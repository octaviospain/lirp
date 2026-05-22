/******************************************************************************
 *     Copyright (C) 2025  Octavio Calleya Garcia                             *
 *                                                                            *
 *     This program is free software: you can redistribute it and/or modify   *
 *     it under the terms of the GNU General Public License as published by   *
 *     the Free Software Foundation, either version 3 of the License, or      *
 *     (at your option) any later version.                                    *
 *                                                                            *
 *     This program is distributed in the hope that it will be useful,        *
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of         *
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
 *     GNU General Public License for more details.                           *
 *                                                                            *
 *     You should have received a copy of the GNU General Public License      *
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>. *
 ******************************************************************************/

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.testing.ReactiveScopeSerialization
import net.transgressoft.lirp.testing.Stress
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Regression test for Issue #200 — the `add()` / `remove()` subscription-lifecycle race in
 * `PersistentRepositoryBase`.
 *
 * Before the fix, `add()` registered the entity's mutation subscription AFTER delegating to
 * `super.add()`, and `remove()` cancelled the subscription AFTER delegating to `super.remove()`.
 * Under concurrent `add()` / `remove()` on the same id, the following interleaving was possible:
 *
 *   T1 (add): super.add() returns true — entity now visible in the map
 *   T2 (remove): super.remove() returns true — entity now invisible again
 *   T2 (remove): subscriptionsMap.remove(id) → null → trips
 *                `error("Repository should contain a subscription for $entity")`
 *   T1 (add): subscribeEntity(entity) — too late, the bug detector has already fired
 *
 * The fix inverts the order in both methods: `add()` registers the subscription BEFORE
 * `super.add()` (with a rollback on already-present), and `remove()` cancels-and-removes the
 * subscription BEFORE `super.remove()`. Under the inversion every interleaving leaves the
 * subscription present from the instant the entity is visible and removes it before the entity
 * becomes invisible, so the `error()` bug detector never fires under correct concurrent usage.
 *
 * The detector itself is preserved as-is — the fix closes the race window, it does not silence
 * the detector. Any future regression that reopens the window will be caught here.
 */
internal class ConcurrentAddRemoveSameKeyStressTest : StringSpec({
    tags(Stress)
    extension(ReactiveScopeSerialization)

    "[ConcurrentAddRemoveSameKey] error invariant never fires under N=8 K=200 add/remove on single id" {
        shouldNotThrowAny {
            val captured = runAddRemoveScenario(seed = 0xC0FFEEL)
            captured.filterIsInstance<IllegalStateException>().shouldBeEmpty()
        }
    }

    "[ConcurrentAddRemoveSameKey] converges across 3 seeds without invariant violation" {
        listOf(0xBADCAFEL, 0xDEADBEEFL, 0xFEEDFACEL).forEach { seed ->
            shouldNotThrowAny {
                val captured = runAddRemoveScenario(seed)
                captured.filterIsInstance<IllegalStateException>().shouldBeEmpty()
            }
        }
    }
})

private const val ADD_REMOVE_WRITER_COUNT = 8
private const val ADD_REMOVE_OPS_PER_WRITER = 200

internal fun runAddRemoveScenario(seed: Long): List<Throwable> {
    val capturedExceptions = CopyOnWriteArrayList<Throwable>()
    val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            capturedExceptions.add(throwable)
        }
    val workerScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    val repo = AddRemoveRepo()
    try {
        val entity = StressEntity(CONTESTED_KEY, "seed-${seed.toString(16)}")
        // Seed the repository so the first ops on every writer have a chance to be either an
        // add (on miss) or a remove (on hit) — without this seeding, the very first wave is
        // dominated by `remove() returning false` (entity not present) which is the contract
        // for absent entities and does not exercise the race.
        repo.add(entity)

        runBlocking {
            val jobs = mutableListOf<Job>()
            repeat(ADD_REMOVE_WRITER_COUNT) { writerIndex ->
                jobs +=
                    workerScope.launch {
                        val rng = Random(seed xor writerIndex.toLong())
                        repeat(ADD_REMOVE_OPS_PER_WRITER) {
                            // Coin-flip per op: deterministic per-(writer, op) using a seeded
                            // Random so a regression on one seed reproduces under that seed.
                            if (rng.nextBoolean()) {
                                repo.add(entity)
                            } else {
                                repo.remove(entity)
                            }
                        }
                    }
            }
            jobs.joinAll()
        }
        repo.close()
        return capturedExceptions.toList()
    } catch (t: Throwable) {
        runCatching { repo.close() }
        throw t
    } finally {
        workerScope.coroutineContext[Job]?.cancel()
    }
}

/**
 * Minimal in-memory [PersistentRepositoryBase] for the add/remove race stress test.
 *
 * `writePending` is fire-and-forget — the test exercises the subscription-lifecycle invariant
 * inside `PersistentRepositoryBase`, not the backing-store write path.
 */
internal class AddRemoveRepo :
    PersistentRepositoryBase<Int, StressEntity>(name = "AddRemoveRepo", loadOnInit = false) {

    init {
        load()
    }

    override fun loadFromStore(): Map<Int, StressEntity> = emptyMap()

    override fun writePending(
        inserts: List<StressEntity>,
        updates: List<PendingUpdate<Int, StressEntity>>,
        deletes: List<Pair<Int, Long?>>,
        hadClear: Boolean
    ) {
        dirty.set(false)
    }
}
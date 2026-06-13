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

import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.testing.ReactiveScopeSerialization
import net.transgressoft.lirp.testing.Stress
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Regression test for Issue #203 — the subscription-handler vs repository-publisher close race.
 *
 * Before the fix, `PersistentRepositoryBase.subscribeEntity` guarded its handler body with a
 * plain `if (!closed)` volatile read. That check could pass on one thread while [close]
 * concurrently transitioned the repository's [net.transgressoft.lirp.event.FlowEventPublisher]
 * to its closed state via `super.close()`. The subsequent `enqueueUpdate(...)` →
 * `onEntityMutated(...)` call then reached `FlowEventPublisher.emitAsync` (in subclasses such as
 * `SqlRepository` that emit `StandardCrudEvent.Update` from `onEntityMutated`), whose
 * `check(!isClosed)` bug detector threw `IllegalStateException("Publisher '...' is closed")` on
 * the worker thread.
 *
 * The fix wraps the handler body in `pendingLock.read { if (closed) return@read; ... }`. Because
 * [close] sets `closed = true` under [pendingLock]'s write lock before calling `super.close()`,
 * every in-flight handler now observes `closed = true` after acquiring the read lock and bails
 * before touching the repository's publisher. This spec exercises the race by mutating an
 * entity's reactive property from N coroutines while the test thread races a `repo.close()`
 * against the storm, and asserts that no `IllegalStateException` from the repository-publisher
 * detector escapes.
 *
 * The detector itself remains in place — the fix closes the race window so the detector stops
 * firing under correct teardown sequencing; it is not silenced. Any future regression that
 * reopens the window will be caught here.
 *
 * The fixture's `onEntityMutated` emits `StandardCrudEvent.Update` on the repository's
 * publisher — mirroring `SqlRepository.onEntityMutated` — so the race surface this spec
 * exercises matches the production failure mode exactly. Worker scopes are cancelled BEFORE
 * `repo.close()` returns, so the entity's own publisher (which auto-closes on subscriber
 * cancellation with `closeOnEmpty = true`) cannot be observed as the source of any captured
 * detector hit — only the repository-publisher race window can produce one.
 */
internal class SubscriptionCloseRaceStressTest : StringSpec({
    tags(Stress)
    // Serialize against every other spec that touches the global ReactiveScope. The writer
    // storm emits hundreds of mutation events through the SharedFlow-backed subscription path;
    // without this extension, a sibling spec running in parallel on Dispatchers.Default-backed
    // ioScope can starve under contention and produce spurious CI failures.
    extension(ReactiveScopeSerialization)

    "[SubscriptionCloseRace] no IllegalStateException when close races with mutating writers" {
        shouldNotThrowAny {
            val captured = runCloseRaceScenario()
            captured.filterIsInstance<IllegalStateException>().shouldBeEmpty()
        }
    }

    "[SubscriptionCloseRace] repository publisher detector remains silent under teardown contention" {
        // The detector at FlowEventPublisher.emitAsync throws with a message containing
        // "Publisher 'CloseRaceRepo' is closed" when triggered against the repo's own publisher.
        // Filtering by the repository name pins this assertion to the #203 race surface and
        // ignores any unrelated noise from entity-level publishers that the framework may close
        // as part of normal teardown.
        val captured = runCloseRaceScenario()
        val repoPublisherHits =
            captured.filter { t ->
                val msg = t.message ?: return@filter false
                msg.contains("Publisher 'CloseRaceRepo' is closed")
            }
        repoPublisherHits.shouldBeEmpty()
    }
})

private const val WRITER_COUNT = 8
private const val CLOSE_RACE_OPS_PER_WRITER = 200
private const val WARMUP_DELAY_MS = 50L
private const val PRE_CLOSE_DELAY_MS = 20L

internal fun runCloseRaceScenario(): List<Throwable> {
    val capturedExceptions = CopyOnWriteArrayList<Throwable>()
    val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            capturedExceptions.add(throwable)
        }
    val workerScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    val repo = CloseRaceRepo()
    try {
        val entity = StressEntity(CONTESTED_KEY, "initial")
        repo.add(entity)

        runBlocking {
            // SharedFlow warmup: the subscription must attach before the writer storm starts,
            // otherwise the first wave of mutations bypasses the production hot path.
            delay(WARMUP_DELAY_MS.milliseconds)

            val jobs = mutableListOf<Job>()
            repeat(WRITER_COUNT) { writerIndex ->
                jobs +=
                    workerScope.launch {
                        repeat(CLOSE_RACE_OPS_PER_WRITER) { opIndex ->
                            entity.label = "w$writerIndex-op$opIndex"
                        }
                    }
            }

            // Let the writer storm warm up so the close() call lands mid-flight. close() races
            // with in-flight handlers; the cancelAndJoin below stops workers BEFORE returning
            // from runBlocking so the entity's own publisher (which auto-closes on subscriber
            // cancellation) cannot be the source of any captured detector hit — that would be
            // unrelated to #203.
            delay(PRE_CLOSE_DELAY_MS.milliseconds)
            repo.close()
            jobs.forEach { it.cancelAndJoin() }
        }
        return capturedExceptions.toList()
    } catch (t: Throwable) {
        runCatching { repo.close() }
        throw t
    } finally {
        // The supervisor job must be cancelled so the JVM does not leak the worker dispatcher
        // threads to subsequent specs. Idempotent against the cancelAndJoin above.
        workerScope.coroutineContext[Job]?.cancel()
    }
}

/**
 * Minimal in-memory [PersistentRepositoryBase] for the close-race stress test.
 *
 * `onEntityMutated` mirrors `SqlRepository.onEntityMutated` by emitting a
 * [StandardCrudEvent.Update] through the repository's own publisher. This is what exercises the
 * #203 race surface — the handler must not call `publisher.emitAsync` after [close] has begun
 * tearing down the publisher.
 */
internal class CloseRaceRepo :
    PersistentRepositoryBase<Int, StressEntity>(name = "CloseRaceRepo", loadOnInit = false) {

    init {
        load()
    }

    override fun loadFromStore(): Map<Int, StressEntity> = emptyMap()

    override fun onEntityMutated(event: MutationEvent<Int, StressEntity>) {
        // Only the entity id matters for the race-condition test; use the live entity for both
        // maps since the test verifies publish-after-close safety, not event content correctness.
        val entityMap = mapOf(event.entity.id to event.entity)
        publisher.emitAsync(StandardCrudEvent.Update(entityMap, entityMap))
    }

    override fun writePending(
        inserts: List<StressEntity>,
        updates: List<PendingUpdate<Int, StressEntity>>,
        deletes: List<Pair<Int, Long?>>,
        hadClear: Boolean
    ) {
        dirty.set(false)
    }
}
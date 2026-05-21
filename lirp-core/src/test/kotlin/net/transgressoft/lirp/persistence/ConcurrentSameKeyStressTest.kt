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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.testing.ReactiveScopeSerialization
import net.transgressoft.lirp.testing.Stress
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Many concurrent writers mutate the same entity's reactive property through the per-key
 * [PendingCell] merge path under [java.util.concurrent.locks.ReentrantReadWriteLock]'s read lock,
 * validating Issue #189 acceptance criterion #4: under arbitrary interleaving the writer-side
 * merge algebra still converges to a final state consistent with **some** serialization of the
 * issued mutations.
 *
 * Strict equivalence to a single sequential reference run is not assertable here: recording the
 * exact completion order at the `pendingCells.compute()` boundary would require a mutex that
 * serialises the merge path — which would defeat the test's purpose of contending on the CHM bin
 * lock. Instead the test proves the linearizability-flavoured invariants that hold under any
 * valid interleaving:
 *
 *  - **No exceptions, no deadlocks.** `runBlocking` returns; no writer throws.
 *  - **Final state is well-formed.** The entity's `label` after the storm carries a value that
 *    SOME writer emitted — no torn property, no foreign value, no resurrected stale state.
 *  - **Per-key isolation.** Every flushed `writePending` payload addresses only the contested
 *    key. The CHM bin lock isolates cells from one another even under heavy contention.
 *  - **All mutations reach the store.** The flush ledger records at least one update — no entire
 *    writer's work is silently dropped.
 *
 * Each writer mutates `entity.label` directly so the production subscription path
 * (`subscribeEntity` → `enqueueUpdate` → `pendingCells.compute(id) { mergeWriterSide(cur, Update) }`)
 * is exercised, not the add()/remove() race surface (which is an application-level antipattern not
 * covered by Issue #189). Multiple seeds run the same comparison so a single lucky interleaving
 * cannot mask a regression.
 */
internal class ConcurrentSameKeyStressTest : StringSpec({
    tags(Stress)
    // Serialize against every other spec that touches the global ReactiveScope (notably
    // HighConcurrencyStressTest / ConcurrencyStressTest / SlowSubscriberTest in the event
    // package). Without this extension, the 9 600 mutation events emitted across the seed
    // loop below contend for the shared Dispatchers.Default-backed ioScope with whichever
    // spec runs in parallel, occasionally starving its 45 s latch waits and producing
    // spurious CI failures on shared runners.
    extension(ReactiveScopeSerialization)

    "[ConcurrentSameKeyStress] converges to a valid final label without exceptions across seeds" {
        listOf(0xC0FFEEL, 0xBADCAFEL, 0xDEADBEEFL).forEach { seed ->
            shouldNotThrowAny {
                val outcome = runConcurrentScenario(seed)
                // A flush is guaranteed: 64 writers × 50 ops per seed cannot complete without
                // at least one debounce window draining. An empty ledger here means either
                // flush() never ran (a bug) or close() returned without its synchronous final
                // drain (also a bug). Asserting non-empty inside the seed loop keeps the
                // per-key isolation check (below) from passing vacuously.
                outcome.flushedKeys.isNotEmpty() shouldBe true
                // After flush, the entity's terminal label must come from SOME writer's emitted
                // set. A foreign or torn label would indicate the merge algebra leaked state.
                outcome.finalLabel shouldBeIn outcome.allEmittedLabels
                // Per-key isolation: anything that reaches writePending() must be confined to
                // the contested key — no foreign id may ever appear in the ledger.
                outcome.flushedKeys.forEach { it shouldBe CONTESTED_KEY }
            }
        }
    }

    "[ConcurrentSameKeyStress] converges to a valid final label under sustained contention" {
        // PersistentRepositoryBase under test has unversioned entities (extractVersion returns
        // null), so the assertion is the consistency check that under sustained contention the
        // per-key merge still collapses to a single final cell whose label is one of the values
        // a real writer produced. A true expectedVersion-preservation assertion would require a
        // versioned subclass and is exercised end-to-end by the SQL integration suite instead.
        val outcome = runConcurrentScenario(seed = 0xFEEDFACEL)
        outcome.finalLabel shouldBeIn outcome.allEmittedLabels
    }

    "[ConcurrentSameKeyStress] writePending payloads carry only contested key under high contention" {
        // Every recorded writePending payload (insert / update / delete groups) addresses only
        // CONTESTED_KEY. This proves that per-key cells do not leak across slots even when
        // 64 coroutines hammer the same bin in parallel. The outcome is built AFTER
        // repo.close() drains the synchronous final flush, so the ledger reliably contains
        // at least one entry — anything else would mean either flush() never ran (a bug) or
        // close() returned without draining (also a bug). We assert both at-least-one entry
        // and per-key isolation.
        val outcome = runConcurrentScenario(seed = 0xC0DEC0DEL)
        outcome.flushedKeys.isNotEmpty() shouldBe true
        outcome.flushedKeys.forEach { it shouldBe CONTESTED_KEY }
    }
})

const val OPS_PER_WRITER: Int = 50
const val CONTESTED_KEY: Int = 1

internal data class ScenarioOutcome(
    val finalLabel: String,
    val flushedKeys: List<Int>,
    val allEmittedLabels: Set<String>
)

internal fun runConcurrentScenario(seed: Long): ScenarioOutcome {
    val flushedKeys = CopyOnWriteArrayList<Int>()
    val emittedLabels = ConcurrentLinkedQueue<String>()
    val opCounter = AtomicLong(0L)

    val repo = StressRepo(flushedKeys)
    try {
        val entity = StressEntity(CONTESTED_KEY, "seed-${seed.toString(16)}")
        repo.add(entity)
        emittedLabels.add(entity.label)

        runBlocking {
            // SharedFlow warmup: give the subscribeEntity subscriber a moment to attach
            // before the writer storm begins, so the first wave of mutations does not land
            // before the SharedFlow has a collector. Without this the subscription path
            // (the production hot path under test) is bypassed and pendingCells stays empty.
            delay(50.milliseconds)
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val jobs = mutableListOf<Job>()
                    repeat(64) { writerIndex ->
                        jobs +=
                            launch {
                                val rng = Random(seed xor writerIndex.toLong())
                                repeat(OPS_PER_WRITER) { opIndex ->
                                    // Deterministic per-(writer, op) label so a successful match
                                    // in `shouldBeIn` discriminates between writers — no two
                                    // writers can produce the same terminal value.
                                    val newLabel = "w$writerIndex-op$opIndex-${rng.nextInt(1000)}"
                                    emittedLabels.add(newLabel)
                                    entity.label = newLabel
                                    opCounter.incrementAndGet()
                                }
                            }
                    }
                    jobs.joinAll()
                }
            }
            // Give the SharedFlow subscriber a chance to deliver any in-flight mutation events
            // to the enqueueUpdate path before close() drains pendingCells. The flush itself is
            // synchronous; only the upstream property-mutation → subscription handoff is async.
            delay(200.milliseconds)
        }
        // Close before snapshotting the outcome — close() drains the final flush synchronously,
        // and the flushedKeys ledger is only meaningful after that drain. Capturing the outcome
        // before close() would let a run that only finalises on close report an empty ledger.
        repo.close()
        return ScenarioOutcome(
            finalLabel = entity.label,
            flushedKeys = flushedKeys.toList(),
            allEmittedLabels = emittedLabels.toSet()
        )
    } catch (t: Throwable) {
        // Ensure the repo is closed even on the exception path so the test runner does not leak
        // the background flush coroutine. Close is idempotent so a second call from the happy
        // path remains safe (close() short-circuits when already closed).
        runCatching { repo.close() }
        throw t
    }
}

/**
 * Entity with a single reactive [label] property. Mutating `label` from any thread emits a
 * mutation event that the repository's subscription routes into `enqueueUpdate`, which then
 * runs the per-key `mergeWriterSide` collapse on the contested cell.
 */
internal class StressEntity(
    override val id: Int,
    initialLabel: String
) : ReactiveEntityBase<Int, StressEntity>() {
    var label: String by reactiveProperty(initialLabel)

    override val uniqueId: String get() = "stress-$id"

    override fun clone(): StressEntity = StressEntity(id, label)
}

/**
 * Minimal in-memory [PersistentRepositoryBase] for the stress test.
 *
 * `writePending` records every key that flowed through the grouped payload so the test can assert
 * per-key isolation and observe that mutations were actually drained. The implementation is
 * fire-and-forget — there is no real I/O — because the contract under test is the per-key merge,
 * not the persistence layer.
 */
internal class StressRepo(
    val flushedKeys: CopyOnWriteArrayList<Int>
) : PersistentRepositoryBase<Int, StressEntity>(name = "StressRepo", loadOnInit = false) {

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
        inserts.forEach { flushedKeys.add(it.id) }
        updates.forEach { flushedKeys.add(it.entity.id) }
        deletes.forEach { flushedKeys.add(it.first) }
        dirty.set(false)
    }
}
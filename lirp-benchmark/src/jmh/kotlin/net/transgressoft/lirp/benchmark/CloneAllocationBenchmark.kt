package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.event.MutationEvent
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jol.info.GraphLayout
import java.util.concurrent.TimeUnit

/**
 * Retained-size probe that a single reactive property assignment with exactly one subscriber does
 * not leave an entity clone reachable from the entity after the assignment.
 *
 * The event hierarchy refactoring removed the pre-mutation `entity.clone()` call from
 * [net.transgressoft.lirp.entity.ReactiveEntityBase]. Instead, each
 * [net.transgressoft.lirp.event.PropertyChanged] event carries immutable scalars (old value, new
 * value, captured index keys) frozen synchronously at assignment time — no copy of the entity graph
 * is needed.
 *
 * **What this measures, and what it does not.** `GraphLayout.parseInstance(entity).totalSize()` is a
 * point-in-time snapshot of the *retained, reachable* footprint of the object graph rooted at the
 * entity — not the total bytes allocated during the assignment. A clone that was allocated inside the
 * setter and then became unreachable would not show up in this retained-size delta. A zero delta here
 * therefore confirms only that no clone-sized graph is *retained* after the assignment; it is a
 * secondary signal, not a standalone proof of zero allocation.
 *
 * **Authoritative per-operation allocation.** To catch a transient clone (allocated then freed within
 * the op), run with the JMH GC profiler and read `gc.alloc.rate.norm` (bytes/op):
 *
 * ```
 * gradle :lirp-benchmark:jmh -Pjmh.includes=CloneAllocation -Pjmh.profilers=gc
 * ```
 *
 * That metric counts every allocation in the iteration. It is not expected to be zero — a small
 * [net.transgressoft.lirp.event.PropertyChanged] event is allocated per mutation by design — but it
 * should stay at the small-event level and must not scale with the entity-graph size, which is the
 * regression a re-introduced `clone()` would cause.
 *
 * Benchmark mode is [Mode.SingleShotTime] so each invocation triggers one mutation and one JOL walk.
 * JOL requires the `--add-opens` / `-Djol.magicFieldOffset=true` JVM flags already configured in
 * `lirp-benchmark/build.gradle`.
 */
@State(Scope.Benchmark)
@Fork(3)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class CloneAllocationBenchmark {

    lateinit var entity: BenchmarkEntity

    @Setup(Level.Invocation)
    fun setup() {
        entity = BenchmarkEntity(42, "before", 10)
        // One subscriber — the case that used to require a pre-mutation clone.
        entity.subscribe { _: MutationEvent<Int, BenchmarkEntity> -> }
        // Warm the emission path once so the lazily-initialised event-flow buffers are
        // allocated before the baseline snapshot; the measured assignment then isolates
        // steady-state per-mutation allocation rather than one-time flow setup.
        entity.name = "warmup"
    }

    @TearDown(Level.Invocation)
    fun tearDown() {
        entity.close()
    }

    /**
     * Measures the retained-size delta of the entity graph before and after a single reactive
     * property assignment with one subscriber.
     *
     * A zero (or negative) delta confirms no clone-sized graph is retained after the assignment:
     * the reachable footprint stays flat because [net.transgressoft.lirp.event.PropertyChanged]
     * carries value scalars, not a full entity copy. Pair with `gc.alloc.rate.norm` (see the class
     * KDoc) for the authoritative per-operation allocation figure.
     */
    @Benchmark
    fun noCloneOnSinglePropertyAssignment(bh: Blackhole) {
        val before = GraphLayout.parseInstance(entity).totalSize()

        entity.name = "after"

        val after = GraphLayout.parseInstance(entity).totalSize()
        val deltaBytes = after - before
        println("clone_alloc_delta_bytes=$deltaBytes")
        bh.consume(deltaBytes)
    }
}
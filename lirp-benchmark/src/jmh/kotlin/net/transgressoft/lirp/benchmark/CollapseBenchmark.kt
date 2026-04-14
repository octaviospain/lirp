package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.PendingDelete
import net.transgressoft.lirp.persistence.PendingInsert
import net.transgressoft.lirp.persistence.PendingOp
import net.transgressoft.lirp.persistence.PendingUpdate
import net.transgressoft.lirp.persistence.collapse
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * JMH throughput benchmark for the [collapse] algorithm.
 *
 * Measures how many collapse operations per second the algorithm can perform on a pre-built
 * list of [opCount] pending operations. The operation list uses a realistic 1:1:1 mix of
 * inserts, updates, and deletes on overlapping entity IDs to exercise the full deduplication
 * logic including insert+update merge, update+delete merge, and insert+delete cancellation.
 *
 * Each benchmark is parameterized by [opCount] at 100, 1000, 10000, and 50000 operations.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class CollapseBenchmark {

    @Param("100", "1000", "10000", "50000")
    var opCount: Int = 0

    lateinit var ops: List<PendingOp<Int, BenchmarkEntity>>

    @Setup(Level.Trial)
    fun setup() {
        // Build a realistic mix: one-third inserts, one-third updates on overlapping IDs,
        // one-third deletes on overlapping IDs. Overlapping IDs exercise the collapse merging
        // rules: insert+update -> insert, update+delete -> delete, insert+delete -> no-op.
        val entityRange = opCount / 3
        val built = ArrayList<PendingOp<Int, BenchmarkEntity>>(opCount)

        // Insert phase: ids 0..<entityRange
        repeat(entityRange) { i ->
            built.add(PendingInsert(BenchmarkEntity(i, "entity-$i")))
        }
        // Update phase: ids 0..<entityRange (overlaps inserts -> insert absorbs update)
        repeat(entityRange) { i ->
            built.add(PendingUpdate(BenchmarkEntity(i, "entity-$i-updated")))
        }
        // Delete phase: ids entityRange/2..<entityRange + entityRange/2 (partial overlap)
        val deleteStart = entityRange / 2
        repeat(opCount - 2 * entityRange) { i ->
            built.add(PendingDelete(deleteStart + i))
        }

        ops = built
    }

    /**
     * Measures [collapse] throughput on a pre-built pending operation list.
     *
     * The operation list is immutable and reused across invocations. [Blackhole] consumes the
     * collapsed result to prevent JIT dead-code elimination.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    fun collapseOps(bh: Blackhole) {
        bh.consume(collapse(ops))
    }
}
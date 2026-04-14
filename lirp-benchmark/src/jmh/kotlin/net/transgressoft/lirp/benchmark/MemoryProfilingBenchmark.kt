package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.persistence.VolatileRepository
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jol.info.GraphLayout
import java.util.concurrent.TimeUnit

/**
 * JOL-based heap memory profiling for [BenchmarkEntity] under varying subscriber counts,
 * secondary-index overhead via [VolatileRepository], and peak memory during bulk initialization.
 *
 * Memory values are printed to stdout within each benchmark method so the JMH console output
 * captures them. The wiki results table extracts these values for the hardware/JVM baseline record.
 *
 * Uses [Mode.SingleShotTime] because memory measurements are one-shot calculations — each
 * benchmark invocation performs a single JOL graph walk and reports the result.
 *
 * JOL requires `--add-opens` JVM flags already configured in the `jmh { jvmArgs }` block of
 * `lirp-benchmark/build.gradle`.
 */
@State(Scope.Benchmark)
@Fork(3)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class MemoryProfilingBenchmark {

    @Param("0", "1", "5", "10")
    var subscriberCount: Int = 0

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    // Pre-created entity for subscriber-count measurements (D-08)
    lateinit var subscriberEntity: BenchmarkEntity

    // Repository for index-overhead measurements (D-09)
    lateinit var indexRepo: VolatileRepository<Int, BenchmarkEntity>

    @Setup(Level.Trial)
    fun setup() {
        subscriberEntity = BenchmarkEntity(1, "mem-probe")
        repeat(subscriberCount) { _ ->
            subscriberEntity.subscribe { _: MutationEvent<Int, BenchmarkEntity> -> }
        }

        indexRepo = VolatileRepository("mem-index-bench")
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        indexRepo.clear()
    }

    /**
     * Measures the heap size of a single [BenchmarkEntity] with [subscriberCount] MUTATION subscribers.
     *
     * Per D-08: reports the full object graph size including the subscriber list,
     * publisher, and all reachable state.
     */
    @Benchmark
    fun heapPerEntityWithSubscribers(bh: Blackhole) {
        val bytes = GraphLayout.parseInstance(subscriberEntity).totalSize()
        println("heap_per_entity_${subscriberCount}_subscribers=$bytes")
        bh.consume(bytes)
    }

    /**
     * Measures the incremental heap cost of registering an entity into a [VolatileRepository]
     * that carries secondary-index metadata (per D-09).
     *
     * Compares the object graph before and after repository registration to isolate the
     * overhead from index structures, ref bindings, and event subscriptions.
     */
    @Benchmark
    fun heapWithSecondaryIndex(bh: Blackhole) {
        val entity = BenchmarkEntity(999_999, "index-probe")
        val beforeBytes = GraphLayout.parseInstance(entity).totalSize()
        indexRepo.add(entity)
        val repoBytes = GraphLayout.parseInstance(indexRepo).totalSize()
        println("heap_entity_before_index=$beforeBytes heap_repo_with_entity=$repoBytes")
        bh.consume(repoBytes)
    }

    /**
     * Measures peak heap delta during bulk initialization of a [VolatileRepository] with [entityCount] entities.
     *
     * Per D-10: uses [Runtime.getRuntime] before and after initialization (coarse but appropriate
     * for bulk-load measurement where JOL graph walk over the full collection is impractical).
     * Forces GC before the baseline measurement to reduce GC noise.
     */
    @Benchmark
    fun peakMemoryDuringInit(bh: Blackhole) {
        val rt = Runtime.getRuntime()
        System.gc()
        val beforeUsed = rt.totalMemory() - rt.freeMemory()

        val repo = VolatileRepository<Int, BenchmarkEntity>("mem-init-bench")
        repeat(entityCount) { i -> repo.add(BenchmarkEntity(i, "init-$i")) }

        val afterUsed = rt.totalMemory() - rt.freeMemory()
        val deltaBytes = afterUsed - beforeUsed
        println("peak_memory_init_${entityCount}_entities_delta_bytes=$deltaBytes")
        bh.consume(deltaBytes)
        repo.clear()
    }
}
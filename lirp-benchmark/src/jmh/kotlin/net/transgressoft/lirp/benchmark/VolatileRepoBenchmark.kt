package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.VolatileRepository
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
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JMH microbenchmarks for [VolatileRepository] covering add throughput and findById latency.
 *
 * Each benchmark is parameterized by [entityCount] at 100, 1000, 10000, and 50000 entities.
 * All methods use [Blackhole] to prevent JIT dead-code elimination of the result values.
 *
 * Note: `findByIndex` is not benchmarked for [VolatileRepository] because secondary indexes
 * require KSP-generated [net.transgressoft.lirp.persistence.IndexEntry] accessors.
 * Since the benchmark module does not run KSP, index registration is unavailable without it,
 * making a meaningful `findByIndex` benchmark not applicable here.
 * The `findByIndex` operation is covered in [SqlRepoBenchmark] via native SQL WHERE clauses.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class VolatileRepoBenchmark {

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    lateinit var repo: VolatileRepository<Int, BenchmarkEntity>

    private val nextId = AtomicInteger()

    @Setup(Level.Trial)
    fun setup() {
        repo = VolatileRepository("volatile-bench")
        repeat(entityCount) { i -> repo.add(BenchmarkEntity(i, "entity-$i")) }
        nextId.set(entityCount)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        repo.clear()
    }

    /**
     * Measures add throughput for [VolatileRepository].
     *
     * Each invocation inserts a new entity with a unique ID above [entityCount].
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    fun addEntity(bh: Blackhole) {
        val id = nextId.getAndIncrement()
        bh.consume(repo.add(BenchmarkEntity(id, "entity-$id")))
    }

    /**
     * Measures findById latency for [VolatileRepository] using [Mode.SampleTime].
     *
     * SampleTime produces p50, p95, and p99 latency percentiles in the JMH JSON output.
     * The lookup key is always the middle entity to avoid any ordering bias.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun findById(bh: Blackhole) {
        bh.consume(repo.findById(entityCount / 2))
    }
}
package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.json.JsonFileRepository
import net.transgressoft.lirp.persistence.json.lirpSerializer
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
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * JMH microbenchmarks for [JsonFileRepository] covering add throughput and
 * mutation-to-file-flush round-trip latency.
 *
 * Each benchmark is parameterized by [entityCount] at 100, 1000, 10000, and 50000 entities.
 *
 * **Serialization:** [BenchmarkEntity] does not carry `@Serializable`. Serialization is handled
 * via [lirpSerializer] which introspects the entity's delegate registry at construction time,
 * eliminating the need for KSP or `@Serializable` annotations.
 *
 * **File isolation:** Each trial creates a fresh temporary JSON file and deletes it in teardown.
 *
 * **Mutation flush:** [mutationFlush] forces a synchronous flush via [JsonFileRepository.close]
 * to measure the full mutation-to-disk round-trip (Pitfall 3 mitigation). The repository is
 * re-opened immediately after close so subsequent invocations within the same trial remain valid.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class JsonRepoBenchmark {

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    lateinit var jsonFile: File
    lateinit var repo: JsonFileRepository<Int, BenchmarkEntity>

    // Cached at field level to avoid recreating the serializer on every mutationFlush invocation
    val mapSerializer: KSerializer<Map<Int, BenchmarkEntity>> =
        MapSerializer(Int.serializer(), lirpSerializer(BenchmarkEntity(0, "sample")))

    private val nextId = AtomicInteger()

    @Setup(Level.Trial)
    fun setup() {
        val tempDir = Files.createTempDirectory("lirp-bench-json").toFile()
        jsonFile = File(tempDir, "benchmark.json").also { it.createNewFile() }

        repo = JsonFileRepository(jsonFile, mapSerializer)
        repeat(entityCount) { i -> repo.add(BenchmarkEntity(i, "entity-$i")) }
        // Force initial flush so all entities are on disk before measurement starts
        repo.close()

        // Re-open so the repo is ready for benchmark invocations
        repo = JsonFileRepository(jsonFile, mapSerializer)
        nextId.set(entityCount)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        try {
            repo.close()
        } catch (_: Exception) {
            // Already closed in mutationFlush — safe to ignore
        }
        jsonFile.parentFile?.deleteRecursively()
    }

    /**
     * Measures add throughput for [JsonFileRepository].
     *
     * Each invocation inserts a new entity. The actual file write is deferred by the debounce
     * pipeline, so this measures in-memory add + enqueue latency, not disk I/O.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    fun addEntity(bh: Blackhole) {
        val id = nextId.getAndIncrement()
        bh.consume(repo.add(BenchmarkEntity(id, "entity-$id")))
    }

    /**
     * Measures full mutation-to-disk round-trip latency for [JsonFileRepository].
     *
     * Mutates the name property of the middle entity, then forces a synchronous flush by calling
     * [JsonFileRepository.close]. This bypasses the debounce timer to capture the true end-to-end
     * write latency. The repository is re-opened immediately after close so subsequent invocations
     * within the same trial remain valid.
     *
     * Note: because close() is called here, [tearDown] guards against double-close gracefully.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    fun mutationFlush(bh: Blackhole) {
        val entity = repo.findById(entityCount / 2).orElse(null)
        if (entity != null) {
            entity.name = "mutated-${System.nanoTime()}"
            // Force synchronous flush — measures full mutation-to-disk round-trip
            repo.close()
            bh.consume(entity)
            // Re-open for subsequent invocations within the same trial
            repo = JsonFileRepository(jsonFile, mapSerializer)
        }
    }
}
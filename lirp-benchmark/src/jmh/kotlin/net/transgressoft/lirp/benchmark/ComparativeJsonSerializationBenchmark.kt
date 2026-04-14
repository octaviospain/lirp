package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.json.JsonFileRepository
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Compares [JsonFileRepository] against raw [Json.encodeToString] + file write for equivalent
 * serialization operations. Measures the overhead that JsonFileRepository's debounce pipeline,
 * in-memory state management, and repository lifecycle add over direct kotlinx.serialization.
 *
 * The raw serialization path uses [SerializableBenchmarkEntity], a data class equivalent of
 * [BenchmarkEntity] annotated with [@Serializable][Serializable] for direct kotlinx.serialization use.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
open class ComparativeJsonSerializationBenchmark {

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    lateinit var jsonRepo: JsonFileRepository<Int, BenchmarkEntity>
    lateinit var repoJsonFile: File
    lateinit var tempDir: File

    lateinit var rawEntities: Map<Int, SerializableBenchmarkEntity>
    lateinit var rawJsonFile: File

    val trialCounter = AtomicInteger(0)
    val json =
        Json {
            prettyPrint = true
            explicitNulls = true
            allowStructuredMapKeys = true
        }
    val rawSerializer: KSerializer<Map<Int, SerializableBenchmarkEntity>> =
        MapSerializer(Int.serializer(), SerializableBenchmarkEntity.serializer())

    @Setup(Level.Trial)
    fun setup() {
        val trial = trialCounter.incrementAndGet()
        tempDir = Files.createTempDirectory("lirp-json-bench-$trial").toFile()

        // JsonFileRepository side
        repoJsonFile = File(tempDir, "repo-$trial.json").also { it.createNewFile() }
        jsonRepo = JsonFileRepository(repoJsonFile, BenchmarkEntityMapSerializer.instance)
        repeat(entityCount) { i -> jsonRepo.add(BenchmarkEntity(i, "entity-$i")) }

        // Raw serialization side: pre-built entity map
        rawEntities =
            (0 until entityCount).associateWith { i ->
                SerializableBenchmarkEntity(i, "entity-$i", "entity-$i")
            }
        rawJsonFile = File(tempDir, "raw-$trial.json").also { it.createNewFile() }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        jsonRepo.close()
        tempDir.deleteRecursively()
    }

    /** Adds a new entity to [JsonFileRepository]. Paired with [rawSerializationWrite]. */
    @Benchmark
    fun jsonRepoAdd(bh: Blackhole) {
        val id = entityCount + System.nanoTime().toInt()
        bh.consume(jsonRepo.add(BenchmarkEntity(id, "new-$id")))
    }

    /**
     * Serializes the full entity map to a JSON string and writes it to a file.
     * Paired with [jsonRepoAdd] to measure raw serialization overhead.
     */
    @Benchmark
    fun rawSerializationWrite(bh: Blackhole) {
        val jsonString = json.encodeToString(rawSerializer, rawEntities)
        rawJsonFile.writeText(jsonString)
        bh.consume(jsonString.length)
    }

    /** Mutates an entity in [JsonFileRepository] and closes to force flush. Paired with [rawSerializationMutationWrite]. */
    @Benchmark
    fun jsonRepoMutationFlush(bh: Blackhole) {
        val optional = jsonRepo.findById(entityCount / 2)
        if (optional.isPresent) optional.get().name = "mutated-${System.nanoTime()}"
        // Close forces the debounce pipeline to flush synchronously
        bh.consume(optional)
    }

    /**
     * Mutates the raw entity map and serializes the full state to a file.
     * Paired with [jsonRepoMutationFlush].
     */
    @Benchmark
    fun rawSerializationMutationWrite(bh: Blackhole) {
        val id = entityCount / 2
        val updated = rawEntities.toMutableMap()
        updated[id] = SerializableBenchmarkEntity(id, "entity-$id", "mutated-${System.nanoTime()}")
        val jsonString = json.encodeToString(rawSerializer, updated)
        rawJsonFile.writeText(jsonString)
        bh.consume(jsonString.length)
    }
}

/**
 * Serializable counterpart of [BenchmarkEntity] for the raw kotlinx.serialization comparison path.
 *
 * [BenchmarkEntity] extends [ReactiveEntityBase][net.transgressoft.lirp.entity.ReactiveEntityBase]
 * which is not [@Serializable][Serializable]. This data class carries the same logical fields
 * (id, label, name) for a fair comparison without the reactive overhead.
 */
@Serializable
data class SerializableBenchmarkEntity(val id: Int, val label: String, val name: String)

/**
 * Manual [KSerializer] for [Map] of [Int] to [BenchmarkEntity] used by [JsonFileRepository]
 * in the benchmark. Wraps the standard [MapSerializer] with a custom entity serializer.
 */
object BenchmarkEntityMapSerializer {
    val instance: KSerializer<Map<Int, BenchmarkEntity>> =
        MapSerializer(Int.serializer(), BenchmarkEntityKSerializer)
}

/**
 * Minimal [KSerializer] for [BenchmarkEntity] that encodes only the fields relevant
 * to the benchmark comparison: [BenchmarkEntity.id], [BenchmarkEntity.label], and [BenchmarkEntity.name].
 */
object BenchmarkEntityKSerializer : KSerializer<BenchmarkEntity> {
    private val delegate = SerializableBenchmarkEntity.serializer()
    override val descriptor = delegate.descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: BenchmarkEntity) {
        delegate.serialize(encoder, SerializableBenchmarkEntity(value.id, value.label, value.name))
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): BenchmarkEntity {
        val data = delegate.deserialize(decoder)
        return BenchmarkEntity(data.id, data.label).also { it.name = data.name }
    }
}
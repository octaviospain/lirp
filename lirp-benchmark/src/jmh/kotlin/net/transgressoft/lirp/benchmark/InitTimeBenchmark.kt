package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.VolatileRepository
import net.transgressoft.lirp.persistence.json.JsonFileRepository
import net.transgressoft.lirp.persistence.json.lirpSerializer
import net.transgressoft.lirp.persistence.sql.SqlRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * JMH initialization-time benchmark measuring cold-start repository construction time.
 *
 * Covers all three repository types — [VolatileRepository], [SqlRepository], and
 * [JsonFileRepository] — each parameterized by [entityCount] at 100, 1000, 10000, and 50000.
 *
 * Uses [Mode.SingleShotTime] to measure a single cold-start rather than steady-state throughput.
 * [Fork] is set to 5 to provide statistical significance: each fork is a new JVM with a fresh
 * repository instance, eliminating JIT warm-up bias across measurements.
 *
 * The key measurement in each benchmark is repository construction and row loading, not insertion.
 * Rows are pre-inserted during [setup] before the benchmark invocation. Each benchmark creates a
 * NEW repository instance that loads existing data from the pre-populated store.
 */
@State(Scope.Benchmark)
@Fork(5)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class InitTimeBenchmark {

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    // JSON init state
    lateinit var jsonFile: File

    val mapSerializer: KSerializer<Map<Int, BenchmarkEntity>>
        get() = MapSerializer(Int.serializer(), lirpSerializer(BenchmarkEntity(0, "sample")))

    // SQL init state
    lateinit var sqlDataSource: HikariDataSource
    lateinit var sqlDbUrl: String

    @Setup(Level.Trial)
    fun setup() {
        // --- JSON pre-population ---
        val tempDir = Files.createTempDirectory("lirp-init-bench-json").toFile()
        jsonFile = File(tempDir, "init-benchmark.json").also { it.createNewFile() }
        val prepRepo = JsonFileRepository(jsonFile, mapSerializer)
        repeat(entityCount) { i -> prepRepo.add(BenchmarkEntity(i, "entity-$i")) }
        prepRepo.close()

        // --- SQL pre-population ---
        sqlDbUrl = "jdbc:h2:mem:init_bench_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        val config =
            HikariConfig().apply {
                jdbcUrl = sqlDbUrl
                maximumPoolSize = 4
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            }
        sqlDataSource = HikariDataSource(config)

        // Pre-create schema and insert rows
        val prepSqlRepo = SqlRepository(sqlDataSource, BenchmarkEntityTableDef)
        repeat(entityCount) { i -> prepSqlRepo.add(BenchmarkEntity(i, "entity-$i")) }
        // Force flush of all inserts to the database
        prepSqlRepo.close()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        jsonFile.parentFile?.deleteRecursively()
        sqlDataSource.close()
    }

    /**
     * Measures [VolatileRepository] cold-start initialization: create repository and add [entityCount] entities.
     *
     * VolatileRepository has no persistent backing store, so initialization is a pure in-memory
     * add loop rather than a load-from-storage operation. This makes it an insertion baseline,
     * not a load baseline like [initSql] and [initJson], which construct from pre-populated stores.
     * It still serves as a useful lower bound on repository construction cost.
     */
    @Benchmark
    fun initVolatile(bh: Blackhole) {
        val repo = VolatileRepository<Int, BenchmarkEntity>("init-bench")
        repeat(entityCount) { i -> repo.add(BenchmarkEntity(i, "entity-$i")) }
        bh.consume(repo.size())
        repo.clear()
    }

    /**
     * Measures [SqlRepository] cold-start initialization: construct a repository against a
     * pre-populated H2 database and load all [entityCount] rows into memory.
     *
     * The database was pre-populated during [setup]. This benchmark measures only the
     * construction + SQL SELECT + in-memory load time, not the insert cost.
     */
    @Benchmark
    fun initSql(bh: Blackhole) {
        val repo = SqlRepository(sqlDataSource, BenchmarkEntityTableDef)
        bh.consume(repo.size())
        repo.close()
    }

    /**
     * Measures [JsonFileRepository] cold-start initialization: construct a repository from a
     * pre-populated JSON file and deserialize all [entityCount] entities into memory.
     *
     * The JSON file was populated during [setup]. This benchmark measures only the
     * construction + JSON deserialization + in-memory load time, not the write cost.
     */
    @Benchmark
    fun initJson(bh: Blackhole) {
        val repo = JsonFileRepository(jsonFile, mapSerializer)
        bh.consume(repo.size())
        repo.close()
    }
}
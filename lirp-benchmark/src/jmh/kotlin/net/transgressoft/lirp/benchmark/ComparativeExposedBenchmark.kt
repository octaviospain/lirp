package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.sql.SqlRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
 * Compares [SqlRepository] against direct JetBrains Exposed [transaction] blocks for equivalent
 * CRUD operations. Both sides use the same H2 in-memory database engine with separate databases
 * for isolation, enabling direct overhead measurement of the SqlRepository abstraction layer.
 *
 * The key comparison metric is the overhead percentage added by SqlRepository relative to direct
 * Exposed transactions for the same logical operation.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class ComparativeExposedBenchmark {

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    lateinit var sqlRepo: SqlRepository<Int, BenchmarkEntity>
    lateinit var repoDataSource: HikariDataSource

    lateinit var exposedDataSource: HikariDataSource
    lateinit var exposedDb: Database
    lateinit var exposedTable: BenchmarkExposedTable

    // Unique DB names per trial to avoid H2 schema accumulation between parameter sets
    val trialCounter = AtomicInteger(0)

    @Setup(Level.Trial)
    fun setup() {
        val trial = trialCounter.incrementAndGet()

        // SqlRepository side: dedicated H2 in-memory database
        val repoDbUrl = "jdbc:h2:mem:bench_repo_$trial;DB_CLOSE_DELAY=-1"
        repoDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = repoDbUrl
                    maximumPoolSize = 5
                }
            )
        sqlRepo = SqlRepository(repoDataSource, BenchmarkEntityTableDef)
        repeat(entityCount) { i -> sqlRepo.add(BenchmarkEntity(i, "entity-$i")) }

        // Direct Exposed side: separate H2 in-memory database
        val exposedDbUrl = "jdbc:h2:mem:bench_exposed_$trial;DB_CLOSE_DELAY=-1"
        exposedDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = exposedDbUrl
                    maximumPoolSize = 5
                }
            )
        exposedDb = Database.connect(exposedDataSource)
        exposedTable = BenchmarkExposedTable()
        transaction(db = exposedDb) {
            SchemaUtils.create(exposedTable)
            repeat(entityCount) { i ->
                exposedTable.insert {
                    it[exposedTable.id] = i
                    it[exposedTable.label] = "entity-$i"
                    it[exposedTable.name] = "entity-$i"
                }
            }
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        sqlRepo.close()
        repoDataSource.close()
        transaction(db = exposedDb) { SchemaUtils.drop(exposedTable) }
        exposedDataSource.close()
    }

    /** Adds a new entity via SqlRepository. Paired with [directExposedAdd]. */
    @Benchmark
    fun sqlRepoAdd(bh: Blackhole) {
        val id = entityCount + System.nanoTime().toInt()
        bh.consume(sqlRepo.add(BenchmarkEntity(id, "new-$id")))
    }

    /** Adds a new row via direct Exposed transaction. Paired with [sqlRepoAdd]. */
    @Benchmark
    fun directExposedAdd(bh: Blackhole) {
        val id = entityCount + System.nanoTime().toInt()
        bh.consume(
            transaction(db = exposedDb) {
                exposedTable.insert {
                    it[exposedTable.id] = id
                    it[exposedTable.label] = "new-$id"
                    it[exposedTable.name] = "new-$id"
                }
            }
        )
    }

    /** Looks up an entity by ID via SqlRepository. Paired with [directExposedFindById]. */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun sqlRepoFindById(bh: Blackhole) {
        bh.consume(sqlRepo.findById(entityCount / 2))
    }

    /** Looks up a row by ID via direct Exposed query. Paired with [sqlRepoFindById]. */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun directExposedFindById(bh: Blackhole) {
        val targetId = entityCount / 2
        bh.consume(
            transaction(db = exposedDb) {
                exposedTable.selectAll()
                    .where { exposedTable.id eq targetId }
                    .singleOrNull()
            }
        )
    }
}

/** Exposed table definition for [BenchmarkEntity] used in the direct Exposed comparison path. */
class BenchmarkExposedTable : Table("benchmark_entities") {
    val id = integer("id")
    val label = varchar("label", 255)
    val name = varchar("name", 255)
    override val primaryKey = PrimaryKey(id)
}
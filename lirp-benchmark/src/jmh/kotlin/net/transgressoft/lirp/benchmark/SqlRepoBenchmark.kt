package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.sql.ExposedTableInterpreter
import net.transgressoft.lirp.persistence.sql.SqlRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JMH microbenchmarks for [SqlRepository] covering add throughput, findById latency,
 * SQL WHERE lookup latency, and mutation-to-flush round-trip latency.
 *
 * Each benchmark is parameterized by [entityCount] at 100, 1000, 10000, and 50000 entities.
 *
 * **Database isolation:** Each trial creates a fresh H2 in-memory database with a unique URL
 * to prevent row accumulation between parameter sets (Pitfall 4 in JMH research). The URL
 * follows the pattern `jdbc:h2:mem:bench_<UUID>;DB_CLOSE_DELAY=-1`.
 *
 * **findByIndex note:** [SqlRepository] uses the same in-memory secondary index mechanism as
 * [net.transgressoft.lirp.persistence.VolatileRepository], which requires KSP-generated
 * [net.transgressoft.lirp.persistence.IndexEntry] accessors. Since the benchmark module does
 * not run KSP, the [findByLabel] benchmark uses a direct Exposed SQL WHERE query instead,
 * which accurately reflects the SQL-level column lookup cost.
 *
 * **Mutation flush:** [mutationFlush] forces a synchronous flush via [SqlRepository.close] to
 * measure the full mutation-to-database round-trip time without relying on the debounce timer.
 * The repository is re-created in the next trial setup.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class SqlRepoBenchmark {

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    lateinit var dataSource: HikariDataSource
    lateinit var repo: SqlRepository<Int, BenchmarkEntity>
    lateinit var exposedTable: Table
    lateinit var db: Database

    private val nextId = AtomicInteger()

    @Setup(Level.Trial)
    fun setup() {
        // Unique URL per trial prevents row accumulation across parameter sets
        val dbUrl = "jdbc:h2:mem:bench_${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
        val config =
            HikariConfig().apply {
                jdbcUrl = dbUrl
                maximumPoolSize = 4
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            }
        dataSource = HikariDataSource(config)
        db = Database.connect(dataSource)

        // Expose the internal table for the findByLabel benchmark
        val interpreted = ExposedTableInterpreter().interpret(BenchmarkEntityTableDef)
        exposedTable = interpreted.table

        // Ensure schema exists before SqlRepository (which also creates it, but we need the table ref)
        transaction(db) { SchemaUtils.create(exposedTable) }

        // SqlRepository creates the schema (no-op since already created above) and loads on init
        repo = SqlRepository(dataSource, BenchmarkEntityTableDef)
        repeat(entityCount) { i -> repo.add(BenchmarkEntity(i, "entity-$i")) }
        // Force initial flush so setup rows are in the database before measurements begin
        repo.close()

        // Re-open for the benchmark
        repo = SqlRepository(dataSource, BenchmarkEntityTableDef)
        nextId.set(entityCount)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        try {
            repo.close()
        } catch (_: Exception) {
            // Already closed in mutationFlush — safe to ignore
        }
        dataSource.close()
    }

    /**
     * Measures add throughput for [SqlRepository].
     *
     * Each invocation inserts a new entity with a unique ID. The actual SQL write is deferred
     * by the debounce pipeline, so this measures in-memory add + enqueue latency, not SQL I/O.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    fun addEntity(bh: Blackhole) {
        val id = nextId.getAndIncrement()
        bh.consume(repo.add(BenchmarkEntity(id, "entity-$id")))
    }

    /**
     * Measures findById latency for [SqlRepository] (in-memory lookup, no SQL I/O).
     *
     * [SqlRepository] caches all rows in memory on init, so `findById` is an O(1) [ConcurrentHashMap]
     * lookup. This matches the optimistic-read design of the repository.
     * SampleTime produces p50, p95, and p99 latency percentiles in the JMH JSON output.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun findById(bh: Blackhole) {
        bh.consume(repo.findById(entityCount / 2))
    }

    /**
     * Measures direct SQL WHERE lookup latency using Exposed `transaction {}`.
     *
     * This benchmarks the cost of a raw SQL `SELECT ... WHERE name = ?` query, bypassing the
     * in-memory cache to reflect the actual SQL-level column lookup cost. The target value
     * is the name of the middle entity so the query always matches exactly one row.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun findByLabel(bh: Blackhole) {
        val targetLabel = "entity-${entityCount / 2}"

        @Suppress("UNCHECKED_CAST")
        val labelCol = exposedTable.columns.first { it.name == "label" } as Column<String>
        val results =
            transaction(db) {
                exposedTable
                    .selectAll()
                    .where { labelCol eq targetLabel }
                    .map { BenchmarkEntityTableDef.fromRow(it, exposedTable) }
            }
        bh.consume(results)
    }

    /**
     * Measures full mutation-to-database round-trip latency for [SqlRepository].
     *
     * Mutates the name property of the middle entity, then forces a synchronous flush by calling
     * [SqlRepository.close]. This bypasses the debounce timer to capture the true end-to-end
     * write latency (Pitfall 3 mitigation). The repository is re-opened in [setup] for the next trial.
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
            // Force synchronous flush to measure full mutation-to-DB round-trip
            repo.close()
            bh.consume(entity)
            // Re-open for subsequent invocations within the same trial
            repo = SqlRepository(dataSource, BenchmarkEntityTableDef)
        }
    }
}
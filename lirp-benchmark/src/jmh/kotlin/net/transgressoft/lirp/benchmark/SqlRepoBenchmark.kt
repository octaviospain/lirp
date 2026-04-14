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
 * The repository re-open happens in a [Level.Invocation] setup hook ([reopenIfNeeded]) so the
 * SQL SELECT load cost is excluded from the JMH measurement window.
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

    // Cached once in setup to avoid per-invocation column resolution in findByLabel
    lateinit var labelCol: Column<String>

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

        // Cache the label column reference once to avoid per-invocation column resolution
        @Suppress("UNCHECKED_CAST")
        labelCol = exposedTable.columns.first { it.name == "label" } as Column<String>

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
     * Each invocation inserts a new entity with a unique ID and then removes it to keep the
     * repository size stable. The actual SQL write is deferred by the debounce pipeline,
     * so this measures in-memory add + enqueue latency, not SQL I/O.
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    fun addEntity(bh: Blackhole) {
        val entity = BenchmarkEntity(nextId.getAndIncrement(), "entity")
        val result = repo.add(entity)
        repo.remove(entity)
        bh.consume(result)
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
     * This benchmarks the cost of a raw SQL `SELECT ... WHERE label = ?` query, bypassing the
     * in-memory cache to reflect the actual SQL-level column lookup cost. The target value
     * is the label of the middle entity so the query always matches exactly one row.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun findByLabel(bh: Blackhole) {
        val targetLabel = "entity-${entityCount / 2}"
        val results =
            transaction(db) {
                exposedTable
                    .selectAll()
                    .where { labelCol eq targetLabel }
                    .map { BenchmarkEntityTableDef.fromRow(it, exposedTable) }
            }
        bh.consume(results)
    }

    @Volatile
    private var needsReopen = false

    /**
     * Re-opens the repository after [mutationFlush] closed it.
     *
     * Runs at [Level.Invocation] so the re-open cost (full SQL SELECT load) is excluded from the
     * JMH sample timer. Only triggers when [needsReopen] is set by [mutationFlush].
     *
     * Note: [Level.Invocation] setup/teardown is acceptable here because `mutationFlush` operates
     * at microsecond granularity (100s of µs), well above the ~1 µs overhead of the invocation hook.
     */
    @Setup(Level.Invocation)
    fun reopenIfNeeded() {
        if (needsReopen) {
            repo = SqlRepository(dataSource, BenchmarkEntityTableDef)
            needsReopen = false
        }
    }

    /**
     * Measures mutation-to-database flush latency for [SqlRepository].
     *
     * Mutates the name property of the middle entity, then forces a synchronous flush by calling
     * [SqlRepository.close]. `close()` is a deterministic flush barrier: it cancels debounce jobs,
     * acquires the `flushLock` (blocking if a debounce flush is in-flight), then calls `flush()`
     * which drains and writes all pending ops synchronously under the lock.
     *
     * The repository re-open happens in [reopenIfNeeded] at [Level.Invocation], so the SQL SELECT
     * load time on re-open is excluded from the JMH measurement window.
     *
     * **Async gap caveat:** The mutation via `entity.name = ...` triggers `mutateAndPublish()` →
     * `SharedFlow` → subscriber → `enqueue(PendingUpdate)`. In production, the subscriber
     * dispatch is effectively immediate (same coroutine context), so by the time `close()` runs
     * the `PendingUpdate` is in the queue. If this assumption breaks under contention, the
     * measured flush time will be artificially low (close drains an empty queue).
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    fun mutationFlush(bh: Blackhole) {
        val entity = repo.findById(entityCount / 2).orElse(null)
        if (entity != null) {
            entity.name = "mutated-${System.nanoTime()}"
            repo.close()
            bh.consume(entity)
        }
        needsReopen = true
    }
}
package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.kafka.KafkaOutboxSqlRepository
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * JMH microbenchmark isolating the write-latency delta between outbox-enabled and outbox-disabled
 * [SqlRepository] writes.
 *
 * Both modes flush a single mutated entity to an H2 in-memory database backed by the same HikariCP
 * pool configuration. The `outbox_on` mode uses [KafkaOutboxSqlRepository], which co-inserts one
 * row into the `lirp_kafka_outbox` table inside the same JDBC commit as the entity flush. The
 * `outbox_off` mode uses a plain [SqlRepository] as the zero-overhead baseline.
 *
 * The delta between the two `outboxMode` parameter rows in the results is the per-flush cost a
 * consumer pays to enable the transactional outbox — specifically the synchronous in-commit
 * `batchInsert` into `lirp_kafka_outbox` that occurs in [KafkaOutboxSqlRepository.onAfterEntityWritesInWritePending].
 * No relay process or Kafka broker is started during measurement.
 *
 * **Database isolation:** Each trial creates a fresh H2 in-memory database with a unique URL to
 * prevent row accumulation between `(outboxMode, entityCount)` parameter combinations.
 *
 * **Measurement pattern:** [mutationFlush] forces a synchronous flush via [SqlRepository.close]
 * to measure the full mutation-to-database round-trip. The repository re-open happens in
 * [reopenIfNeeded] at [Level.Invocation] so the SQL SELECT load cost is excluded from the
 * JMH measurement window.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class OutboxCaptureBenchmark {

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    @Param("outbox_on", "outbox_off")
    var outboxMode: String = "outbox_off"

    lateinit var dataSource: HikariDataSource
    lateinit var repo: SqlRepository<Int, BenchmarkEntity>

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

        repo =
            if (outboxMode == "outbox_on") {
                KafkaOutboxSqlRepository(dataSource, BenchmarkEntityTableDef)
            } else {
                SqlRepository(dataSource, BenchmarkEntityTableDef)
            }
        repeat(entityCount) { i -> repo.add(BenchmarkEntity(i, "entity-${i % 100}", age = (i % 100) + 1)) }
        // Force initial flush so setup rows are in the database before measurements begin
        repo.close()

        // Re-open for the benchmark
        repo =
            if (outboxMode == "outbox_on") {
                KafkaOutboxSqlRepository(dataSource, BenchmarkEntityTableDef)
            } else {
                SqlRepository(dataSource, BenchmarkEntityTableDef)
            }
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

    @Volatile
    private var needsReopen = false

    /**
     * Re-opens the repository after [mutationFlush] closed it.
     *
     * Runs at [Level.Invocation] so the re-open cost (full SQL SELECT load) is excluded from the
     * JMH sample timer. Only triggers when [needsReopen] is set by [mutationFlush].
     * Branches on [outboxMode] to re-open with the same repository type that was measured.
     */
    @Setup(Level.Invocation)
    fun reopenIfNeeded() {
        if (needsReopen) {
            repo =
                if (outboxMode == "outbox_on") {
                    KafkaOutboxSqlRepository(dataSource, BenchmarkEntityTableDef)
                } else {
                    SqlRepository(dataSource, BenchmarkEntityTableDef)
                }
            needsReopen = false
        }
    }

    /**
     * Measures mutation-to-database flush latency, isolating the outbox capture overhead.
     *
     * Mutates the name property of the middle entity, then forces a synchronous flush via
     * [SqlRepository.close]. For the `outbox_on` mode, this also inserts one row into the
     * `lirp_kafka_outbox` table inside the same JDBC commit. The repository re-open happens
     * in [reopenIfNeeded] at [Level.Invocation] so the SQL SELECT load time is excluded from
     * the measurement window.
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
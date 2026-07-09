package net.transgressoft.lirp.benchmark

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
import java.util.concurrent.atomic.AtomicLong

/**
 * Head-to-head comparison of [SqlRepository] against raw JDBC [java.sql.PreparedStatement]
 * operations using H2 in-memory database, establishing the zero-overhead baseline for SQL
 * persistence cost.
 *
 * Both sides use dedicated, separate H2 in-memory databases per trial to avoid cross-side
 * interference. The JDBC side pre-creates a schema identical to the one lirp uses and seeds
 * the same number of rows.
 *
 * **What is measured:**
 * - `add` — INSERT throughput: SqlRepository in-memory enqueue vs immediate JDBC insert + commit
 * - `findById` — SELECT by primary key latency: SqlRepository ConcurrentHashMap lookup vs JDBC
 *   `SELECT WHERE id = ?` round-trip
 * - `update` — Full round-trip write latency: SqlRepository mutation flush via `close()` vs JDBC
 *   `UPDATE ... WHERE id = ?` + commit
 *
 * **Database isolation:** Each trial creates unique H2 URLs (`jdbc:h2:mem:lirp_<UUID>` and
 * `jdbc:h2:mem:jdbc_<UUID>`) to prevent row accumulation between parameter sets.
 *
 * **JDBC connection strategy:** The JDBC side acquires a fresh connection from a dedicated pool
 * per benchmark invocation for `add` (to avoid unique-key violations from accumulated inserts)
 * and reuses a stable connection with `autoCommit=true` for `findById` and `update`.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class DirectJdbcBenchmark {

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    // lirp side
    lateinit var lirpDataSource: HikariDataSource
    lateinit var lirpRepo: SqlRepository<Int, BenchmarkEntity>

    // Raw JDBC side — separate dedicated data source with autoCommit=true for simplicity
    lateinit var jdbcDataSource: HikariDataSource

    // Monotonic counter for generating unique IDs in add benchmarks (avoids PK collisions)
    val addIdGen = AtomicLong(0L)

    @Setup(Level.Trial)
    fun setup() {
        val trialId = UUID.randomUUID()

        // lirp side: dedicated H2 database
        lirpDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:lirp_$trialId;DB_CLOSE_DELAY=-1"
                    maximumPoolSize = 8
                    isAutoCommit = false
                    transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                }
            )
        lirpRepo = SqlRepository(lirpDataSource, BenchmarkEntityTableDef)
        repeat(entityCount) { i -> lirpRepo.add(BenchmarkEntity(i, "entity-$i", i % 100 + 1)) }
        lirpRepo.close()
        lirpRepo = SqlRepository(lirpDataSource, BenchmarkEntityTableDef)

        // JDBC side: separate H2 database, autoCommit=true
        jdbcDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:jdbc_$trialId;DB_CLOSE_DELAY=-1"
                    maximumPoolSize = 8
                    isAutoCommit = true
                }
            )
        // Create schema and seed identical rows using JDBC directly
        jdbcDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """CREATE TABLE IF NOT EXISTS benchmark_entities (
                        id INT PRIMARY KEY,
                        label VARCHAR(255) NOT NULL,
                        age INT NOT NULL,
                        "name" VARCHAR(255) NOT NULL
                    )"""
                )
            }
            val ps =
                conn.prepareStatement(
                    "INSERT INTO benchmark_entities (id, label, age, \"name\") VALUES (?, ?, ?, ?)"
                )
            ps.use {
                repeat(entityCount) { i ->
                    it.setInt(1, i)
                    it.setString(2, "entity-$i")
                    it.setInt(3, i % 100 + 1)
                    it.setString(4, "entity-$i")
                    it.addBatch()
                }
                it.executeBatch()
            }
        }

        addIdGen.set(entityCount.toLong())
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        try {
            lirpRepo.close()
        } catch (_: Exception) {
        }
        lirpDataSource.close()
        jdbcDataSource.close()
    }

    /**
     * Rewinds both sides to their seeded baseline between measurement iterations.
     *
     * The two add benchmarks insert a new row on every invocation and never remove it, so in
     * [Mode.Throughput] the lirp in-memory store (plus its debounce queue) and the raw JDBC table
     * grow without bound across a full iteration and eventually exhaust the heap, dropping the
     * benchmarks from the results. The guard makes this a no-op for the findById/update
     * benchmarks, which never advance [addIdGen] past the seed.
     */
    @Setup(Level.Iteration)
    fun rewindToBaseline() {
        if (addIdGen.get() <= entityCount.toLong()) return
        lirpRepo.close()
        deleteRowsAtOrAboveSeed(lirpDataSource)
        lirpRepo = SqlRepository(lirpDataSource, BenchmarkEntityTableDef)
        deleteRowsAtOrAboveSeed(jdbcDataSource)
        addIdGen.set(entityCount.toLong())
    }

    /** Deletes rows whose id is at or above the seeded [entityCount], committing when the pool is not auto-commit. */
    private fun deleteRowsAtOrAboveSeed(source: HikariDataSource) {
        source.connection.use { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM benchmark_entities WHERE id >= $entityCount") }
            if (!conn.autoCommit) conn.commit()
        }
    }

    /**
     * Measures add throughput via [SqlRepository].
     *
     * Enqueues the entity in the in-memory store; the SQL write is deferred by the debounce
     * pipeline. Paired with [directJdbcAdd].
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    fun lirpAdd(bh: Blackhole) {
        val id = addIdGen.getAndIncrement().toInt()
        bh.consume(lirpRepo.add(BenchmarkEntity(id, "entity-$id", id % 100 + 1)))
    }

    /**
     * Measures add throughput via raw JDBC with a single autoCommit=true INSERT.
     *
     * Each call acquires a connection from the pool, executes one INSERT, and releases
     * the connection. This is the zero-overhead baseline for a committed insert.
     * Paired with [lirpAdd].
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    fun directJdbcAdd(bh: Blackhole) {
        val id = addIdGen.getAndIncrement().toInt()
        jdbcDataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO benchmark_entities (id, label, age, \"name\") VALUES (?, ?, ?, ?)"
            ).use { ps ->
                ps.setInt(1, id)
                ps.setString(2, "entity-$id")
                ps.setInt(3, id % 100 + 1)
                ps.setString(4, "entity-$id")
                bh.consume(ps.executeUpdate())
            }
        }
    }

    /**
     * Measures findById latency via [SqlRepository] (in-memory ConcurrentHashMap lookup).
     *
     * Paired with [directJdbcFindById].
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun lirpFindById(bh: Blackhole) {
        bh.consume(lirpRepo.findById(entityCount / 2))
    }

    /**
     * Measures findById latency via raw JDBC `SELECT ... WHERE id = ?`.
     *
     * Acquires a connection per call to measure the full pool-checkout + query + result-read
     * cost, which is the actual per-operation JDBC overhead. Paired with [lirpFindById].
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun directJdbcFindById(bh: Blackhole) {
        jdbcDataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT id, label, age, \"name\" FROM benchmark_entities WHERE id = ?"
            ).use { ps ->
                ps.setInt(1, entityCount / 2)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        bh.consume(rs.getInt(1))
                        bh.consume(rs.getString(2))
                        bh.consume(rs.getInt(3))
                        bh.consume(rs.getString(4))
                    }
                }
            }
        }
    }

    /**
     * Measures full mutation-to-database round-trip via [SqlRepository].
     *
     * Mutates one entity property and forces a synchronous flush via [SqlRepository.close].
     * The repository is re-opened within this method for the next invocation.
     * Paired with [directJdbcUpdate].
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    fun lirpUpdate(bh: Blackhole) {
        val entity = lirpRepo.findById(entityCount / 2).orElse(null)
        if (entity != null) {
            entity.name = "mutated-${System.nanoTime()}"
            lirpRepo.close()
            bh.consume(entity)
            lirpRepo = SqlRepository(lirpDataSource, BenchmarkEntityTableDef)
        }
    }

    /**
     * Measures full UPDATE + commit round-trip via raw JDBC.
     *
     * Acquires a connection from the pool, executes `UPDATE ... WHERE id = ?`, and releases.
     * This is the zero-overhead baseline for a single-row synchronous SQL write.
     * Paired with [lirpUpdate].
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    fun directJdbcUpdate(bh: Blackhole) {
        jdbcDataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE benchmark_entities SET \"name\" = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, "mutated-${System.nanoTime()}")
                ps.setInt(2, entityCount / 2)
                bh.consume(ps.executeUpdate())
            }
        }
    }
}
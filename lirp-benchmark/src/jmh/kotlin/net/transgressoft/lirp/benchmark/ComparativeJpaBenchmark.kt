package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.sql.SqlRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.Id
import jakarta.persistence.Persistence
import jakarta.persistence.Table
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Compares [SqlRepository] against plain Hibernate [EntityManager] for equivalent CRUD operations,
 * both backed by an H2 in-memory database. Demonstrates the overhead or advantage of SqlRepository's
 * in-memory-first, debounced-write design relative to direct JPA persistence semantics.
 *
 * Uses plain Hibernate [EntityManagerFactory] via `persistence.xml` rather than the full Spring
 * Data JPA wrapper, following the research fallback (assumption A1): same comparison value,
 * simpler setup without requiring a Spring application context inside the JMH harness.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
open class ComparativeJpaBenchmark {

    @Param("100", "1000", "10000", "50000")
    var entityCount: Int = 0

    lateinit var sqlRepo: SqlRepository<Int, BenchmarkEntity>
    lateinit var repoDataSource: HikariDataSource

    lateinit var emf: EntityManagerFactory
    lateinit var em: EntityManager

    val trialCounter = AtomicInteger(0)
    val addIdGen = AtomicLong(0L)

    @Setup(Level.Trial)
    fun setup() {
        val trial = trialCounter.incrementAndGet()

        // SqlRepository side: dedicated H2 in-memory database
        val repoDbUrl = "jdbc:h2:mem:bench_jpa_repo_$trial;DB_CLOSE_DELAY=-1"
        repoDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = repoDbUrl
                    maximumPoolSize = 5
                }
            )
        sqlRepo = SqlRepository(repoDataSource, BenchmarkEntityTableDef)
        repeat(entityCount) { i -> sqlRepo.add(BenchmarkEntity(i, "entity-$i")) }

        // JPA side: Hibernate EntityManagerFactory with overridden JDBC URL for unique trial isolation
        val jpaDbUrl = "jdbc:h2:mem:bench_jpa_$trial;DB_CLOSE_DELAY=-1"
        emf =
            Persistence.createEntityManagerFactory(
                "benchmark-pu",
                mapOf("jakarta.persistence.jdbc.url" to jpaDbUrl)
            )
        em = emf.createEntityManager()
        em.transaction.begin()
        repeat(entityCount) { i ->
            em.persist(
                JpaBenchmarkEntity().apply {
                    id = i
                    label = "entity-$i"
                    name = "entity-$i"
                }
            )
        }
        em.transaction.commit()
        addIdGen.set(entityCount.toLong())
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        sqlRepo.close()
        repoDataSource.close()
        em.close()
        emf.close()
    }

    /** Adds a new entity via [SqlRepository]. Paired with [jpaAdd]. */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    fun sqlRepoAdd(bh: Blackhole) {
        val id = addIdGen.getAndIncrement().toInt()
        bh.consume(sqlRepo.add(BenchmarkEntity(id, "new-$id")))
    }

    /** Persists a new entity via JPA [EntityManager]. Paired with [sqlRepoAdd]. */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    fun jpaAdd(bh: Blackhole) {
        val id = addIdGen.getAndIncrement().toInt()
        em.transaction.begin()
        val entity =
            JpaBenchmarkEntity().apply {
                this.id = id
                label = "new-$id"
                name = "new-$id"
            }
        em.persist(entity)
        em.transaction.commit()
        bh.consume(entity)
    }

    /** Finds an entity by ID via [SqlRepository]. Paired with [jpaFindById]. */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun sqlRepoFindById(bh: Blackhole) {
        bh.consume(sqlRepo.findById(entityCount / 2))
    }

    /**
     * Finds an entity by ID via JPA [EntityManager.find]. Paired with [sqlRepoFindById].
     *
     * [em.clear] is called before each lookup to evict the L1 persistence context cache,
     * ensuring the measurement reflects a true database round-trip rather than an in-memory
     * cache hit. This makes the comparison with SqlRepository's in-memory lookup symmetrical
     * in intent: both are measured against their respective optimised read paths.
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun jpaFindById(bh: Blackhole) {
        em.clear()
        bh.consume(em.find(JpaBenchmarkEntity::class.java, entityCount / 2))
    }
}

/**
 * JPA entity for the Hibernate comparison path in [ComparativeJpaBenchmark].
 *
 * Declared `open` because Hibernate generates CGLIB proxies for JPA entities.
 * Uses Jakarta EE 10 annotations (jakarta.persistence.*).
 */
@Entity
@Table(name = "jpa_benchmark_entities")
open class JpaBenchmarkEntity {
    @Id
    var id: Int = 0
    var label: String = ""
    var name: String = ""
}
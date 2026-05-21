# Performance Benchmarks

**Date:** {{ TODAY }}
**Configuration:** 2 warmup iterations, 3 measurement iterations, 1 fork (per JMH recommendation for initial profiling runs)

---

## Environment

| Attribute         | Value                                                       |
|-------------------|-------------------------------------------------------------|
| JVM               | {{ JVM_VERSION }}                                           |
| OS                | {{ OS_VERSION }}                                            |
| CPU               | {{ CPU_MODEL }}                                             |
| RAM               | {{ RAM_GB }} GB                                             |
| Database          | H2 (in-memory, per-trial isolated)                          |
| JMH               | 1.37                                                        |
| JVM args          | `--add-opens` for JOL reflection access                     |

---

## Section 1 — Repository Microbenchmarks

### 1.1 VolatileRepository — add() Throughput

`VolatileRepository` uses `ConcurrentHashMap` for in-memory storage. Add throughput is effectively constant across entity counts, showing that the hash map overhead does not grow with collection size.

| Entity Count | add() ops/s |
|-------------|-------------|
| 100         | {{ score | VolatileRepoBenchmark | addEntity | 100 }}     |
| 1,000       | {{ score | VolatileRepoBenchmark | addEntity | 1000 }}    |
| 10,000      | {{ score | VolatileRepoBenchmark | addEntity | 10000 }}   |
| 50,000      | {{ score | VolatileRepoBenchmark | addEntity | 50000 }}   |

### 1.2 VolatileRepository — findById() Latency (ns/op)

| Entity Count | Mean  | p50  | p95  | p99  |
|-------------|-------|------|------|------|
| 100         | {{ mean | VolatileRepoBenchmark | findById | 100 | bare }} ns | {{ p50 | VolatileRepoBenchmark | findById | 100 | bare }} | {{ p95 | VolatileRepoBenchmark | findById | 100 | bare }} | {{ p99 | VolatileRepoBenchmark | findById | 100 | bare }} |
| 1,000       | {{ mean | VolatileRepoBenchmark | findById | 1000 | bare }} ns | {{ p50 | VolatileRepoBenchmark | findById | 1000 | bare }} | {{ p95 | VolatileRepoBenchmark | findById | 1000 | bare }} | {{ p99 | VolatileRepoBenchmark | findById | 1000 | bare }} |
| 10,000      | {{ mean | VolatileRepoBenchmark | findById | 10000 | bare }} ns | {{ p50 | VolatileRepoBenchmark | findById | 10000 | bare }} | {{ p95 | VolatileRepoBenchmark | findById | 10000 | bare }} | {{ p99 | VolatileRepoBenchmark | findById | 10000 | bare }} |
| 50,000      | {{ mean | VolatileRepoBenchmark | findById | 50000 | bare }} ns | {{ p50 | VolatileRepoBenchmark | findById | 50000 | bare }} | {{ p95 | VolatileRepoBenchmark | findById | 50000 | bare }} | {{ p99 | VolatileRepoBenchmark | findById | 50000 | bare }} |

`findById` uses `ConcurrentHashMap.get` — O(1) with no performance degradation at higher entity counts.

### 1.3 SqlRepository — add() Throughput

`SqlRepository` stores entities in-memory immediately on `add()`. SQL writes are batched via the debounce pipeline (100 ms window). The add() throughput reflects in-memory enqueue time.

| Entity Count | add() ops/s |
|-------------|-------------|
| 100         | {{ score | SqlRepoBenchmark | addEntity | 100 }}   |
| 1,000       | {{ score | SqlRepoBenchmark | addEntity | 1000 }}  |
| 10,000      | {{ score | SqlRepoBenchmark | addEntity | 10000 }} |
| 50,000      | {{ score | SqlRepoBenchmark | addEntity | 50000 }} |

### 1.4 SqlRepository — findById() Latency (ns/op)

| Entity Count | Mean  | p50  | p95  | p99  |
|-------------|-------|------|------|------|
| 100         | {{ mean | SqlRepoBenchmark | findById | 100 | bare }} ns | {{ p50 | SqlRepoBenchmark | findById | 100 | bare }} | {{ p95 | SqlRepoBenchmark | findById | 100 | bare }} | {{ p99 | SqlRepoBenchmark | findById | 100 | bare }} |
| 1,000       | {{ mean | SqlRepoBenchmark | findById | 1000 | bare }} ns | {{ p50 | SqlRepoBenchmark | findById | 1000 | bare }} | {{ p95 | SqlRepoBenchmark | findById | 1000 | bare }} | {{ p99 | SqlRepoBenchmark | findById | 1000 | bare }} |
| 10,000      | {{ mean | SqlRepoBenchmark | findById | 10000 | bare }} ns | {{ p50 | SqlRepoBenchmark | findById | 10000 | bare }} | {{ p95 | SqlRepoBenchmark | findById | 10000 | bare }} | {{ p99 | SqlRepoBenchmark | findById | 10000 | bare }} |
| 50,000      | {{ mean | SqlRepoBenchmark | findById | 50000 | bare }} ns | {{ p50 | SqlRepoBenchmark | findById | 50000 | bare }} | {{ p95 | SqlRepoBenchmark | findById | 50000 | bare }} | {{ p99 | SqlRepoBenchmark | findById | 50000 | bare }} |

### 1.5 SqlRepository — findByLabel() Latency (SQL WHERE lookup, ns/op)

This measures a direct SQL WHERE clause column lookup, which involves a full table scan in H2 without a secondary index. Note: lirp's `@Indexed` secondary indexes are O(1) in-memory lookups; this SQL path is used only in the benchmark because KSP-generated index accessors are unavailable in the benchmark module.

| Entity Count | Mean        | p50         | p95         | p99         |
|-------------|-------------|-------------|-------------|-------------|
| 100         | {{ mean | SqlRepoBenchmark | findByLabel | 100 }} ns | {{ p50 | SqlRepoBenchmark | findByLabel | 100 }} | {{ p95 | SqlRepoBenchmark | findByLabel | 100 }} | {{ p99 | SqlRepoBenchmark | findByLabel | 100 }} |
| 1,000       | {{ mean | SqlRepoBenchmark | findByLabel | 1000 }} ns | {{ p50 | SqlRepoBenchmark | findByLabel | 1000 }} | {{ p95 | SqlRepoBenchmark | findByLabel | 1000 }} | {{ p99 | SqlRepoBenchmark | findByLabel | 1000 }} |
| 10,000      | {{ mean | SqlRepoBenchmark | findByLabel | 10000 }} ns | {{ p50 | SqlRepoBenchmark | findByLabel | 10000 }} | {{ p95 | SqlRepoBenchmark | findByLabel | 10000 }} | {{ p99 | SqlRepoBenchmark | findByLabel | 10000 }} |
| 50,000      | {{ mean | SqlRepoBenchmark | findByLabel | 50000 }} ns | {{ p50 | SqlRepoBenchmark | findByLabel | 50000 }} | {{ p95 | SqlRepoBenchmark | findByLabel | 50000 }} | {{ p99 | SqlRepoBenchmark | findByLabel | 50000 }} |

### 1.6 SqlRepository — mutationFlush Latency (µs/op)

`mutationFlush` measures: mutate one entity property + call `close()` (which triggers a synchronous batch flush to H2). This includes the full SQL write cost for all entities in the repository.

| Entity Count | Mean          | p50           | p95           | p99           |
|-------------|---------------|---------------|---------------|---------------|
| 100         | {{ mean | SqlRepoBenchmark | mutationFlush | 100 }} µs | {{ p50 | SqlRepoBenchmark | mutationFlush | 100 }} | {{ p95 | SqlRepoBenchmark | mutationFlush | 100 }} | {{ p99 | SqlRepoBenchmark | mutationFlush | 100 }} |
| 1,000       | {{ mean | SqlRepoBenchmark | mutationFlush | 1000 }} µs | {{ p50 | SqlRepoBenchmark | mutationFlush | 1000 }} | {{ p95 | SqlRepoBenchmark | mutationFlush | 1000 }} | {{ p99 | SqlRepoBenchmark | mutationFlush | 1000 }} |
| 10,000      | {{ mean | SqlRepoBenchmark | mutationFlush | 10000 }} µs | {{ p50 | SqlRepoBenchmark | mutationFlush | 10000 }} | {{ p95 | SqlRepoBenchmark | mutationFlush | 10000 }} | {{ p99 | SqlRepoBenchmark | mutationFlush | 10000 }} |
| 50,000      | {{ mean | SqlRepoBenchmark | mutationFlush | 50000 }} µs | {{ p50 | SqlRepoBenchmark | mutationFlush | 50000 }} | {{ p95 | SqlRepoBenchmark | mutationFlush | 50000 }} | {{ p99 | SqlRepoBenchmark | mutationFlush | 50000 }} |

### 1.7 JsonFileRepository — add() Throughput

| Entity Count | add() ops/s |
|-------------|-------------|
| 100         | {{ score | JsonRepoBenchmark | addEntity | 100 }}   |
| 1,000       | {{ score | JsonRepoBenchmark | addEntity | 1000 }}  |
| 10,000      | {{ score | JsonRepoBenchmark | addEntity | 10000 }} |
| 50,000      | {{ score | JsonRepoBenchmark | addEntity | 50000 }} |

### 1.8 JsonFileRepository — mutationFlush Latency (µs/op)

`mutationFlush` measures: mutate one entity property + call `close()` (synchronous JSON file write). Full serialization and file I/O cost included.

| Entity Count | Mean       | p50        | p95        | p99        |
|-------------|------------|------------|------------|------------|
| 100         | {{ mean | JsonRepoBenchmark | mutationFlush | 100 }} µs | {{ p50 | JsonRepoBenchmark | mutationFlush | 100 }} | {{ p95 | JsonRepoBenchmark | mutationFlush | 100 }} | {{ p99 | JsonRepoBenchmark | mutationFlush | 100 }} |
| 1,000       | {{ mean | JsonRepoBenchmark | mutationFlush | 1000 }} µs | {{ p50 | JsonRepoBenchmark | mutationFlush | 1000 }} | {{ p95 | JsonRepoBenchmark | mutationFlush | 1000 }} | {{ p99 | JsonRepoBenchmark | mutationFlush | 1000 }} |
| 10,000      | {{ mean | JsonRepoBenchmark | mutationFlush | 10000 }} µs | {{ p50 | JsonRepoBenchmark | mutationFlush | 10000 }} | {{ p95 | JsonRepoBenchmark | mutationFlush | 10000 }} | {{ p99 | JsonRepoBenchmark | mutationFlush | 10000 }} |
| 50,000      | {{ mean | JsonRepoBenchmark | mutationFlush | 50000 }} µs | {{ p50 | JsonRepoBenchmark | mutationFlush | 50000 }} | {{ p95 | JsonRepoBenchmark | mutationFlush | 50000 }} | {{ p99 | JsonRepoBenchmark | mutationFlush | 50000 }} |

### 1.9 CollapseBenchmark — Throughput (ops/s)

The collapse algorithm eliminates redundant `PendingOp` entries before SQL/JSON flush (`Insert+Update→Insert`, `Insert+Delete→no-op`, multiple `Update→single Update`).

| Entity Count | collapseOps ops/s |
|-------------|-------------------|
| 100         | {{ score | CollapseBenchmark | collapseOps | opCount=100 }}   |
| 1,000       | {{ score | CollapseBenchmark | collapseOps | opCount=1000 }}  |
| 10,000      | {{ score | CollapseBenchmark | collapseOps | opCount=10000 }} |
| 50,000      | {{ score | CollapseBenchmark | collapseOps | opCount=50000 }} |

### 1.10 Initialization Time (ms, SingleShotTime)

Cold-start initialization — from constructor to all entities loaded and indexed in memory.

| Entity Count | VolatileRepository | SqlRepository | JsonFileRepository |
|-------------|-------------------|---------------|-------------------|
| 100         | {{ mean | InitTimeBenchmark | initVolatile | 100 }} ms | {{ mean | InitTimeBenchmark | initSql | 100 }} ms | {{ mean | InitTimeBenchmark | initJson | 100 }} ms |
| 1,000       | {{ mean | InitTimeBenchmark | initVolatile | 1000 }} ms | {{ mean | InitTimeBenchmark | initSql | 1000 }} ms | {{ mean | InitTimeBenchmark | initJson | 1000 }} ms |
| 10,000      | {{ mean | InitTimeBenchmark | initVolatile | 10000 }} ms | {{ mean | InitTimeBenchmark | initSql | 10000 }} ms | {{ mean | InitTimeBenchmark | initJson | 10000 }} ms |
| 50,000      | {{ mean | InitTimeBenchmark | initVolatile | 50000 }} ms | {{ mean | InitTimeBenchmark | initSql | 50000 }} ms | {{ mean | InitTimeBenchmark | initJson | 50000 }} ms |

---

## Section 2 — Comparative Benchmarks

### 2.1 SqlRepository vs Direct JetBrains Exposed

Both sides use separate H2 in-memory databases. The lirp side uses the debounce pipeline; the Exposed side executes direct transactions.

**add() Throughput (ops/s):**

| Entity Count | SqlRepository | Direct Exposed |
|-------------|---------------|----------------|
| 100         | {{ score | ComparativeExposedBenchmark | sqlRepoAdd | 100 }}     | {{ score | ComparativeExposedBenchmark | directExposedAdd | 100 }}   |
| 1,000       | {{ score | ComparativeExposedBenchmark | sqlRepoAdd | 1000 }}    | {{ score | ComparativeExposedBenchmark | directExposedAdd | 1000 }}  |
| 10,000      | {{ score | ComparativeExposedBenchmark | sqlRepoAdd | 10000 }}   | {{ score | ComparativeExposedBenchmark | directExposedAdd | 10000 }} |
| 50,000      | {{ score | ComparativeExposedBenchmark | sqlRepoAdd | 50000 }}   | {{ score | ComparativeExposedBenchmark | directExposedAdd | 50000 }} |

**findById() Latency (ns/op, p50):**

| Entity Count | SqlRepository | Direct Exposed |
|-------------|---------------|----------------|
| 100         | {{ p50 | ComparativeExposedBenchmark | sqlRepoFindById | 100 | bare }} ns | {{ p50 | ComparativeExposedBenchmark | directExposedFindById | 100 }} ns |
| 1,000       | {{ p50 | ComparativeExposedBenchmark | sqlRepoFindById | 1000 | bare }} ns | {{ p50 | ComparativeExposedBenchmark | directExposedFindById | 1000 }} ns |
| 10,000      | {{ p50 | ComparativeExposedBenchmark | sqlRepoFindById | 10000 | bare }} ns | {{ p50 | ComparativeExposedBenchmark | directExposedFindById | 10000 }} ns |
| 50,000      | {{ p50 | ComparativeExposedBenchmark | sqlRepoFindById | 50000 | bare }} ns | {{ p50 | ComparativeExposedBenchmark | directExposedFindById | 50000 }} ns |

SqlRepository `findById` reads from the in-memory `ConcurrentHashMap` (O(1)). Direct Exposed `findById` executes a SQL `SELECT WHERE id = ?` per call.

### 2.2 SqlRepository vs Hibernate JPA

**add() Throughput (ops/s):**

| Entity Count | SqlRepository | JPA/Hibernate |
|-------------|---------------|---------------|
| 100         | {{ score | ComparativeJpaBenchmark | sqlRepoAdd | 100 }} | {{ score | ComparativeJpaBenchmark | jpaAdd | 100 }} |
| 1,000       | {{ score | ComparativeJpaBenchmark | sqlRepoAdd | 1000 }} | {{ score | ComparativeJpaBenchmark | jpaAdd | 1000 }} |
| 10,000      | {{ score | ComparativeJpaBenchmark | sqlRepoAdd | 10000 }} | {{ score | ComparativeJpaBenchmark | jpaAdd | 10000 }} |
| 50,000      | {{ score | ComparativeJpaBenchmark | sqlRepoAdd | 50000 }} | {{ score | ComparativeJpaBenchmark | jpaAdd | 50000 }} |

JPA `add()` commits a transaction per call via Hibernate `EntityManager.persist()` + `flush()`. SqlRepository enqueues in-memory and batches SQL writes — this is the fundamental architectural difference.

**findById() Latency (ns/op, p50):**

| Entity Count | SqlRepository | JPA/Hibernate |
|-------------|---------------|---------------|
| 100         | {{ p50 | ComparativeJpaBenchmark | sqlRepoFindById | 100 | bare }} ns | {{ p50 | ComparativeJpaBenchmark | jpaFindById | 100 }} ns |
| 1,000       | {{ p50 | ComparativeJpaBenchmark | sqlRepoFindById | 1000 | bare }} ns | {{ p50 | ComparativeJpaBenchmark | jpaFindById | 1000 }} ns |
| 10,000      | {{ p50 | ComparativeJpaBenchmark | sqlRepoFindById | 10000 | bare }} ns | {{ p50 | ComparativeJpaBenchmark | jpaFindById | 10000 }} ns |
| 50,000      | {{ p50 | ComparativeJpaBenchmark | sqlRepoFindById | 50000 | bare }} ns | {{ p50 | ComparativeJpaBenchmark | jpaFindById | 50000 }} ns |

### 2.3 JsonFileRepository vs Raw kotlinx.serialization

**add() Latency (µs/op, p50):**

| Entity Count | JsonFileRepository | Raw Serialization |
|-------------|-------------------|-------------------|
| 100         | {{ p50 | ComparativeJsonSerializationBenchmark | jsonRepoAdd | 100 }} µs | {{ p50 | ComparativeJsonSerializationBenchmark | rawSerializationWrite | 100 }} µs |
| 1,000       | {{ p50 | ComparativeJsonSerializationBenchmark | jsonRepoAdd | 1000 }} µs | {{ p50 | ComparativeJsonSerializationBenchmark | rawSerializationWrite | 1000 }} µs |
| 10,000      | {{ p50 | ComparativeJsonSerializationBenchmark | jsonRepoAdd | 10000 }} µs | {{ p50 | ComparativeJsonSerializationBenchmark | rawSerializationWrite | 10000 }} µs |
| 50,000      | {{ p50 | ComparativeJsonSerializationBenchmark | jsonRepoAdd | 50000 }} µs | {{ p50 | ComparativeJsonSerializationBenchmark | rawSerializationWrite | 50000 }} µs |

`jsonRepoAdd` enqueues the entity in-memory (no file I/O during add). Raw serialization re-encodes and rewrites the entire JSON file on every add call — this grows linearly with entity count.

**mutationFlush Latency (µs/op, p50):**

| Entity Count | JsonFileRepository | Raw Serialization |
|-------------|-------------------|-------------------|
| 100         | {{ p50 | ComparativeJsonSerializationBenchmark | jsonRepoMutationFlush | 100 }} µs | {{ p50 | ComparativeJsonSerializationBenchmark | rawSerializationMutationWrite | 100 }} µs |
| 1,000       | {{ p50 | ComparativeJsonSerializationBenchmark | jsonRepoMutationFlush | 1000 }} µs | {{ p50 | ComparativeJsonSerializationBenchmark | rawSerializationMutationWrite | 1000 }} µs |
| 10,000      | {{ p50 | ComparativeJsonSerializationBenchmark | jsonRepoMutationFlush | 10000 }} µs | {{ p50 | ComparativeJsonSerializationBenchmark | rawSerializationMutationWrite | 10000 }} µs |
| 50,000      | {{ p50 | ComparativeJsonSerializationBenchmark | jsonRepoMutationFlush | 50000 }} µs | {{ p50 | ComparativeJsonSerializationBenchmark | rawSerializationMutationWrite | 50000 }} µs |

`jsonRepoMutationFlush` mutates one entity's property and forces a synchronous flush via `close()`. Raw serialization rewrites the full file per call. At equal repository size, lirp's debounce pipeline still amortizes I/O across many mutations in normal usage — this benchmark intentionally bypasses that to measure the close-time round-trip cost.

---

## Section 3 — Memory Profiling

### 3.1 Peak Memory During Initialization (ms, SingleShotTime)

These measurements use `Runtime.getRuntime().totalMemory() - freeMemory()` delta between before and after loading all entities. The metric is the JMH iteration time (ms) which includes GC pressure; actual heap bytes allocated depend on subscriber count and entity size.

| Entity Count | 0 Subscribers | 1 Subscriber | 5 Subscribers | 10 Subscribers |
|-------------|--------------|--------------|---------------|----------------|
| 100         | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=100,subscriberCount=0 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=100,subscriberCount=1 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=100,subscriberCount=5 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=100,subscriberCount=10 }} ms |
| 1,000       | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=1000,subscriberCount=0 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=1000,subscriberCount=1 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=1000,subscriberCount=5 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=1000,subscriberCount=10 }} ms |
| 10,000      | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=10000,subscriberCount=0 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=10000,subscriberCount=1 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=10000,subscriberCount=5 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=10000,subscriberCount=10 }} ms |
| 50,000      | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=50000,subscriberCount=0 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=50000,subscriberCount=1 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=50000,subscriberCount=5 }} ms | {{ mean | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=50000,subscriberCount=10 }} ms |

### 3.2 Heap Per Entity (JOL)

`MemoryProfilingBenchmark.heapPerEntityWithSubscribers` uses JOL `GraphLayout.parseInstance` to measure total reachable heap size of a single `BenchmarkEntity` with varying subscriber counts. The reported metric is JMH iteration time (ms); the actual heap bytes per entity are printed to stdout during the benchmark run and vary by subscriber count due to `FlowEventPublisher` and `SharedFlow` allocation.

Key observations from the benchmark design:
- With 0 subscribers, the entity publisher is never initialized (lazy init) — only the entity object itself and its backing field values are allocated
- With 1+ subscribers, `FlowEventPublisher` and its `SharedFlow` buffer are allocated on first subscription
- Additional subscribers add a `Job` entry per subscription but share the same `SharedFlow`

### 3.3 Dataset Size Recommendations

Based on the measured initialization times and throughput characteristics:

| Repository Type    | Practical Sweet Spot | Upper Bound   | Notes |
|-------------------|---------------------|---------------|-------|
| VolatileRepository | Up to 50K entities  | ~500K entities | Pure in-memory; limited by heap only |
| SqlRepository      | Up to 10K entities  | ~100K entities | Init time grows with row count and table scan |
| JsonFileRepository | Up to 10K entities  | ~50K entities  | Full-file rewrite on each flush |

For applications exceeding these bounds, consider SQL-level pagination or streaming — lirp is designed for bounded-context working sets that fit comfortably in heap.

---

## Section 4 — lirp SqlRepository vs Direct JDBC (Zero-Overhead Baseline)

**Date:** {{ TODAY }}
**Configuration:** 2 warmup iterations, 3 measurement iterations, 1 fork

This is the most direct comparison in the benchmark suite: lirp `SqlRepository` measured against raw `java.sql.PreparedStatement` with no ORM or framework overhead. Both sides use the same H2 in-memory engine with separate databases per trial.

The key insight is that lirp's architecture inverts the naive trade-off on the read path: `findById()` is faster than raw JDBC because it serves from an in-memory `ConcurrentHashMap`. On the write path, raw JDBC `add()` is faster per call (a single autocommit INSERT is tiny), but lirp amortizes I/O across the full batch flush at `close()` — see Section 4.3.

---

### 4.1 add() Throughput — lirp vs Direct JDBC (ops/s)

Direct JDBC `add()` executes one `INSERT` + autoCommit per call. lirp `add()` enqueues in memory; the SQL write is deferred and batched.

| Entity Count | lirp SqlRepository | Direct JDBC (baseline) |
|-------------|-------------------|------------------------|
| 100         | {{ score | DirectJdbcBenchmark | lirpAdd | 100 }} | {{ score | DirectJdbcBenchmark | directJdbcAdd | 100 }} |
| 1,000       | {{ score | DirectJdbcBenchmark | lirpAdd | 1000 }} | {{ score | DirectJdbcBenchmark | directJdbcAdd | 1000 }} |
| 10,000      | {{ score | DirectJdbcBenchmark | lirpAdd | 10000 }} | {{ score | DirectJdbcBenchmark | directJdbcAdd | 10000 }} |
| 50,000      | {{ score | DirectJdbcBenchmark | lirpAdd | 50000 }} | {{ score | DirectJdbcBenchmark | directJdbcAdd | 50000 }} |

**Interpretation:** Direct JDBC `add()` is faster here because each call is a single row insert with immediate autoCommit — tiny and constant. lirp `add()` has additional overhead from event publication, the debounce pipeline enqueue, and the in-memory map put. However, the lirp cost is also constant regardless of entity count — and critically, the SQL I/O cost is amortized across all inserts in a single batch transaction (see Section 4.3).

---

### 4.2 findById() Latency — lirp vs Direct JDBC (ns/op, p50)

Direct JDBC `findById()` executes `SELECT ... WHERE id = ?` and reads the result set. lirp reads from a `ConcurrentHashMap`.

| Entity Count | lirp SqlRepository (p50) | Direct JDBC (p50) |
|-------------|--------------------------|-------------------|
| 100         | {{ p50 | DirectJdbcBenchmark | lirpFindById | 100 | bare }} ns | {{ p50 | DirectJdbcBenchmark | directJdbcFindById | 100 }} ns |
| 1,000       | {{ p50 | DirectJdbcBenchmark | lirpFindById | 1000 | bare }} ns | {{ p50 | DirectJdbcBenchmark | directJdbcFindById | 1000 }} ns |
| 10,000      | {{ p50 | DirectJdbcBenchmark | lirpFindById | 10000 | bare }} ns | {{ p50 | DirectJdbcBenchmark | directJdbcFindById | 10000 }} ns |
| 50,000      | {{ p50 | DirectJdbcBenchmark | lirpFindById | 50000 | bare }} ns | {{ p50 | DirectJdbcBenchmark | directJdbcFindById | 50000 }} ns |

**Interpretation:** lirp's in-memory cache delivers O(1) hash-map lookups. Direct JDBC requires a connection checkout, statement preparation, SQL execution, network round-trip to H2, and result-set deserialization — all within the same process but still significantly slower due to the SQL engine overhead. In production against a remote database, the JDBC gap widens further.

---

### 4.3 Full Flush/Update Latency — lirp vs Direct JDBC (µs/op, p50)

lirp `update` mutates one entity then calls `close()`, which flushes ALL entities in the repository as a batch transaction. Direct JDBC `update` executes a single `UPDATE WHERE id = ?` + autoCommit.

| Entity Count | lirp flush (p50)  | Direct JDBC single UPDATE (p50) |
|-------------|-------------------|---------------------------------|
| 100         | {{ p50 | DirectJdbcBenchmark | lirpUpdate | 100 }} µs | {{ p50 | DirectJdbcBenchmark | directJdbcUpdate | 100 | bare }} µs |
| 1,000       | {{ p50 | DirectJdbcBenchmark | lirpUpdate | 1000 }} µs | {{ p50 | DirectJdbcBenchmark | directJdbcUpdate | 1000 | bare }} µs |
| 10,000      | {{ p50 | DirectJdbcBenchmark | lirpUpdate | 10000 }} µs | {{ p50 | DirectJdbcBenchmark | directJdbcUpdate | 10000 | bare }} µs |
| 50,000      | {{ p50 | DirectJdbcBenchmark | lirpUpdate | 50000 }} µs | {{ p50 | DirectJdbcBenchmark | directJdbcUpdate | 50000 | bare }} µs |

**Interpretation:** This comparison is asymmetric by design: the lirp `close()` flush writes the **entire** repository in one transaction, while the JDBC baseline writes **one** row. The lirp batch's per-entity cost approaches JDBC's per-row cost as batch size increases, because transaction overhead is amortized across all rows.

---

## Section 5 — How to Run

Run all benchmarks (full production configuration: 5 warmup, 10 measurement, 1 fork):

```bash
gradle :lirp-benchmark:jmh
```

Run a specific benchmark class:

```bash
gradle :lirp-benchmark:jmh -Pjmh.includes='VolatileRepoBenchmark'
```

Run with reduced iterations for quick profiling (forces a re-run if results were cached):

```bash
gradle :lirp-benchmark:jmh -Pjmh.warmupIterations=2 -Pjmh.iterations=3 -Pjmh.fork=1 --rerun-tasks
```

After the JMH run, the `renderBenchmarkReport` task (wired via `finalizedBy`) regenerates:

- `lirp-benchmark/build/reports/jmh/csv/*.csv` — one CSV per benchmark class
- `lirp-benchmark/build/reports/jmh/Performance-Benchmarks.md` — this file, rendered from the template at `lirp-benchmark/scripts/Performance-Benchmarks.template.md`

To publish to the wiki, copy the rendered file:

```bash
cp lirp-benchmark/build/reports/jmh/Performance-Benchmarks.md ../lirp.wiki/Performance-Benchmarks.md
```

> The benchmark module is **never published to Maven Central** and is **excluded from CI**. It is a local development tool only.

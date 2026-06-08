[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/octaviospain/lirp)
![Maven Central Version](https://img.shields.io/maven-central/v/net.transgressoft/lirp-api)
![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/octaviospain/lirp/.github%2Fworkflows%2Fmaster.yml?logo=github)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=bugs)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=coverage)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=octaviospain_lirp&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=octaviospain_lirp)

# LIRP - Lightweight Reactive Persistence

A Kotlin/Java library where domain entities own their reactivity — property changes automatically notify subscribers, and repositories persist transparently.

## What is LIRP?

LIRP solves a specific problem: **most reactive libraries make you wire streams manually, and most persistence libraries treat entities as passive data**. LIRP does both — your entities are reactive objects that automatically notify subscribers on property changes, and repositories keep your data in sync with a database or file.

**The core idea:** domain entities should carry both behavior and reactivity. When you declare `var price by reactiveProperty(initial)`, the property becomes observable. When you add an entity to a repository, persistence happens automatically.

## Why LIRP?

| Approach | Entities are reactive? | Auto-persist changes? | Wiring needed? |
|---|---|---|---|
| **LIRP** | ✅ Built-in via `reactiveProperty()` | ✅ Transparent (SQL/JSON) | None |
| RxJava / Kotlin Flow | ❌ You wire streams yourself | ❌ Not a concern | Extensive |
| Event Sourcing (fmodel, occurrent) | ❌ Events stored & replayed | ✅ But event-focused | Moderate |
| Hibernate Reactive | ❌ ORM-managed | ✅ But requires sessions | Moderate |
| Event Bus (Guava, Event-Library) | ❌ Separate infrastructure | ❌ Not a concern | Manual |

LIRP's sweet spot: **small-to-medium datasets where entities need both reactivity and persistence with zero boilerplate** — configuration stores, user preferences, catalog management, any bounded context where the working set fits in memory.

Built on Kotlin Coroutines and Kotlin Serialization. Targets **JVM 21 toolchain, JVM 17+ runtime, Kotlin 2.3.10**.

## Quick Start

### Installation

LIRP is published to Maven Central under the `net.transgressoft` group. Pull `lirp-core` for the
reactive entity + in-memory / JSON repository surface; add `lirp-sql` for the SQL repository and
`lirp-fx` for the JavaFX bridge. The `net.transgressoft.lirp.sql` Gradle plugin wires up the KSP
processor for you so generated accessors are produced at build time.

**Requirements:** JVM 21 toolchain (JVM 17+ runtime), Kotlin 2.3.10.

Gradle (Kotlin DSL):
```kotlin
plugins {
    id("net.transgressoft.lirp.sql") version "<lirp-version>"
}

dependencies {
    implementation("net.transgressoft:lirp-core:<lirp-version>")
    implementation("net.transgressoft:lirp-sql:<lirp-version>")   // optional — SQL persistence
    implementation("net.transgressoft:lirp-fx:<lirp-version>")    // optional — JavaFX bridge
}
```

Gradle without the LIRP plugin (manual KSP wiring):

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
    implementation("net.transgressoft:lirp-core:<lirp-version>")
    ksp("net.transgressoft:lirp-ksp:<lirp-version>")

    implementation("net.transgressoft:lirp-sql:<lirp-version>")   // optional
    implementation("net.transgressoft:lirp-fx:<lirp-version>")    // optional
}
```

Maven:

```xml
<dependencies>
    <dependency>
        <groupId>net.transgressoft</groupId>
        <artifactId>lirp-core</artifactId>
        <version>${lirp.version}</version>
    </dependency>
    <!-- optional: SQL persistence -->
    <dependency>
        <groupId>net.transgressoft</groupId>
        <artifactId>lirp-sql</artifactId>
        <version>${lirp.version}</version>
    </dependency>
    <!-- optional: JavaFX bridge -->
    <dependency>
        <groupId>net.transgressoft</groupId>
        <artifactId>lirp-fx</artifactId>
        <version>${lirp.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- KSP compiler plugin — runs lirp-ksp during the compile phase. -->
        <plugin>
            <groupId>com.dyescape</groupId>
            <artifactId>kotlin-maven-symbol-processing</artifactId>
            <version>${ksp-maven.version}</version>
            <configuration>
                <processors>
                    <processor>net.transgressoft:lirp-ksp:${lirp.version}</processor>
                </processors>
            </configuration>
        </plugin>
    </plugins>
</build>
```

See [Consuming LIRP](https://github.com/octaviospain/lirp/wiki/Consuming-LIRP) for the
compatibility matrix and troubleshooting common setup failures.

### Core example

The following shows a reactive entity with a parent aggregate reference, a repository, entity-level
and repository-level subscriptions, and a property mutation that fires events through both:

```kotlin
// @PersistenceMapping: marks the entity for KSP codegen — generates Album_LirpTableDef
// (the Exposed table) and accessors, mapping it to the "albums" table
@PersistenceMapping(name = "albums")
data class Album(
    override val id: Int,
    var title: String,
    // @Indexed: secondary index for O(1) equality lookups on genre
    @Indexed val genre: String,
    var artistId: Int,
    initialRating: Double = 0.0
) : ReactiveEntityBase<Int, Album>() {
    var rating: Double by reactiveProperty(initialRating)

    // @Aggregate: declares a cross-entity reference; onDelete sets the cascade mode and
    // bubbleUp = true propagates child mutations to the artist's subscribers too
    @Aggregate(bubbleUp = true, onDelete = CascadeAction.DETACH)
    // @Transient: the resolved reference is computed from artistId, not a stored column
    @Transient
    val artist by aggregate<Int, Artist> { artistId }

    override val uniqueId = "album-$id"
    override fun clone() = Album(id, title, genre, artistId, rating)
}

// @LirpRepository: registers the repository for context wiring and create()-factory codegen
@LirpRepository
class AlbumRepository : VolatileRepository<Int, Album>("Albums") {
    fun create(id: Int, title: String, genre: String, artistId: Int): Album =
        Album(id, title, genre, artistId).also { add(it) }
}

val repo = AlbumRepository()

// Repository-level: track all structural changes
repo.subscribe { event ->
    when (event) {
        is StandardCrudEvent.Create -> println("Added: ${event.entity.title}")
        is StandardCrudEvent.Update -> println("Updated album ${event.entityId}")
        is StandardCrudEvent.Delete -> println("Removed album ${event.entityId}")
        else -> {}
    }
}

val nevermind = repo.create(1, "Nevermind", "rock", 42)

// Entity-level: fine-grained property change tracking
nevermind.subscribe { event ->
    println("Rating changed: ${event.oldEntity.rating} -> ${event.newEntity.rating}")
}

nevermind.rating = 9.5  // fires both entity subscriber and repository Update event
```

See [Core Concepts](https://github.com/octaviospain/lirp/wiki/Core-Concepts) for the reactive-property model, subscription patterns, and entity lifecycle.

## SQL Persistence

`SqlRepository` persists entities to a relational database automatically. Reads are served from
the in-memory `ConcurrentHashMap` — `findById` never round-trips to the database. Writes are
enqueued into a debounced pipeline (default 100 ms, max 1 s) and flushed on a background
coroutine. See [SQL Persistence](https://github.com/octaviospain/lirp/wiki/SQL-Persistence) for
write-path and flush details.

```kotlin
@LirpRepository
class AlbumRepository(dataSource: DataSource) :
    SqlRepository<Int, Album>(dataSource, Album_LirpTableDef)

// Or build the pool from a JDBC URL — the repository owns the HikariDataSource lifecycle
@LirpRepository
class AlbumRepository :
    SqlRepository<Int, Album>("jdbc:postgresql://localhost:5432/mydb", Album_LirpTableDef)
```

### Credential handling

JDBC URLs frequently embed secrets. Treat them like passwords, not like configuration.

Prefer injecting credentials via a pre-configured `DataSource` rather than embedding them in the
URL string. For example, build a `HikariDataSource` from environment variables and pass it to the
`SqlRepository(dataSource, tableDef)` constructor — credentials never appear inside the URL
itself, and the calling code controls pool lifecycle:

```kotlin
val config = HikariConfig().apply {
    jdbcUrl  = System.getenv("JDBC_URL")  ?: error("JDBC_URL not set")
    // inject user/password via config rather than the URL to avoid leaking them in logs
    setUsername(System.getenv("DB_USER"))
    setPassword(System.getenv("DB_PASSWORD"))
}
val repo = AlbumRepository(HikariDataSource(config))
```

When you must include a URL in diagnostic output, route it through `ConnectionUrlSanitizer` to
mask passwords before they reach logs:

```kotlin
import net.transgressoft.lirp.persistence.sql.ConnectionUrlSanitizer

logger.debug { "Connecting to: ${ConnectionUrlSanitizer.sanitize(jdbcUrl)}" }
// jdbc:postgresql://user:secret@host:5432/db  →  jdbc:postgresql://user:****@host:5432/db
```

`sanitize` handles all five supported dialects, is case-insensitive on the `password` key, and
returns malformed input verbatim — it never throws. See the
[SQL Persistence wiki page](https://github.com/octaviospain/lirp/wiki/SQL-Persistence#credential-handling)
for the full guidance.

## Query DSL

LIRP provides a type-safe, Kotlin-native query DSL for filtering, ordering, and paginating entities directly from any `Repository`. Predicates compose with infix operators; the planner automatically routes indexed equality checks through secondary indexes and falls back to in-memory scans for range and composite predicates.

```kotlin
// Equality filter — auto-routes through @Indexed if available
val rockAlbums = repo.query { where { Album::genre eq "rock" } }.toList()

// Composite predicates with AND, OR, NOT; ordering and pagination
val topRated = repo.query {
    where { (Album::genre eq "rock") and (Album::rating gte 8.0) }
    orderBy(Album::rating, Direction.DESC)
    limit(10)
}.toList()

// Cross-aggregate via: albums whose artist is from a given country
val britishAlbums = repo.query {
    where { Album::artistId via artists where { Artist::country eq "UK" } }
}.toList()

// Collection reference: playlists that include at least one top-rated track
val featuredPlaylists = playlists.query {
    where { Playlist::trackIds via tracks anyMatch { Track::rating gt 9.0 } }
}.toList()
```

The returned `Sequence<T>` is lazy — no evaluation occurs until a terminal operation (`toList`, `firstOrNull`, `count`, etc.). See [Query DSL](https://github.com/octaviospain/lirp/wiki/Query-DSL) for the full operator reference, planner strategies, and Java interop notes.

| Persistence target | Module | Status |
|---|---|---|
| PostgreSQL, MySQL, MariaDB | `lirp-sql` | Supported |
| JSON file | `lirp-core` | Supported |
| MS SQL Server, Oracle | `lirp-sql` | Not tested |

Deep coverage of the write pipeline, collapse algorithm, transactional guarantees, `@Version` optimistic locking, aggregate references, cascade semantics, collection delegates, JSON persistence, and JavaFX integration lives on the wiki — see [Documentation](#documentation) below.

## Features

- **Transparent SQL persistence** — add an entity, change a property, the database stays in sync automatically
- **Entity-first reactivity** — `var x by reactiveProperty(init)` notifies subscribers on assignment, zero overhead when unobserved
- **Two subscription levels** — repository-level `CrudEvent`s and entity-level `MutationEvent`s
- **DDD aggregate references** — `@Aggregate` with single-entity (`aggregate`, `optionalAggregate`) and collection (`aggregateList`, `aggregateSet`, `mutableAggregateList`, `mutableAggregateSet`) delegates, configurable cascade (DETACH / CASCADE / RESTRICT / NONE) enforced both app-side and at the database layer (FK constraints on scalar refs, junction tables for collection refs)
- **JSON FK reconciliation** — `JsonFkPolicy.LOG_AND_RECONCILE` (default) silently repairs dangling refs at load; `JsonFkPolicy.STRICT` fails loudly — symmetric to SQL `ON DELETE RESTRICT`
- **Secondary indexes** — `@Indexed` for O(1) equality lookups
- **Type-safe Query DSL** — Kotlin-native filtering, ordering, and pagination with automatic index routing
- **Optimistic locking** — `@Version` triggers versioned UPDATE/DELETE; conflicts surface as `StandardCrudEvent.Conflict` with canonical state
- **Convention-over-configuration KSP codegen** — `@PersistenceMapping` generates table definitions; annotations only when you need to customize. Generated companions match the entity's own visibility, so an `internal` entity produces compiling `internal` companions; an explicitly-annotated `private`/`protected` entity fails the build with a targeted diagnostic rather than uncompilable output
- **Robust column eligibility** — `@PersistenceIgnore` and `@Transient` (both `kotlin.jvm` and `kotlinx.serialization`) exclude a property from the generated table. Property types still resolving during KSP's multi-round processing are deferred and retried rather than dropped, and a type that never resolves fails the build with a targeted diagnostic instead of a silently-missing column
- **Custom column converters** — route non-scalar domain types through a consumer-supplied `ColumnConverter<D, S>` `object` referenced via `@PersistenceProperty(converter = MyConverter::class)`. The contract lives in `lirp-api`
- **Embeddable value objects** — `@Embeddable` + `@Embedded` flattens value-object fields into prefixed columns on the parent entity's table (no join, no extra table)
- **Element collections** — `@ElementCollection` persists a `List<E>` or `Set<E>` of scalar-backed values as a single JSON-array `TEXT` column
- **JSON persistence** — debounced file writes via `JsonFileRepository`, zero-reflection `LirpEntitySerializer`
- **Repository-as-factory** — typed `create()` methods with automatic `@LirpRepository` registration
- **JavaFX integration** (`lirp-fx`) — `fxAggregateList`/`fxAggregateSet` bridging lirp collections with `ObservableList`/`ObservableSet`, scalar delegates (`fxString`, `fxInteger`, etc.), read-only `ObservableMap` projections
- **Non-FX projection maps** — `projectionMap` in `lirp-core` groups entities into a `Map<PK, List<E>>` with no JavaFX dependency
- **Full Java interoperability**

## Limitations and Design Trade-offs

LIRP's in-memory-first architecture has trade-offs that influence where it fits best:

- **Full dataset loaded into memory** — `SqlRepository` and `JsonFileRepository` load every row into a `ConcurrentHashMap` on initialization. This enables instant reads and O(1) indexed lookups but caps practical dataset size at what the JVM heap can hold (comfortable up to low thousands of entities; tens of thousands need heap tuning; hundreds of thousands are impractical).
- **Optimistic writes, eventual persistence** — in-memory state is always authoritative; a crash between enqueue and flush loses uncommitted mutations. The debounce window (default 100 ms, max 1 s) defines the data-loss window.
- **Single-node only** — no cross-process replication or distributed cache invalidation. LIRP is not a substitute for a shared database layer in a multi-instance deployment.
- **No joins or SQL aggregations** — the Query DSL operates on a single repository at a time. Cross-repository joins, GROUP BY, or window functions should be handled at the SQL level outside LIRP.

**Best suited for:** microservices or bounded contexts with small-to-medium datasets where domain reactivity and transparent persistence matter more than raw query power — configuration stores, user preference services, catalog management, any context where the working set fits comfortably in memory.

**Not suited for:** analytics workloads, high-cardinality datasets, or services requiring cross-instance consistency.

## Performance

Benchmarks run with JMH 1.37 on OpenJDK 21.0.10, 13th Gen Intel Core i7-13700, 62 GB RAM. Repository benchmarks use H2 in-memory databases with per-trial isolation. Highlights at 10,000 entities:

| Repository | `add()` throughput | `findById()` p50 |
|---|---|---|
| `VolatileRepository` | 271,877 ops/s | 27 ns |
| `SqlRepository` | 92,151 ops/s | 27 ns |
| `JsonFileRepository` | 97,720 ops/s | 27 ns |

`findById()` at 27 ns is against the in-memory `ConcurrentHashMap` — the SQL and JSON repositories skip the round-trip entirely. For operation-level persistence timing details and full benchmark methodology, see [Performance Benchmarks](https://github.com/octaviospain/lirp/wiki/Performance-Benchmarks).

## Documentation

The **[LIRP Wiki](https://github.com/octaviospain/lirp/wiki)** is the canonical reference. Start with the page that matches your question:

| Page | What's there |
|---|---|
| [Home](https://github.com/octaviospain/lirp/wiki) | Guided tour, entry points by use case |
| [Consuming LIRP](https://github.com/octaviospain/lirp/wiki/Consuming-LIRP) | External-consumer setup: Gradle plugin, Gradle manual, Maven, compatibility matrix, KSP troubleshooting |
| [Core Concepts](https://github.com/octaviospain/lirp/wiki/Core-Concepts) | Reactive entities, `reactiveProperty()`, lazy publishers, events, subscription patterns, `withEventsDisabled` |
| [Query DSL](https://github.com/octaviospain/lirp/wiki/Query-DSL) | Type-safe, Kotlin-native query DSL for filtering, ordering, and paginating entities |
| [DDD & Aggregates](https://github.com/octaviospain/lirp/wiki/DDD-and-Aggregates) | `@Aggregate`, `aggregate`, `optionalAggregate`, collection delegates, cascade, bubble-up, `CollectionChangeEvent`, app-side ↔ SQL FK mapping |
| [Persistence](https://github.com/octaviospain/lirp/wiki/Persistence) | Repository hierarchy, `PersistentRepositoryBase`, debounced write pipeline, deferred loading |
| [SQL Persistence](https://github.com/octaviospain/lirp/wiki/SQL-Persistence) | `SqlRepository`, entity annotations, type mapping, dialect support, batch SQL, foreign keys & junction tables, deferred FK installation |
| [Transactional Boundaries](https://github.com/octaviospain/lirp/wiki/Transactional-Boundaries) | Single-aggregate atomicity, `@Version` optimistic locking, `Conflict` event, saga/compensation pattern |
| [JSON Persistence](https://github.com/octaviospain/lirp/wiki/JSON-Persistence) | `JsonFileRepository`, `LirpEntitySerializer`, polymorphic serializers, deferred loading, `JsonFkPolicy` reconciliation |
| [JavaFX Integration](https://github.com/octaviospain/lirp/wiki/JavaFX-Integration) | `lirp-fx`, `fxAggregateList`/`fxAggregateSet`, scalar delegates, dual notification, FX thread dispatch |
| [Projection Maps](https://github.com/octaviospain/lirp/wiki/Projection-Maps) | `projectionMap` and `fxProjectionMap` — read-only grouped views |
| [Java Interop](https://github.com/octaviospain/lirp/wiki/Java-Interop) | Full Java examples for entities, repositories, subscriptions, collection events |
| [Architecture Overview](https://github.com/octaviospain/lirp/wiki/Architecture-Overview) | Entity hierarchy, event flow, module dependency, repository lifecycle diagrams |

### Module overview

| Module | Role |
|---|---|
| `lirp-api` | Pure interfaces & contracts: `ReactiveEntity`, `Repository`, event types, annotations (`@PersistenceMapping`, `@Aggregate`, `@Indexed`, `@Version`, `@LirpRepository`). No implementation. |
| `lirp-core` | Reactive entity machinery (`ReactiveEntityBase`, `reactiveProperty`), `VolatileRepository`, `JsonFileRepository`, `LirpEntitySerializer`, `projectionMap`, `LirpContext`, debounced write pipeline. |
| `lirp-ksp` | KSP processor generating per-entity `<Entity>_LirpTableDef`, `LirpFxScalarAccessor`, repository registration. Drives convention-over-configuration codegen. |
| `lirp-sql-api` | Pure SQL contracts: `SqlTableDef`, `JunctionAware`, `ForeignKeyAware`, `VersionedTableDef`. Sits between `lirp-api` and `lirp-sql` — no implementation, no Exposed/HikariCP dependency. |
| `lirp-sql` | `SqlRepository` built on JetBrains Exposed + HikariCP. Supports PostgreSQL/MySQL/MariaDB/SQLite (MS SQL/Oracle untested). |
| `lirp-fx` | JavaFX bridge: `fxAggregateList`/`fxAggregateSet`, scalar delegates (`fxString`, `fxInteger`...), read-only `ObservableMap` projections, FX thread dispatch. |
| `lirp-gradle-plugin` | `net.transgressoft.lirp.sql` Gradle plugin auto-configuring KSP for consumers. |
| `lirp-benchmark` | JMH benchmarks. |

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License and Attributions

Copyright (c) 2025 Octavio Calleya García.

LIRP is free software under the [GNU GPL v3 license](https://www.gnu.org/licenses/gpl-3.0.en.html#license-text).

This project uses:
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) for asynchronous programming
- [Kotlin Serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON processing
- [JetBrains Exposed](https://github.com/JetBrains/Exposed) for SQL generation and type-safe query building
- [HikariCP](https://github.com/brettwooldridge/HikariCP) for JDBC connection pooling
- [Kotest](https://kotest.io/) for testing

The approach is inspired by books including [Object Thinking by David West](https://www.goodreads.com/book/show/43940.Object_Thinking), [Domain-Driven Design: Aligning Software Architecture and Business Strategy by Vladik Khonon](https://www.goodreads.com/book/show/57573212-learning-domain-driven-design) and [Elegant Objects by Yegor Bugayenko](https://www.yegor256.com/elegant-objects.html).

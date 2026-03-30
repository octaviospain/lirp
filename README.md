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

A Kotlin/Java library where domain entities own their reactivity — property changes automatically notify subscribers, repositories persist transparently.

## Overview

lirp is built around a single idea: **domain entities should not be passive data containers**. Inspired by Domain-Driven Design, entities in lirp carry identity and behavior, and they publish their own state changes without external wiring.

Unlike general-purpose reactive toolkits (Kotlin Flow, RxJava, EventBus) where you wire streams yourself, lirp operates at the domain level — **your domain objects _are_ the reactive infrastructure**. A property declared with `reactiveProperty()` automatically notifies every subscriber on assignment, with zero overhead for unobserved entities thanks to lazy publisher initialization.

Repositories are event publishers too: they emit `CrudEvent`s on add/remove and serve as entity factories through typed `create()` methods. SQL repositories persist directly to relational databases via JetBrains Exposed, while JSON repositories handle debounced file writes — both share the same `Repository` API and entity mutation semantics.

Built on Kotlin Coroutines and Kotlin Serialization. Targets **JVM 17+, Kotlin 2.3.10**.

## SQL Persistence

`SqlRepository` persists entities to a relational database — automatically. Add an entity: an INSERT is queued. Change a property: the UPDATE is queued. Subscribe to the repository: you get `CrudEvent`s for every operation immediately (at enqueue time, before the SQL write). Tables are created on first use; existing rows are loaded on initialization.

### Batched Writes and Debounce

`SqlRepository` uses a debounced write pipeline, shared with `JsonFileRepository` via `PersistentRepositoryBase`:

- **Optimistic reads** — in-memory state is updated immediately on every CRUD operation or property mutation; reads always return the latest state without waiting for a DB write.
- **Batched SQL writes** — CRUD operations enqueue `PendingOp` entries. After a configurable debounce window (default **100 ms** of inactivity), the queue is collapsed and all pending operations are written to the database in a **single transaction**: `batchInsert` for multiple inserts, individual `deleteWhere` for deletes, and per-entity `UPDATE` for mutations.
- **Collapse algorithm** — redundant operations are eliminated before the SQL write: `Insert + Update → Insert`, `Insert + Delete → no-op`, `multiple Updates → single Update (latest state)`.
- **Max-delay cap** — a 1-second cap prevents starvation under continuous mutations by forcing a flush even while ops keep arriving.
- **`close()` flushes synchronously** — calling `close()` guarantees all pending ops are written to the database before the repository shuts down. A `ReentrantLock` serializes the final flush with any in-flight debounce flush, preventing race conditions.
- **Error retry** — if a batch write fails (e.g., transient DB error), the raw ops are re-enqueued and the next debounce cycle retries.

```kotlin
// Entity with SQL mapping, secondary index, and aggregate references
@PersistenceMapping(name = "albums")
data class Album(
    override val id: Int,
    var title: String,
    @Indexed val genre: String,
    var artistId: Int,
    val trackIds: List<Int>,
    initialRating: Double = 0.0
) : ReactiveEntityBase<Int, Album>() {
    var rating: Double by reactiveProperty(initialRating)

    @Aggregate(bubbleUp = true, onDelete = CascadeAction.DETACH)
    @Transient
    val artist by aggregate<Int, Artist> { artistId }

    @Aggregate(onDelete = CascadeAction.CASCADE)
    @Transient
    val tracks by aggregateList<Int, Track> { trackIds }

    override val uniqueId = "album-$id"
    override fun clone() = Album(id, title, genre, artistId, trackIds, rating)
}

// Repository with typed factory method — auto-registers via @LirpRepository
@LirpRepository
class AlbumRepository(context: LirpContext) :
    SqlRepository<Int, Album>(context, Album_LirpTableDef, "jdbc:postgresql://localhost:5432/mydb") {

    fun create(id: Int, title: String, genre: String, artistId: Int, trackIds: List<Int>) =
        Album(id, title, genre, artistId, trackIds).also { add(it) }
}

// KSP generates Album_LirpTableDef and Album_LirpRefAccessor at compile time

val ctx = LirpContext()
val artistRepo = ArtistRepository(ctx)
val trackRepo = TrackRepository(ctx)
val albumRepo = AlbumRepository(ctx)

albumRepo.subscribe { event ->
    println("${event.type}: ${event.entities.values}")
}

val album = albumRepo.create(1, "OK Computer", "rock", artistId = 42, trackIds = listOf(1, 2, 3))

album.rating = 9.8                     // UPDATE happens automatically → CrudEvent.UPDATE emitted
val artist = album.artist.resolve()    // lazy resolution from bound ArtistRepository
val tracks = album.tracks.resolveAll() // returns List<Track> in order

val rock: Set<Album> = albumRepo.findByIndex("genre", "rock")  // O(1) indexed lookup

ctx.close()                            // closes all repositories and connection pools
```

No manual saves. No ORM session flushing. Property assignment _is_ persistence.

Annotations are optional — convention-over-configuration infers table and column names from the class. Use `@PersistenceMapping` and `@PersistenceProperty` when you need to customize names, lengths, or precision. See the [wiki](https://github.com/octaviospain/lirp/wiki/SQL-Persistence) for full annotation reference.

### Supported Persistence Targets

| Target | Module | Status |
|--------|--------|--------|
| PostgreSQL | `lirp-sql` | Supported |
| MySQL | `lirp-sql` | Supported |
| MariaDB | `lirp-sql` | Supported |
| JSON file | `lirp-core` | Supported |
| MS SQL Server | `lirp-sql` | Planned |
| Oracle | `lirp-sql` | Planned |

## Secondary Indexes

Annotate properties with `@Indexed` for O(1) equality lookups — no collection scan:

```kotlin
data class Product(
    override val id: Int,
    var name: String,
    @Indexed(name = "cat") val category: String
) : ReactiveEntityBase<Int, Product>() {
    // ...
}

val electronics: Set<Product> = repo.findByIndex("category", "electronics")
val bySku: Optional<Product> = repo.findFirstByIndex("sku", "SKU-001")
```

## Aggregate References

Model DDD aggregate root relationships with the `@Aggregate` annotation and the `aggregate()`, `aggregateList()`, or `aggregateSet()` property delegates. A reference holds only the referenced entity's ID and resolves it lazily from the registered repository:

```kotlin
data class Product(override val id: Int, var name: String, val categoryId: Int) :
    ReactiveEntityBase<Int, Product>() {

    @Aggregate(bubbleUp = true, onDelete = CascadeAction.DETACH)
    val category by aggregate<Int, Category> { categoryId }

    override val uniqueId = "product-$id"
    override fun clone() = copy()
}

// Resolution — always live, no cache
val resolved: Optional<Category> = product.category.resolve()
```

### Collection References

Use `aggregateList` (ordered, allows duplicates) or `aggregateSet` (unique elements) when an entity holds a collection of related IDs:

```kotlin
class Playlist(override val id: Long, val name: String, val trackIds: List<Int>) :
    ReactiveEntityBase<Long, Playlist>() {

    @Aggregate(onDelete = CascadeAction.CASCADE)
    @Transient
    val tracks by aggregateList<Int, Track> { trackIds }

    override val uniqueId = "playlist-$id"
    override fun clone() = Playlist(id, name, trackIds)
}

// Resolution returns List<Track> or Set<Track>
val tracks: List<Track> = playlist.tracks.resolveAll()
```

Cascade defaults for collection references are `NONE` (unlike `DETACH` for single references). Bubble-up propagation is not supported for collection references. KSP generates `collectionEntries` in the accessor to wire the delegates automatically.

**Cascade options:** `DETACH` (default for single refs), `CASCADE`, `RESTRICT`, `NONE` (default for collection refs).

## Entity Reactivity

Entities are reactive by default — no event bus, no manual Flow collection. A property declared with `reactiveProperty()` automatically notifies every subscriber on assignment:

```kotlin
data class Product(override val id: Int, var name: String, initialPrice: Double = 0.0) : ReactiveEntityBase<Int, Product>() {
    var price: Double by reactiveProperty(initialPrice)

    override val uniqueId = "product-$id"
    override fun clone() = Product(id, name, price)
}

val product = Product(1, "Widget")

product.subscribe { event ->
    println("${event.oldEntity.price} -> ${event.newEntity.price}")
}

product.price = 29.99  // prints: 0.0 -> 29.99
```

Zero overhead for unobserved entities thanks to lazy publisher initialization.

## Repository as Factory

Repositories manage entity lifecycles and register automatically via `@LirpRepository`:

```kotlin
@LirpRepository
class ProductRepository(name: String = "Products") : VolatileRepository<Int, Product>(name) {
    fun create(id: Int, name: String, price: Double = 0.0): Product {
        val product = Product(id, name, price)
        add(product)
        return product
    }
}

val repo = ProductRepository()
val widget = repo.create(1, "Widget", 29.99)

repo.subscribe(CrudEvent.Type.CREATE) { event ->
    println("Added: ${event.entities.values}")
}
```

## JSON Persistence

`JsonFileRepository` persists entities to a JSON file with debounced writes — multiple rapid mutations are batched into a single file write. No manual save calls needed:

```kotlin
val repo = JsonFileRepository<Int, Product>(
    file = File("products.json"),
    serializer = Product.serializer()
)

val widget = Product(1, "Widget", 29.99)
repo.add(widget)       // entity is tracked and file write is scheduled

widget.price = 39.99   // debounced write triggered; no extra code needed
```

The `@Serializable` annotation (from Kotlin Serialization) is required on the entity class. For polymorphic entity hierarchies, use `TransEntityPolymorphicSerializer`.

## Installation

```kotlin
plugins {
    id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
    implementation("net.transgressoft:lirp-api:<version>")
    implementation("net.transgressoft:lirp-core:<version>")
    ksp("net.transgressoft:lirp-ksp:<version>")

    // For SQL persistence (PostgreSQL, H2, etc.)
    implementation("net.transgressoft:lirp-sql:<version>")
    runtimeOnly("org.postgresql:postgresql:<version>")  // your JDBC driver
}
```

**Requirements:** JVM 17+, Kotlin 2.3.10

## Persistence Hierarchy

lirp provides a layered persistence abstraction:

```text
Repository (interface, lirp-api)
  └── PersistentRepository (interface, lirp-api)       — marker for durable backends
        └── JsonRepository (interface, lirp-api)        — JSON-specific contract

VolatileRepository (class, lirp-core)                   — in-memory
  └── PersistentRepositoryBase (abstract, lirp-core)    — subscription mgmt, lifecycle, dirty tracking
        ├── JsonFileRepository (class, lirp-core)       — debounced JSON file writes
        └── SqlRepository (class, lirp-sql)             — batched SQL writes via Exposed
```

`PersistentRepository` is the marker interface for repositories that survive JVM lifetime. `PersistentRepositoryBase` provides the shared foundation for all durable backends: it auto-subscribes entities on add, cancels subscriptions on remove, guards mutating operations after close, and drives the debounced write pipeline. Every CRUD operation and entity mutation enqueues a `PendingOp`; a sliding-window debounce collapses the queue and calls `writePending()` on the subclass. `JsonFileRepository` rewrites the full JSON file; `SqlRepository` executes batch SQL in a single transaction.

## Key Features

- **Transparent SQL persistence** — add an entity, change a property, and the database stays in sync automatically
- **Entity-first reactivity** — `var x by reactiveProperty(init)` notifies subscribers on assignment, zero overhead when unobserved
- **Two subscription levels** — repository-level `CrudEvent`s and entity-level `MutationEvent`s
- **DDD aggregate references** — `@Aggregate` with single-entity (`aggregate`) and collection (`aggregateList`, `aggregateSet`) references, configurable cascade (DETACH/CASCADE/RESTRICT/NONE)
- **Secondary indexes** — `@Indexed` for O(1) equality lookups without collection scans
- **Convention-over-configuration** — KSP generates table definitions from entity classes; annotations only when you need customization
- **JSON persistence** — debounced file writes via `JsonFileRepository`
- **Repository-as-factory** — typed `create()` methods with automatic `@LirpRepository` registration
- **Full Java interoperability**

## Limitations and Design Trade-offs

lirp's in-memory-first architecture has trade-offs that influence where it fits best:

- **Full dataset loaded into memory** — On initialization, `SqlRepository` and `JsonFileRepository` load all rows from the backing store into a `ConcurrentHashMap`. This enables instant reads and O(1) indexed lookups but means the JVM heap must fit the entire dataset. Repositories with tens of thousands of entities will increase memory pressure; hundreds of thousands are impractical without significant heap tuning.
- **Optimistic writes, eventual persistence** — In-memory state is always authoritative. A crash between enqueue and flush loses uncommitted mutations. The debounce window (default 100 ms, max 1 s) defines the data-loss window.
- **Single-node only** — There is no cross-process replication or distributed cache invalidation. Each JVM instance holds its own copy of the data. lirp is not a substitute for a shared database layer in a multi-instance deployment.
- **No query language** — Reads are key lookups (`findById`), index lookups (`findByIndex`), or full-scan predicates (`findFirst`). Complex joins, aggregations, or range queries should be handled at the SQL level outside of lirp.

**Best suited for:** microservices or bounded contexts with small-to-medium datasets (hundreds to low thousands of entities) where domain reactivity and transparent persistence matter more than raw query power. Think configuration stores, user preference services, catalog management, or any context where the working set fits comfortably in memory.

**Not suited for:** analytics workloads, high-cardinality datasets, or services requiring cross-instance consistency.

Performance benchmarks comparing lirp's throughput and latency against direct JDBC and other persistence frameworks are planned — see [#69](https://github.com/octaviospain/lirp/issues/69).

## Documentation

For detailed guides, examples, and architecture diagrams, see the **[LIRP Wiki](https://github.com/octaviospain/lirp/wiki)**.

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## License and Attributions

Copyright (c) 2025 Octavio Calleya García.

lirp is free software under GNU GPL version 3 license and is available [here](https://www.gnu.org/licenses/gpl-3.0.en.html#license-text).

This project uses:
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) for asynchronous programming
- [Kotlin Serialization](https://github.com/Kotlin/kotlinx.serialization) for JSON processing
- [JetBrains Exposed](https://github.com/JetBrains/Exposed) for SQL generation and type-safe query building
- [HikariCP](https://github.com/brettwooldridge/HikariCP) for JDBC connection pooling
- [Kotest](https://kotest.io/) for testing

The approach is inspired by books including [Object Thinking by David West](https://www.goodreads.com/book/show/43940.Object_Thinking), [Domain-Driven Design: Aligning Software Architecture and Business Strategy by Vladik Khonon](https://www.goodreads.com/book/show/57573212-learning-domain-driven-design) and [Elegant Objects by Yegor Bugayenko](https://www.yegor256.com/elegant-objects.html).

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
    val tracks by aggregateList<Int, Track>(trackIds)

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

`@PersistenceMapping` on the entity class triggers KSP table definition generation for SQL persistence. Convention-over-configuration infers table and column names from the class — use `@PersistenceProperty` on individual properties when you need to customize names, lengths, or precision. See the [wiki](https://github.com/octaviospain/lirp/wiki/SQL-Persistence) for full annotation reference. These annotations are not needed for `VolatileRepository` or `JsonFileRepository`.

### Supported Persistence Targets

| Target | Module | Status      |
|--------|--------|-------------|
| PostgreSQL | `lirp-sql` | Supported   |
| MySQL | `lirp-sql` | Supported   |
| MariaDB | `lirp-sql` | Supported   |
| JSON file | `lirp-core` | Supported   |
| MS SQL Server | `lirp-sql` | Not tested  |
| Oracle | `lirp-sql` | Not tested |

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

## Transactional Model

LIRP is designed around **single-aggregate atomicity** with **optimistic concurrency control**. The transactional boundary for every SQL repository is one aggregate — all collapsed pending operations for a single debounce flush commit in a single JDBC transaction.

### Guarantees

- **Single-aggregate atomicity.** All collapsed ops for one flush cycle (inserts, updates, deletes, clear) commit in one `transaction(db) { ... }`. Either the whole cycle lands on disk, or the transaction rolls back.
- **Event-before-persistence.** `CrudEvent`s fire at the consumer call site, not after SQL commit. Reactive UI bindings see state changes immediately (optimistic reads). If the persist fails later, a subsequent `StandardCrudEvent.Conflict` event explains the divergence and the in-memory state is reconciled with the database.
- **Optimistic locking via `@Version`.** Annotating a `Long` reactive property with `@Version` turns every UPDATE and DELETE into a versioned statement: `... WHERE id = ? AND version = ?`. Zero-row-affected triggers an auto-reload from the database and emits a `Conflict` event alongside the recovered canonical state. No silent last-write-wins. Example:

```kotlin
class Order(override val id: Int) : ReactiveEntityBase<Int, Order>() {
    var status: String by reactiveProperty("PENDING")
    @Version var version: Long by reactiveProperty(0L)
    override val uniqueId: String get() = "order-$id"
    override fun clone() = Order(id).also { it.withEventsDisabled { it.status = status; it.version = version } }
}

repo.subscribe { event ->
    if (event is StandardCrudEvent.Conflict<Int, Order>) {
        // Conflict: canonical state is already swapped into the in-memory entity.
        // event.entities.values.single() is the winning state; event.oldEntities is the local state we tried.
    }
}
```

### Non-Guarantees

LIRP intentionally does not provide:

- **Multi-aggregate transactions.** Each `SqlRepository` transacts over its own table. Coordinating writes across two or more aggregates is a consumer concern. The recommended pattern is a saga via `CrudEvent` subscribers with compensation — see [Transactional Boundaries](https://github.com/octaviospain/lirp/wiki/Transactional-Boundaries).
- **Distributed saga orchestration.** No saga DSL, no event bus, no workflow engine. LIRP provides the primitives (`CrudEvent` stream on every repository, `Conflict` event as a compensation signal); consumers compose.
- **Outbox pattern.** `CrudEvent`s go directly to subscribers. If you need a durable event log for downstream systems, subscribe and write to your own outbox — LIRP does not embed one.

These boundaries are design decisions, not missing features. See the wiki page for context and the saga/compensation example.

## Aggregate References

Model DDD aggregate root relationships with the `@Aggregate` annotation and the `aggregate()`, `aggregateList()`, or `aggregateSet()` property delegates. The `@Aggregate` annotation is **required** on every aggregate reference property — the KSP processor uses it to generate the `_LirpRefAccessor` that `RegistryBase` needs at runtime for reference binding and cascade resolution. Without it, `RegistryBase` throws `IllegalStateException` when the entity is added to a repository.

A reference holds only the referenced entity's ID and resolves it lazily from the registered repository:

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

LIRP provides four collection delegate types for modeling entities that hold collections of related IDs. Each factory returns a standard Kotlin stdlib collection type that also implements `AggregateCollectionRef` internally, providing both the full collection API and access to the underlying reference IDs. For full copy-paste runnable examples, see [DDD & Aggregates](https://github.com/octaviospain/lirp/wiki/DDD-and-Aggregates) in the wiki.

| Delegate | Return type | Mutability | Ordering | Uniqueness |
|----------|------------|-----------|----------|------------|
| `aggregateList` | `List<E>` | read-only | ordered | allows duplicates |
| `aggregateSet` | `Set<E>` | read-only | unordered | unique |
| `mutableAggregateList` | `MutableList<E>` | mutable | ordered | allows duplicates |
| `mutableAggregateSet` | `MutableSet<E>` | mutable | insertion-ordered | unique |

**Read-only example** — use `aggregateList` or `aggregateSet` when the collection of related IDs never changes at runtime:

```kotlin
class Playlist(override val id: Long, val name: String, val trackIds: List<Int>) :
    ReactiveEntityBase<Long, Playlist>() {

    @Aggregate(onDelete = CascadeAction.CASCADE)
    @Transient
    val tracks by aggregateList<Int, Track>(trackIds)

    override val uniqueId = "playlist-$id"
    override fun clone() = Playlist(id, name, trackIds)
}

// Standard List<Track> — indexed access, iteration, all collection operations
val firstTrack: Track = playlist.tracks[0]
val allTracks: List<Track> = playlist.tracks.toList()
```

**Mutable example** — use `mutableAggregateList` or `mutableAggregateSet` when the collection must support runtime mutations. The property type is `MutableList<E>` / `MutableSet<E>`, providing the full standard collection API. Every mutation emits exactly one event on the owning entity — an `AggregateMutationEvent` wrapping a `CollectionChangeEvent` with the added/removed diff:

```kotlin
@Serializable
data class Playlist(override val id: Long, val name: String, val trackIds: List<Int> = emptyList()) :
    ReactiveEntityBase<Long, Playlist>() {

    @Aggregate(onDelete = CascadeAction.CASCADE)
    val tracks by mutableAggregateList<Int, Track>(trackIds)

    override val uniqueId = "playlist-$id"
    // Deep-copy referenceIds in clone() so mutateAndPublish detects changes correctly
    override fun clone() = copy(trackIds = tracks.referenceIds.toList())

    override fun equals(other: Any?): Boolean {
        if (other !is Playlist) return false
        return id == other.id && name == other.name && tracks.referenceIds == other.tracks.referenceIds
    }
    override fun hashCode() = 31 * (31 * id.hashCode() + name.hashCode()) + tracks.referenceIds.hashCode()
}

// Standard MutableList<Track> operations — no special API needed
playlist.tracks.add(newTrack)           // MutationEvent emitted
playlist.tracks.remove(oldTrack)        // MutationEvent emitted
playlist.tracks[0]                      // indexed access — resolves entity from registry on every call
playlist.tracks.removeAt(2)             // remove by position — MutationEvent emitted
playlist.tracks[1] = newTrack           // replace at position — MutationEvent emitted

// Views work naturally — mutations on views emit events on the owning entity
val sub = playlist.tracks.subList(0, 3)
val iter = playlist.tracks.listIterator()
```

**Resolution behavior:** Entities are resolved lazily from the registry on every element access — no caching. If an ID cannot be found in the registry, `NoSuchElementException` is thrown. If the registry changes (entity updated or removed), the next access reflects the current state.

**Accessing reference IDs:** The reference ID list is accessible via a cast to `AggregateCollectionRef`:

```kotlin
// For serialization, debugging, or direct ID access
val ids = (playlist.tracks as AggregateCollectionRef<*, *>).referenceIds
```

**Bulk operations:** `addAll(collection)`, `removeAll(collection)`, and `retainAll(collection)` each emit exactly one `CollectionChangeEvent` (wrapped in `AggregateMutationEvent`) regardless of how many elements are in the input — the backing ID store is updated atomically.

**Important notes:**
- The delegate's `referenceIds` property (accessed via cast) is the live source of truth after mutations. For persistence, read from `(tracks as AggregateCollectionRef<*, *>).referenceIds` in your serializer or `toParams` method.
- Entities using mutable collection delegates MUST deep-copy `referenceIds` in `clone()` and override `equals`/`hashCode()` to compare `referenceIds`. Without this, the `mutateAndPublish` before/after equality check always returns `true` and mutation events are silenced.
- KSP generates accessor metadata for all four delegate types, wiring them automatically — no manual accessor code needed.
- Cascade defaults for collection references are `NONE` (unlike `DETACH` for single references).
- Bubble-up propagation is not supported for collection references.

**Cascade options:** `DETACH` (default for single refs), `CASCADE`, `RESTRICT`, `NONE` (default for collection refs).

### Collection Change Events

When a mutable aggregate collection is mutated, the owning entity emits an `AggregateMutationEvent` whose `childEvent` is a `CollectionChangeEvent<E>`. Unlike `ReactiveMutationEvent` (which carries before/after entity snapshots), `CollectionChangeEvent` carries a diff — exactly which elements were added and removed:

| Operation | Event type | `added` | `removed` |
|-----------|-----------|---------|-----------|
| `add(e)` / `addAll(es)` | `ADD` | added elements | empty |
| `remove(e)` / `removeAll(es)` | `REMOVE` | empty | removed elements |
| `set(index, e)` | `REPLACE` | `[newElement]` | `[oldElement]` |
| `clear()` | `CLEAR` | empty | all prior elements |

Bulk operations (`addAll`, `removeAll`) emit a single event per call, not one per element. Clearing an already-empty collection emits no event.

**3-tier subscription API:**

```kotlin
// All events — property mutations, collection changes, and bubble-up
entity.subscribe { event -> ... }

// Property mutations only (excludes AggregateMutationEvent)
entity.subscribeToMutations { event ->
    println("${event.oldEntity} -> ${event.newEntity}")
}

// Collection changes — type-safe with element class
entity.subscribeToCollectionChanges(Track::class) { event ->
    println("Type: ${event.type}, Added: ${event.added}, Removed: ${event.removed}")
}

// Filter to a specific collection
entity.subscribeToCollectionChanges(Track::class, "tracks") { event ->
    println("Tracks changed: +${event.added.size} -${event.removed.size}")
}
```

**Java Consumer overloads:**

```java
// All collections — type-safe with element class
CollectionChangeEventExtensionsKt.subscribeToCollectionChanges(entity, Track.class, null,
    (Consumer<CollectionChangeEvent<Track>>) event ->
        System.out.println("Added: " + event.getAdded()));

// Specific collection
CollectionChangeEventExtensionsKt.subscribeToCollectionChanges(entity, Track.class, "tracks",
    (Consumer<CollectionChangeEvent<Track>>) event ->
        System.out.println("Added: " + event.getAdded()));

// Property mutations only
CollectionChangeEventExtensionsKt.subscribeToMutations(entity,
    (Consumer<ReactiveMutationEvent<Integer, MyEntity>>) event ->
        System.out.println("Old: " + event.getOldEntity() + " New: " + event.getNewEntity()));
```

> **v2.4.0 migration note:** Collection mutations on `mutableAggregateList`/`mutableAggregateSet` no longer emit `ReactiveMutationEvent` with full entity snapshots. Subscribers that previously handled collection changes via the general `subscribe {}` callback must now handle `AggregateMutationEvent` with a `CollectionChangeEvent` childEvent, or use `subscribeToCollectionChanges`. `AggregateMutationEvent.childEvent` was widened from `MutationEvent<*, *>` to `LirpEvent<*>` — use `is` checks before casting.

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

## Delegation-Based Repositories

When you need to wrap an existing `VolatileRepository` (or other `RegistryBase` subclass) rather than extending it — for example, to add domain-specific behaviour while keeping the repository as a field — use Kotlin's `by` delegation combined with a manual `init` block:

```kotlin
class MyCustomRepo(
    private val delegate: VolatileRepository<Int, MyEntity>
) : Repository<Int, MyEntity> by delegate {

    init {
        RegistryBase.registerRepository(MyEntity::class.java, delegate)
    }

    fun create(id: Int, name: String): MyEntity =
        MyEntity(id, name).also { add(it) }
}
```

The `by delegate` clause routes all `Repository` method calls to the underlying `VolatileRepository`. The `init` block calls `RegistryBase.registerRepository()` to register the **delegate** — not the wrapper — into `LirpContext.default`. This means `LirpContext.default.registries()` returns the `VolatileRepository` instance, which is the actual `RegistryBase` subclass.

**Key behaviours:**

- The delegate is what gets registered: `context.registryFor(MyEntity::class.java)` returns the `VolatileRepository`, not the wrapper.
- Calling `delegate.close()` deregisters the repository from the context.
- Registering the same instance twice is idempotent (safe to call from multiple wrappers sharing a delegate).
- Registering a different instance for the same entity class throws `IllegalStateException`, consistent with the `@LirpRepository` duplicate-detection rule.

### Deregistration and Lifecycle

Delegation-based repositories should implement `AutoCloseable` to cleanly deregister on shutdown. The `close()` method removes the context mapping via `RegistryBase.deregisterRepository()` and then shuts down the delegate:

```kotlin
class MyCustomRepo(
    private val delegate: VolatileRepository<Int, MyEntity>
) : Repository<Int, MyEntity> by delegate, AutoCloseable {

    init {
        RegistryBase.registerRepository(MyEntity::class.java, delegate)
    }

    fun create(id: Int, name: String): MyEntity =
        MyEntity(id, name).also { add(it) }

    override fun close() {
        RegistryBase.deregisterRepository(MyEntity::class.java)
        delegate.close()
    }
}
```

- `close()` calls `deregisterRepository()` first, then `delegate.close()` -- order matters because `delegate.close()` also deregisters (the delegate's own `close()` removes it from context)
- `deregisterRepository()` is idempotent -- calling it for an unregistered class is a safe no-op
- `deregisterRepository()` only removes the context mapping; it does not close the repository or its publisher
- After close, the entity class slot is free for a new repository instance

### Registry Lookup

`LirpContext.registryFor()` is public API and can be called from any module, including external ones like lirp-sql. It returns the `Registry` registered for a given entity class, or `null` if none is registered.

**Kotlin (reified overload):**

```kotlin
val registry: Registry<*, Album>? = LirpContext.default.registryFor<Album>()
```

**Java / Class-based overload:**

```java
Registry<?, ?> registry = LirpContext.Companion.getDefault().registryFor(Album.class);
```

Registry lookup is read-only — `register()` and `deregister()` remain internal. External modules can read the registry map but cannot modify it directly.

## Inner Class Support

Entities and repositories declared as inner classes are fully supported. KSP generates accessor and info classes using the JVM binary name (`$`-separated), which matches the runtime `Class.forName` lookup:

```kotlin
class MusicLibrary {
    @LirpRepository
    class TrackRepository : VolatileRepository<Int, Track>()

    data class Track(override val id: Int, var title: String) : ReactiveEntityBase<Int, Track>() {
        override val uniqueId = "track-$id"
        override fun clone() = copy()
    }
}
```

KSP generates `MusicLibrary$TrackRepository_LirpRegistryInfo`, which `RegistryBase` resolves at runtime via `Class.forName("MusicLibrary$TrackRepository_LirpRegistryInfo")`. Anonymous and local class entities are safely handled — their ref and index discovery is skipped automatically since they cannot have KSP-generated accessors.

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

### Framework Serializer (LirpEntitySerializer)

Entities that use `reactiveProperty()` and aggregate collection delegates can be serialized without `@Serializable` or any KSP-generated code. `LirpEntitySerializer` introspects the entity's delegate registry at runtime and builds a `KSerializer` automatically:

```kotlin
class MyEntity(override val id: Int) : ReactiveEntityBase<Int, MyEntity>() {
    var name by reactiveProperty("default")
    val items by mutableAggregateList<Int, Track>()

    override val uniqueId get() = id.toString()
    override fun clone() = MyEntity(id).also { copy ->
        copy.withEventsDisabled {
            copy.name = name
            // Deep-copy mutable aggregate backing IDs for correct mutation event comparison
        }
    }
}

// Create the serializer from a sample instance
val serializer = lirpSerializer(MyEntity(0))
val repo = JsonFileRepository(file, MapSerializer(Int.serializer(), serializer))
```

The serializer encodes constructor parameters first, then delegate properties in declaration order. Reactive property values are encoded as their declared type; aggregate delegate backing IDs are encoded as a JSON array under the delegate's property name.

Entities with `@Serializable` continue to use their plugin-generated serializer — `LirpEntitySerializer` is opt-in and does not affect existing code.

#### KSP-Accelerated FxScalar Serialization

When `lirp-ksp` is applied, entities with `fxString()`, `fxInteger()`, and other FxScalar delegates get a generated `{ClassName}_LirpFxScalarAccessor` class. `LirpEntitySerializer` discovers this class at runtime via `Class.forName` and uses the direct lambda accessors it provides — no `java.lang.reflect.Method` invocations and no `--add-opens` JVM flags required.

When the generated accessor is not present (e.g., `lirp-ksp` is not applied), then the serializer falls back to reflection and logs a deprecation warning at startup. The `--add-opens` flags in `build.gradle` are required only for this reflection fallback path.

The `LirpAccessorValidationProcessor` enforces consistency at compile time: if an entity has `@Indexed` or FxScalar delegates, it verifies that the corresponding generated accessors exist in the same compilation round. Missing accessors produce a KSP build error identifying the entity and the missing class — no runtime surprises.

### Manual Serializers

For entities annotated with `@Serializable`, supply the Kotlin Serialization serializer directly. For polymorphic entity hierarchies, use `TransEntityPolymorphicSerializer`.

## Deferred Loading

Both `JsonFileRepository` and `SqlRepository` support **deferred loading**: construction and data loading are decoupled so you can coordinate multiple repositories before any of them reads from the backing store.

By default (`loadOnInit = true`), rows are loaded immediately during construction. Pass `loadOnInit = false` to defer loading until an explicit `load()` call:

```kotlin
// SQL — table is created on construction, but no rows are loaded yet
val repo = SqlRepository(jdbcUrl, tableDef, loadOnInit = false)
// ... set up aggregate references, register other repositories ...
repo.load()  // rows loaded now; mutating operations become available

// JSON — file is validated on construction, but no entities are read yet
val jsonRepo = JsonFileRepository(file, serializer, loadOnInit = false)
// ... configure serializer modules, coordinate with other repos ...
jsonRepo.load()  // entities loaded now
```

**Key rules:**

- `load()` may only be called once; a second call throws `IllegalStateException`.
- Mutating operations (`add`, `remove`, `removeAll`, `clear`) throw `IllegalStateException` before `load()` is called on a deferred repository. Query operations return empty results.
- `isLoaded` returns `false` before `load()` and `true` after a successful call.
- For SQL repositories, the table is always created during construction regardless of `loadOnInit`.

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

### External Consumer Setup (Gradle)

Add the LIRP Gradle plugin alongside the KSP plugin to get automatic `SqlTableDef` generation.

Since the plugin is published to Maven Central (not the Gradle Plugin Portal), add `mavenCentral()`
to `pluginManagement` repositories in your `settings.gradle`:

```groovy
// settings.gradle
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Then apply the plugin in your `build.gradle`:

```groovy
plugins {
    id 'org.jetbrains.kotlin.jvm' version 'X.Y.Z'
    id 'com.google.devtools.ksp' version 'X.Y.Z'
    id 'net.transgressoft.lirp.sql' version 'X.Y.Z'
}

dependencies {
    implementation 'net.transgressoft:lirp-sql:X.Y.Z'
    ksp 'net.transgressoft:lirp-ksp:X.Y.Z'
}
```

The `net.transgressoft.lirp.sql` Gradle plugin automatically detects `lirp-sql` in your
`implementation` or `api` dependencies and adds it to the `ksp` configuration. This enables the KSP
processor to find `SqlTableDef` via the resolver and generate SQL-aware table definitions.

### External Consumer Setup (Maven)

Maven users must manually add `lirp-sql` to the KSP processor classpath so the resolver can
find `SqlTableDef`. The Gradle plugin is not available for Maven — configure the KSP Maven
plugin to include `lirp-sql` as a processor dependency:

```xml
<dependencies>
    <dependency>
        <groupId>net.transgressoft</groupId>
        <artifactId>lirp-api</artifactId>
        <version>X.Y.Z</version>
    </dependency>
    <dependency>
        <groupId>net.transgressoft</groupId>
        <artifactId>lirp-core</artifactId>
        <version>X.Y.Z</version>
    </dependency>
    <dependency>
        <groupId>net.transgressoft</groupId>
        <artifactId>lirp-sql</artifactId>
        <version>X.Y.Z</version>
    </dependency>
</dependencies>
```

In the KSP Maven plugin configuration, add `lirp-sql` alongside `lirp-ksp` as processor
dependencies so the KSP resolver can discover `SqlTableDef` on its classpath:

```xml
<plugin>
    <groupId>com.google.devtools.ksp</groupId>
    <artifactId>symbol-processing-maven-plugin</artifactId>
    <version>X.Y.Z</version>
    <configuration>
        <processorDependencies>
            <dependency>
                <groupId>net.transgressoft</groupId>
                <artifactId>lirp-ksp</artifactId>
                <version>X.Y.Z</version>
            </dependency>
            <dependency>
                <groupId>net.transgressoft</groupId>
                <artifactId>lirp-sql</artifactId>
                <version>X.Y.Z</version>
            </dependency>
        </processorDependencies>
    </configuration>
</plugin>
```

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

## JavaFX Integration (lirp-fx)

The `lirp-fx` module bridges lirp's aggregate collection delegates with JavaFX `ObservableList` and `ObservableSet`. Use `fxAggregateList()` and `fxAggregateSet()` as drop-in replacements for `mutableAggregateList()` and `mutableAggregateSet()` when your entities participate in JavaFX data binding.

```kotlin
class Playlist(
    override val id: Int,
    name: String,
    initialTrackIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, Playlist>() {
    override val uniqueId = "playlist-$id"

    var name: String by reactiveProperty(name)

    @Aggregate(onDelete = CascadeAction.DETACH)
    val tracks by fxAggregateList<Int, Track>(initialTrackIds)

    override fun clone() = Playlist(id, name, tracks.referenceIds.toList())
}

// Bind directly to a TableView — mutations fire both JavaFX ListChangeListener
// notifications AND lirp CollectionChangeEvent on the entity's event stream.
val tableView = TableView<Track>()
tableView.items = playlist.tracks  // ObservableList<Track>
```

**Dual notification:** A single mutation fires both a JavaFX `ListChangeListener.Change` (or `SetChangeListener.Change`) and a lirp `CollectionChangeEvent` wrapped in an `AggregateMutationEvent`. UI bindings and domain event subscribers both stay in sync.

**FX thread dispatch:** By default, JavaFX listener notifications are dispatched to the FX Application Thread via `Platform.runLater`. Pass `dispatchToFxThread = false` to dispatch on `ReactiveScope.flowScope` instead, consistent with how lirp events are dispatched asynchronously.

**Lazy-snapshot mode:** For collections with 10k+ entities, pass `lazySnapshot = true` to eliminate the in-memory element cache. In this mode, `size`, `get(index)`, and iteration resolve entities from the registry on demand instead of maintaining a parallel reference list — halving the memory footprint for large collections. All mutation operations and `ListChangeListener`/`SetChangeListener` notifications work identically to the default mode. The trade-off is per-access registry lookup latency instead of a cache hit, which is acceptable when memory savings outweigh lookup cost.

```kotlin
@Aggregate(onDelete = CascadeAction.DETACH)
val items by fxAggregateList<Int, AudioItem>(lazySnapshot = true)
```

Precondition: lazy-snapshot mode requires registry binding — the entity must be added to a repository before any structural access (`get`, iteration, `size`) resolves entities. This is the same precondition that applies to all aggregate collection delegates; eager mode hides it by caching references at `addAll`-time.

**Dependency:** JavaFX is `compileOnly` -- you bring your own JavaFX version at runtime. Add `lirp-fx` alongside your existing JavaFX dependency:

```kotlin
dependencies {
    implementation("net.transgressoft:lirp-fx:<version>")
    implementation("org.openjfx:javafx-base:21")
}
```

### Scalar Property Delegates

`lirp-fx` provides scalar property delegates that bridge lirp's reactive properties with JavaFX property types:

```kotlin
class MyEntity(override val id: Int, name: String, age: Int) :
    ReactiveEntityBase<Int, MyEntity>(), IdentifiableEntity<Int> {

    override val uniqueId: String get() = "my-entity-$id"

    val nameProperty: StringProperty by fxString(name)
    val ageProperty: IntegerProperty by fxInteger(age)
    val activeProperty: BooleanProperty by fxBoolean(false)
    val scoreProperty: DoubleProperty by fxDouble(0.0)
    val tagProperty: ObjectProperty<String?> by fxObject<String?>(null)

    override fun clone(): MyEntity = MyEntity(id, nameProperty.get(), ageProperty.get())
}
```

When the entity is in a repository, setting a scalar property fires both a lirp `MutationEvent` and JavaFX `ChangeListener` notifications:

```kotlin
entity.nameProperty.set("Updated")  // Emits MutationEvent + fires ChangeListeners
entity.nameProperty.addListener { _, old, new -> println("$old -> $new") }
```

Available factories: `fxString()`, `fxInteger()`, `fxDouble()`, `fxFloat()`, `fxLong()`, `fxBoolean()`, `fxObject<T>()`.

**Java API:** Use `FxProperties` for static factory access:

```java
LirpStringProperty name = FxProperties.fxString("default", true);
LirpIntegerProperty age = FxProperties.fxInteger(0, true);
FxAggregateList<Integer, Track> tracks = FxProperties.fxAggregateList(List.of(), true);
```

### Projection Maps

Derive a read-only `Map<PK, List<E>>` from an existing `aggregateList` or `aggregateSet` delegate, grouping entities by a secondary key:

```kotlin
class AudioLibrary(
    override val id: Int,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, AudioLibrary>() {
    override val uniqueId = "audio-library-$id"

    @Aggregate(onDelete = CascadeAction.DETACH)
    val audioItems by mutableAggregateList<Int, AudioItem>(initialAudioItemIds)

    val byTitle: Map<String, List<AudioItem>>
        by projectionMap(::audioItems) { it.title }

    override fun clone() = AudioLibrary(id, audioItems.referenceIds.toList())
}
```

The projection lazily initializes on the first access and groups entities using a `TreeMap` for natural sorted key order. When the source is a `mutableAggregateList` or `mutableAggregateSet`, the projection auto-updates incrementally whenever entities are added or removed — no manual notification required:

```kotlin
val newItem = MutableAudioItem(42, "Blues")
library.audioItems.add(newItem)
// The projection updates automatically
```

- `onChange` — optional callback invoked after each projection change with the current map state

The returned map is read-only (`Collections.unmodifiableMap`). All mutations flow through the source collection.

### FX Projection Maps

Derive a read-only `ObservableMap<PK, List<E>>` from an existing `fxAggregateList` or `fxAggregateSet` delegate, grouping entities by a secondary key:

```kotlin
class AudioLibrary(
    override val id: Int,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, AudioLibrary>() {
    override val uniqueId = "audio-library-$id"

    @Aggregate(onDelete = CascadeAction.DETACH)
    val audioItems by fxAggregateList<Int, AudioItem>(initialAudioItemIds, dispatchToFxThread = false)

    val byAlbum: ObservableMap<String, List<AudioItem>>
        by fxProjectionMap(::audioItems, AudioItem::albumName)

    override fun clone() = AudioLibrary(id, audioItems.referenceIds.toList())
}
```

The projection updates incrementally when the source collection changes, firing targeted `MapChangeListener` events per affected group key only. Keys iterate in natural sorted order via a `TreeMap` backing. The returned map is read-only — calling `put` or `remove` throws `UnsupportedOperationException`.

Java callers use the static factory and access the map directly on the returned `FxProjectionMap`, which implements `ObservableMap`:

```java
FxProjectionMap<Integer, String, AudioItem> byAlbum =
    FxProperties.fxProjectionMap(() -> audioItems, AudioItem::getAlbumName, false);
byAlbum.addListener((MapChangeListener<String, List<AudioItem>>) change -> { ... });
int count = byAlbum.size();
List<AudioItem> albums = byAlbum.get("Album A");
```
## Annotations Reference

| Annotation | Target | Required when | Not needed for |
|-----------|--------|--------------|----------------|
| `@Aggregate` | aggregate reference properties | any `aggregate()`, `aggregateList()`, `aggregateSet()`, `mutableAggregateList()`, `mutableAggregateSet()`, `fxAggregateList()`, `fxAggregateSet()` delegate | `reactiveProperty()`, `fxString()`, `fxInteger()`, and other scalar property delegates |
| `@PersistenceMapping` | entity class | SQL persistence via `SqlRepository` | `VolatileRepository`, `JsonFileRepository`, `lirp-fx` delegates |
| `@PersistenceProperty` | entity property | customizing SQL column name, length, precision, or type | uses convention defaults when absent; not needed outside `SqlRepository` |
| `@Indexed` | entity property | O(1) equality lookups via `findByIndex` | when only key-based lookups or full-scan predicates are needed |
| `@LirpRepository` | repository class | auto-discovery via KSP-generated `LirpContext` registration | manual repository registration via `RegistryBase.registerRepository()` |

**`@Aggregate`** triggers KSP generation of `_LirpRefAccessor`, which `RegistryBase` requires at runtime for reference binding and cascade resolution. Without it on an aggregate delegate property, `RegistryBase` throws `IllegalStateException`.

**`@PersistenceMapping`** triggers KSP generation of `_LirpTableDef` for SQL schema creation. Convention-over-configuration infers table name from the class name; use `@PersistenceProperty` on individual properties to customize column details.

**FxScalar delegates** (`fxString()`, `fxInteger()`, `fxDouble()`, `fxFloat()`, `fxLong()`, `fxBoolean()`, `fxObject()`) trigger KSP generation of `_LirpFxScalarAccessor` when the entity is processed by `lirp-ksp`. The generated accessor provides direct get/set lambdas and compile-time resolved `KSerializer` instances for each FxScalar property, eliminating the `java.lang.reflect.Method` access that `LirpEntitySerializer` would otherwise require. No annotation is needed — KSP detects FxScalar properties automatically by type.

## Key Features

- **Transparent SQL persistence** — add an entity, change a property, and the database stays in sync automatically
- **Entity-first reactivity** — `var x by reactiveProperty(init)` notifies subscribers on assignment, zero overhead when unobserved
- **Two subscription levels** — repository-level `CrudEvent`s and entity-level `MutationEvent`s
- **DDD aggregate references** — `@Aggregate` with single-entity (`aggregate`) and collection (`aggregateList`, `aggregateSet`) references, configurable cascade (DETACH/CASCADE/RESTRICT/NONE)
- **Secondary indexes** — `@Indexed` for O(1) equality lookups without collection scans
- **Convention-over-configuration** — KSP generates table definitions from entity classes; annotations only when you need customization
- **JSON persistence** — debounced file writes via `JsonFileRepository`
- **Repository-as-factory** — typed `create()` methods with automatic `@LirpRepository` registration
- **JavaFX integration** — `fxAggregateList`/`fxAggregateSet` delegates bridging lirp collections with `ObservableList`/`ObservableSet`; `fxProjectionMap` for read-only grouped `ObservableMap` projections
- **Non-FX projection maps** — `projectionMap` in `lirp-core` groups entities into a standard `Map<PK, List<E>>` with no JavaFX dependency, suitable for Android, Compose Multiplatform, and server-side consumers
- **Full Java interoperability**

## Limitations and Design Trade-offs

lirp's in-memory-first architecture has trade-offs that influence where it fits best:

- **Full dataset loaded into memory** — On initialization, `SqlRepository` and `JsonFileRepository` load all rows from the backing store into a `ConcurrentHashMap`. This enables instant reads and O(1) indexed lookups but means the JVM heap must fit the entire dataset. Repositories with tens of thousands of entities will increase memory pressure; hundreds of thousands are impractical without significant heap tuning.
- **Optimistic writes, eventual persistence** — In-memory state is always authoritative. A crash between enqueue and flush loses uncommitted mutations. The debounce window (default 100 ms, max 1 s) defines the data-loss window.
- **Single-node only** — There is no cross-process replication or distributed cache invalidation. Each JVM instance holds its own copy of the data. lirp is not a substitute for a shared database layer in a multi-instance deployment.
- **No query language** — Reads are key lookups (`findById`), index lookups (`findByIndex`), or full-scan predicates (`findFirst`). Complex joins, aggregations, or range queries should be handled at the SQL level outside of lirp.

**Best suited for:** microservices or bounded contexts with small-to-medium datasets (hundreds to low thousands of entities) where domain reactivity and transparent persistence matter more than raw query power. Think configuration stores, user preference services, catalog management, or any context where the working set fits comfortably in memory.

**Not suited for:** analytics workloads, high-cardinality datasets, or services requiring cross-instance consistency.

## Performance

Benchmarks were run with JMH 1.37 on OpenJDK 21.0.10, 13th Gen Intel Core i7-13700, 62 GB RAM. All repository benchmarks use H2 in-memory databases with per-trial isolation. See the [Performance Benchmarks wiki page](https://github.com/octaviospain/lirp/wiki/Performance-Benchmarks) for full results, environment specs, and comparative analysis.

### Key Numbers (at 10,000 entities)

| Repository | Operation | Result |
|-----------|-----------|--------|
| `VolatileRepository` | add() throughput | 271,877 ops/s |
| `VolatileRepository` | findById() p50 | 27 ns |
| `SqlRepository` | add() throughput | 92,151 ops/s |
| `SqlRepository` | findById() p50 | 27 ns |
| `SqlRepository` | mutation-flush p50 | 63,767 µs (full batch write) |
| `JsonFileRepository` | add() throughput | 97,720 ops/s |
| `JsonFileRepository` | mutation-flush p50 | 66,978 µs (full file write) |

### Why add() is Fast

`add()` on `SqlRepository` and `JsonFileRepository` enqueues the entity in-memory immediately and returns — the SQL INSERT or JSON file write happens asynchronously via the debounced write pipeline (100 ms window, 1 s max delay). This is why add() throughput is comparable to `VolatileRepository`: all three repositories share the same O(1) in-memory write path.

### SqlRepository vs Direct JDBC vs JPA/Hibernate

lirp `SqlRepository` measured against raw `java.sql.PreparedStatement` (zero-overhead baseline) and JPA/Hibernate, all using H2 in-memory databases.

**findById() p50 — read latency:**

| Operation | lirp SqlRepository | Direct JDBC | JPA/Hibernate |
|-----------|-------------------|-------------|---------------|
| findById() p50 | **27 ns** | 960 ns | 1,000 ns |
| findById() ratio | baseline | 35x slower | 37x slower |

lirp's in-memory `ConcurrentHashMap` lookup eliminates the SQL round-trip entirely — 35x faster than raw JDBC and 37x faster than JPA/Hibernate at all entity counts.

**add() throughput — write throughput:**

| Operation | lirp SqlRepository | Direct JDBC | JPA/Hibernate |
|-----------|-------------------|-------------|---------------|
| add() at 10K entities | 93,776 ops/s | 517,435 ops/s | 915 ops/s |
| vs JDBC | 5.5x lower (deferred) | baseline | 565x lower |
| vs JPA | ~100x faster | ~565x faster | baseline |

lirp's `add()` is deferred — no SQL I/O per call. JDBC's single-row autoCommit is faster per individual insert, but lirp writes all inserts as a single batch transaction, dramatically reducing I/O pressure at scale. JPA's per-call `persist()` + `flush()` is 565x slower than JDBC.

### SqlRepository vs JPA/Hibernate (add, 10K entities)

`SqlRepository` achieves ~85,921 ops/s vs Hibernate JPA ~915 ops/s — a ~94x throughput advantage — because lirp batches SQL writes while JPA commits a transaction per call.

### Initialization Time

| Entity Count | VolatileRepository | SqlRepository | JsonFileRepository |
|-------------|-------------------|---------------|-------------------|
| 1,000       | 12 ms             | 33 ms         | 24 ms             |
| 10,000      | 41 ms             | 81 ms         | 108 ms            |
| 50,000      | 212 ms            | 397 ms        | 408 ms            |

See the full head-to-head results in [Section 5 of the Performance Benchmarks wiki](https://github.com/octaviospain/lirp/wiki/Performance-Benchmarks#section-5--lirp-sqlrepository-vs-direct-jdbc-zero-overhead-baseline).

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

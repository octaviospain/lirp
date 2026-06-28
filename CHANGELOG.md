# Changelog

All notable changes to **LIRP (Lightweight Reactive Persistence)** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — 3.1.0

Small, additive improvements on top of the 3.0.0 release: per-bucket ordering for registry
projections, and a read-only query-diagnostics (`EXPLAIN`) surface over the in-memory query
planner. This release also completes the event-API cleanup started in 3.0.0 by removing the
deprecated `MutationEvent.Type.MUTATE(301)` enum value and the `ReactiveMutationEvent` class.
See [Migration from 3.0.0 to 3.1.0](#migration-from-300-to-310) for upgrade steps.

### Added

- **Per-bucket ordering for registry projections** — all four registry projection factories
  (`registryProjection`, `registryMultiKeyProjection`, `registryFxProjection`,
  `registryFxMultiKeyProjection`) and their `valueTransform` / two-phase `dataTransform`+`fxFactory`
  overloads now accept an optional `entryOrdering: Comparator<E>? = null` parameter placed
  immediately after `keyExtractor`. When a comparator is supplied, each per-key bucket `List<E>` is
  kept sorted incrementally — new entities are inserted at their comparator position using a
  stable upper-bound binary search, so equal elements retain arrival order (a newly arriving equal
  element is placed after the existing run of equal elements). An in-place mutation to a sort-key
  property re-positions the element within its bucket so the list remains ordered without a
  full re-sort. A `valueTransform` or `dataTransform` callback always receives the already-ordered
  `List<E>`; for FX variants, ordering is applied on the background thread before the FX-thread
  dispatch, consistent with the existing off-thread transform contract. Omitting the parameter
  (`null`) preserves the prior insertion order and is binary compatible.
  See [#278](https://github.com/octaviospain/lirp/issues/278).

- **Query diagnostics API** — two new extension functions on `Registry<K, E>` expose the query
  planner's internal decisions without altering query semantics.
  `explainQuery { }` mirrors the `query { }` DSL but returns a `QueryDiagnostic` snapshot
  instead of a result sequence; the result sequence is **never consumed**, making it safe to call
  without side-effects (equivalent to SQL `EXPLAIN`).
  `queryWithDiagnostics { }` executes the query eagerly and returns a `DiagnosedQuery<E>` pairing
  the result sequence with a `QueryDiagnostic`.
  Both functions are in `lirp-core` and visible via Kotlin extension syntax on any `Registry`.
  The following public types in `net.transgressoft.lirp.persistence.query` (module `lirp-api`) support them:
  - `QueryDiagnostic` — the full diagnostic snapshot: chosen `Strategy`, list of `IndexHit`s,
    post-filter predicate count, optional `ViaStrategy` for cross-aggregate queries, planning time
    in nanoseconds, and (for `queryWithDiagnostics` only) execution time in nanoseconds.
  - `DiagnosedQuery<T>` — pairs a result `Sequence<T>` with a `QueryDiagnostic`.
  - `IndexHit` — per-predicate breakdown: property name, index name, `IndexHitType`, and an optional
    selectivity whose meaning depends on the hit type — candidate count for `RANGE`, number of
    distinct probed values for `MULTI`, and `null` for `EXACT` (not computed at plan time).
  - `IndexHitType` — `EXACT` (equality lookup), `MULTI` (`isIn` set lookup), or `RANGE` (comparison).
  - `Strategy` — the planner's chosen retrieval mode: `INDEX_ONLY`, `INDEX_THEN_FILTER`, or
    `SCAN_ONLY`.
  See [#151](https://github.com/octaviospain/lirp/issues/151).

- **First-class soft delete** — `Repository.softDelete(entity)` marks an entity as deleted by
  setting its `deletedAt: Instant?` timestamp without removing the row from the database or
  evicting the entity from memory. `Repository.restore(entity)` clears `deletedAt` and returns
  the entity to the active set. Both operations are available only when the entity implements
  `MutableSoftDeletable` (which extends `SoftDeletable`). `remove()` remains the hard-delete
  primitive — soft delete is not an erasure operation.

  **Default-exclude reads** — after `softDelete`, the entity is excluded from every read surface
  by default: `findById`, registry iteration, `size()`, index lookups, and Query DSL results all
  omit soft-deleted entities. Use `includeDeleted()` or `onlyDeleted()` in the Query DSL to
  opt in:

  ```kotlin
  // Default: soft-deleted entities excluded
  val active = repo.query { where { Track::genre eq "rock" } }.toList()

  // Opt in to see all, including soft-deleted
  val all = repo.query {
      where { Track::genre eq "rock" }
      includeDeleted()
  }.toList()

  // Query only soft-deleted entities
  val deleted = repo.query { onlyDeleted() }.toList()
  ```

  **Events** — `softDelete` emits a `StandardCrudEvent.SoftDelete` and `restore` emits a
  `StandardCrudEvent.Restore`. Both are subtypes of the existing `CrudEvent` sealed hierarchy.
  The new `CrudEvent.Type` constants `SOFT_DELETE(410)` and `RESTORE(420)` accompany them.

  **KSP codegen** — for entities annotated with `@PersistenceMapping` that implement
  `SoftDeletable`, the KSP processor automatically injects a `deleted_at` column into the
  generated table definition so no manual table annotation is needed.

  **SQL and JSON persistence** — soft-delete persists as an UPDATE setting `deleted_at` to the
  current instant (the row is not deleted). Restore persists as an UPDATE setting `deleted_at`
  to `NULL`. Both operations pass through the existing debounced write pipeline, and entities
  with `@Version` have their version incremented on each operation.

  See [#283](https://github.com/octaviospain/lirp/issues/283).

- **Soft-delete visibility through cross-aggregate `via()` queries** — the `includeDeleted()` /
  `onlyDeleted()` Query DSL visibility flags are now honored for cross-aggregate `via()` queries,
  not just direct predicates. A single visibility mode defines one visible set that is applied to
  **both** parent enumeration and child resolution (strict mirror), across all four via operators
  (`anyMatch` / `allMatch` / `noneMatch` / `where`) and multi-`via` compounds combined with
  `and` / `or` / `not`. Under `onlyDeleted()`, an `allMatch` / `noneMatch` over a parent with no
  soft-deleted children matches vacuously (`true`), mirroring Kotlin stdlib semantics.

  ```kotlin
  // Soft-deleted playlists that reference a soft-deleted track priced over 100.
  val deletedHits = playlists.query {
      onlyDeleted()
      where { Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 } }
  }.toList()
  ```

  The default (no-flag) `via()` path is unchanged and remains active-only. This removes the
  temporary fail-fast guard that previously threw `IllegalStateException` when a `via()` query
  was combined with a visibility flag — that combination is now fully supported.
  See [#294](https://github.com/octaviospain/lirp/issues/294).

### Changed

- **`ViaStrategy` moved from `lirp-core` to `lirp-api`** — the enum class
  `net.transgressoft.lirp.persistence.query.ViaStrategy` is now declared in the `lirp-api`
  artifact. The **package name is unchanged**, so any source import of the fully-qualified name
  (`import net.transgressoft.lirp.persistence.query.ViaStrategy`) recompiles without modification.
  Consumers that depend on `lirp-core` at the binary level and reference `ViaStrategy` must
  ensure they also declare a `lirp-api` dependency; because `lirp-core` already pulls in
  `lirp-api` transitively, a standard Gradle or Maven dependency on `lirp-core` is sufficient
  with no extra `implementation("net.transgressoft:lirp-api:…")` line needed.
  See [#151](https://github.com/octaviospain/lirp/issues/151).

### Removed

- **`MutationEvent.Type.MUTATE(301)` enum value** — the `MUTATE` constant (code 301) is removed
  from the `MutationEvent.Type` enum. Code that switches or filters on this value must be updated.
  Use `PROPERTY_CHANGED(302)` for single-property mutations and `BATCH_CHANGED(303)` for
  multi-property batch mutations. The two-value enum is now `{ PROPERTY_CHANGED(302), BATCH_CHANGED(303) }`.

- **`ReactiveMutationEvent` class** — the `net.transgressoft.lirp.event.ReactiveMutationEvent`
  data class is removed entirely. It had no production emitter in 3.0.0 — its only purpose was to
  carry the now-deleted `MUTATE(301)` type. Replace any construction sites with `PropertyChanged`
  (for a single property) or `BatchChanged` (for a coordinated batch of property changes). Both
  types carry the same `entity` reference and are subtypes of `MutationEvent`.

- **Aggregate bubble-up events no longer report type 301** — `StandardAggregateMutationEvent`
  previously defaulted its `type` to `MUTATE(301)`. It now derives `type` from the wrapped child
  event: a `PropertyChanged` child yields `PROPERTY_CHANGED(302)`; a `BatchChanged` or collection
  child yields `BATCH_CHANGED(303)`. The event continues to be delivered; only its `type` value
  changes. Consumers that filter aggregate events on `event.type == MUTATE` must update the
  condition to match `PROPERTY_CHANGED` or `BATCH_CHANGED` as appropriate, or switch to inspecting
  `event.childEvent` directly (which carries the full per-property detail and is unaffected by
  this change).

### Fixed

- **`transaction { }` deadlock when the block switches threads** — a `transaction(repo) { ... }`
  whose body suspended and resumed on a different thread (for example via
  `withContext(Dispatchers.IO) { ... }`) could leak the repository's flush lock, causing the next
  `close()` (or a subsequent transaction) to block forever. The transaction critical section now
  runs on a dedicated thread-pinned dispatcher (`ReactiveScope.transactionDispatcher`) so the
  thread-owned flush lock is always released by its owning thread.
  See [#292](https://github.com/octaviospain/lirp/issues/292).

## Migration from 3.0.0 to 3.1.0

### Replace `MutationEvent.Type.MUTATE` references

Remove any `when`/`if` branches, filter calls, or constants that reference `MutationEvent.Type.MUTATE`.
Depending on whether the mutation is a single-property or batch change:

```kotlin
// Before
entity.subscribe { event ->
    if (event.type == MutationEvent.Type.MUTATE) { handle(event) }
}

// After — filter on the concrete subtype instead
entity.subscribe { event ->
    when (event) {
        is PropertyChanged<*, *, *> -> handleProperty(event)
        is BatchChanged<*, *>       -> handleBatch(event)
        else                        -> { /* aggregate or other */ }
    }
}
```

### Replace `ReactiveMutationEvent` construction

`ReactiveMutationEvent` is removed. Construct a `PropertyChanged` or `BatchChanged` event instead.
In tests, `PropertyChanged` is the direct replacement for single-property changes:

```kotlin
// Before
val event = ReactiveMutationEvent(entity)

// After — use PropertyChanged for a specific property change
val event = PropertyChanged(entity, property = MyEntity::title, oldValue = "A", newValue = "B")
// or BatchChanged for a coordinated multi-property mutation
val event = BatchChanged(entity, changes = listOf(FieldChange(MyEntity::title, "A", "B")))
```

### Update aggregate bubble-up event handling

If your code filters aggregate events by `event.type == MutationEvent.Type.MUTATE`, update it to
accept `PROPERTY_CHANGED` and `BATCH_CHANGED`:

```kotlin
// Before
aggregateEvents.filter { it.type == MutationEvent.Type.MUTATE }

// After
aggregateEvents.filter { it.type == MutationEvent.Type.PROPERTY_CHANGED
                      || it.type == MutationEvent.Type.BATCH_CHANGED }
// Or more idiomatically — inspect the child event directly:
aggregateEvents.filterIsInstance<AggregateMutationEvent<*, *>>()
    .filter { it.childEvent is PropertyChanged<*, *, *> || it.childEvent is BatchChanged<*, *> }
```

### Add `StandardCrudEvent.SoftDelete` and `Restore` branches to exhaustive `when` expressions

`StandardCrudEvent` is a sealed class. Adding `SoftDelete` and `Restore` subtypes is a
**source-breaking change** for any consumer code that has an exhaustive `when` expression over
the sealed hierarchy without an `else` branch. The Kotlin compiler will report the new cases as
missing and fail the build.

Add the two new branches (or an `else`) to every exhaustive `when` over `StandardCrudEvent` or
`CrudEvent`:

```kotlin
// Before — two-branch exhaustive when (fails to compile after this release)
repo.subscribe { event ->
    when (event) {
        is StandardCrudEvent.Create  -> handleCreate(event)
        is StandardCrudEvent.Update  -> handleUpdate(event)
        is StandardCrudEvent.Delete  -> handleDelete(event)
        is StandardCrudEvent.Conflict -> handleConflict(event)
    }
}

// After — add the new soft-delete branches
repo.subscribe { event ->
    when (event) {
        is StandardCrudEvent.Create    -> handleCreate(event)
        is StandardCrudEvent.Update    -> handleUpdate(event)
        is StandardCrudEvent.Delete    -> handleDelete(event)
        is StandardCrudEvent.Conflict  -> handleConflict(event)
        is StandardCrudEvent.SoftDelete -> handleSoftDelete(event)
        is StandardCrudEvent.Restore    -> handleRestore(event)
    }
}

// Alternatively — use an else branch to ignore events you do not handle
repo.subscribe { event ->
    when (event) {
        is StandardCrudEvent.Create -> handleCreate(event)
        is StandardCrudEvent.Update -> handleUpdate(event)
        else -> { /* ignore delete, conflict, soft-delete, restore */ }
    }
}
```

If your code does not use an exhaustive `when` (e.g. `if`/`else if` chains, or `when` with an
`else` branch), no change is required.

## [3.0.0] - 2026-06-23

The 3.0.0 line expands the projection API from a single aggregate-source, single-key,
identity-only map into a full matrix: aggregate **and** registry sources, single-key **and**
multi-key extractors, identity **and** value-transform outputs — for both the core and the
JavaFX layers. It also reorganises projection code into dedicated `projection` subpackages,
replaces the assignable change-callback slots with an additive, clobber-safe listener API,
and replaces the entity-clone–carrying mutation event with a typed, scalar-capturing event
hierarchy.
These are **breaking changes**; see [Migration from 2.x to 3.0.0](#migration-from-2x-to-30) below.

### Added

- **Built-in default `ColumnConverter`s for common JDK value types** — `PathColumnConverter`,
  `DurationColumnConverter`, `InstantColumnConverter`, `OffsetDateTimeColumnConverter`,
  `UriColumnConverter`, `UrlColumnConverter`, and `BigIntegerColumnConverter` ship in `lirp-api` and
  are bound automatically when an entity property's declared type matches — no
  `@PersistenceProperty(converter = …)` annotation and no registration required. `Path`, `URI`,
  `URL`, `Instant`, `OffsetDateTime`, and `BigInteger` map to a `TEXT` column; `Duration` maps to a
  `BIGINT` of nanoseconds. A consumer-supplied converter for the same type always wins, and
  `length` / `precision` / `scale` hints refine a default-converter column exactly as they do an
  explicit one. Types lirp already maps natively (`LocalDate`, `LocalDateTime`, `UUID`,
  `BigDecimal`) are intentionally excluded. The change is additive and backward compatible: existing
  hand-written converters keep working unchanged.
- **Typed mutation events** — `PropertyChanged<K, R, V>` and `BatchChanged<K, R>` are now the
  primary event types emitted when reactive properties change. Both carry immutable value scalars
  (`oldValue`, `newValue`, `property`, `versionAtMutation`, `oldIndexKey`, `newIndexKey`) captured
  synchronously at assignment time, guaranteeing deferred subscribers observe the correct values
  even when the entity is mutated again before the subscriber drains. `FieldChange<R, V>` is the
  per-field change carrier inside `BatchChanged`. The `MutationEvent.Type` enum gains
  `PROPERTY_CHANGED` (302) and `BATCH_CHANGED` (303).
- **KSP-free JSON persistence** — `JsonFileRepository` now serializes and reloads reactive entities
  whose module deliberately omits lirp-ksp. `LirpEntitySerializer` falls back to a reflection-based
  reactive-property accessor (reusing the delegate-registry silent-setter path, so no `--add-opens`
  is needed), and the redundant `_LirpRawInitializer` load-time guard is gone. Applying lirp-ksp
  still yields the zero-reflection direct-call path; the fallback only trades that for reflection on
  property getters.
- **Contextual serializers for non-`@Serializable` field types** — `LirpEntitySerializer` and the
  `lirpSerializer(sample, serializersModule)` factory now accept a `SerializersModule` and resolve the
  serializers for nested constructor-parameter and reactive-property field types through it. An entity
  whose field types are not `@Serializable` — including third-party or polymorphic types the consumer
  cannot annotate — can now be persisted by registering a contextual serializer for each such type
  instead of annotating the domain model. This gives the JSON path the same bring-your-own-mapping
  flexibility the SQL path already offers through `ColumnConverter`. The parameter defaults to an empty
  module, so existing callers are unaffected.
- **Construction-free SQL persistence of non-public entities** — `SqlRepository.loadFromStore` can
  now rebuild an entity whose primary constructor is `internal` or `private` — and therefore
  unreachable from a separate persistence module — without a public factory. A table definition opts
  in by implementing the new `RawConstructibleTableDef`: it supplies the constructor argument values
  via `constructorParams` and the entity's binary name via `entityClassName`, and leaves `fromRow`
  unused. Construction is delegated to a `LirpRawConstructor` co-located with the entity (resolved by
  the `_LirpRawConstructor` `Class.forName` convention, mirroring `_LirpRawInitializer`), which alone
  reaches the non-public constructor; remaining fields are still populated through
  `LirpRawInitializer`. This makes the SQL bulk-load path symmetric with the reflective JSON
  construction path for entities a persistence module cannot construct directly. KSP generation of
  `_LirpRawConstructor` is not yet provided — hand-author the class for now.
- **Registry-source projections** — `RegistryProjection` (core) and `RegistryFxProjection`
  (FX) project a `Registry`'s entities into buckets, complementing the existing aggregate-source
  projections. Registry-backed projections are `AutoCloseable` so their registry subscription can
  be released. A `SoftDeletable` contract lets soft-deleted entities be filtered out of buckets.
- **Per-bucket value-transform** — new 4-argument `projection` / `registryProjection`
  overloads return a derived `Map<PK, V>`, recomputing the transform only for the buckets affected
  by each delta. Registry-source transform factories return `CloseableProjection<PK, V>` (a
  `Map` that is also `AutoCloseable`). The corresponding FX types `TransformedFxProjection` /
  `TransformedRegistryFxProjection` expose an `ObservableMap<PK, V>` and run the transform off
  the FX thread.
- **Multi-key extractor projections** — `MultiKeyProjection` / `MultiKeyRegistryProjection`
  (core) and `FxMultiKeyProjection` / `RegistryFxMultiKeyProjection` (+ transformed variants,
  FX) place an entity into every bucket its `Collection<PK>` key-set extractor yields, using a
  reverse index and add-before-remove ordering so a key-set change never leaves an entity
  transiently absent from all buckets.
- **Observable value-transform projections** — `ObservableProjection<PK, V>` and
  `addOnEntriesChangedListener` are now available on all four core value-transform projection maps
  (`projection`, `registryProjection`, `multiKeyProjection`, `registryMultiKeyProjection`
  with a trailing `valueTransform` lambda) **and** on all four FX value-transform projection maps
  (`fxProjection`, `registryFxProjection`, `fxMultiKeyProjection`,
  `registryFxMultiKeyProjection` with a trailing `valueTransform` lambda). Each listener invocation
  receives a batched `List<ProjectionEntryChange<PK, V>>` carrying the old **and** new transformed
  value per key (add / replace / remove), so a consumer can drive a CRUD-style event stream directly
  from projection changes without maintaining its own diff cache. Registration replays the current
  entries as adds so a late subscriber observes full state. The four FX value-transform factories
  return `FxObservableProjection<PK, V>` — a single interface extending both JavaFX `ObservableMap`
  and `ObservableProjection` — so a caller keeps `addListener(MapChangeListener)` **and**
  `addOnEntriesChangedListener` without a cast, giving a single core-level listener API across both
  layers. Identity projection maps (the four
  non-value-transform variants) deliberately do not implement this interface — see the wiki for the
  rationale. The transform output type `V` on every value-transform factory is now constrained to
  `V : Any`, so the add/replace/remove encoding of `ProjectionEntryChange` cannot be confused with an
  absent key. See [#260](https://github.com/octaviospain/lirp/issues/260).
- **FX single-pulse batching** — the FX projection maps coalesce all bucket changes from one source
  event into a single `Platform.runLater` pulse, so bound UI controls never observe an intermediate
  inconsistent state.
- **Full FX factory matrix** — `FxProjectionFactories.kt` (Kotlin top-level functions) and the
  Java-facing `FxProjections` object expose every combination of aggregate/registry × single/
  multi-key × identity/transform.
- **`@InternalLirpApi`** — a `@RequiresOptIn(level = ERROR)` annotation marking the cross-module
  projection adapter SPI (e.g. `MultiKeyProjection.reconcile`). External Kotlin consumers must
  explicitly opt in; the surface carries no semantic-versioning guarantees.
- **Additive change listeners** — `addOnChangeListener` / `addOnBucketsChangedListener` on the
  projection maps register observers and return an `AutoCloseable` that deregisters the individual
  listener.
- **Configurable async error handler** — `LirpErrorHandler` (`fun interface`),
  `LirpErrorContext` (`data class`), and `LirpOperation` (`enum`) are new public types in
  `lirp-api`. Pass an `onError: LirpErrorHandler?` to `VolatileRepository`, `JsonFileRepository`,
  or `SqlRepository` to receive a callback whenever an async operation (event drain or debounced
  flush) catches an exception. The framework logs first, then invokes the handler; the handler
  observes but does not alter control flow. When `null`, behaviour is log-only — identical to
  previous versions. `LirpEventPublisher.subscribeAsync(action, onError)` adds a per-subscription
  independent error handler so individual subscribers can observe failures without routing them
  to the publisher-level handler.

- **Two-phase FX-safe value transform** — new `dataTransform` / `fxFactory` overloads on
  `fxProjection`, `registryFxProjection`, `fxMultiKeyProjection`, and
  `registryFxMultiKeyProjection` split bucket projection into an off-thread data-extraction
  step (`dataTransform`) and an on-FX-thread construction step (`fxFactory`), making it safe
  to build `SimpleSetProperty` / `ReadOnlyBooleanWrapper` values in the factory. A `fxFactory`
  failure is logged with the bucket key and skipped so the remaining buckets in the same pulse
  still flush. The existing single-`valueTransform` overloads are unaffected ([#256](https://github.com/octaviospain/lirp/issues/256)).

- **`@ToOneAggregate`** — new annotation for FK scalar properties that replaces the hand-written
  companion `val` pattern. Place `@ToOneAggregate(target = TargetClass::class, onDelete = …)` on a
  scalar property whose name ends in `Id` (e.g. `var labelId: Int?`). KSP generates a
  `_LirpToOneExtAccessor.kt` file containing an extension property (e.g. `release.label`) that
  navigates to the referenced entity via the bound `AggregateRefDelegate`. `bubbleUp = true` and all
  cascade modes (CASCADE, DETACH, RESTRICT, NONE) are supported identically to the old `@Aggregate`
  to-one pattern. `@ToOneAggregate` may also be placed on a single-entity `by aggregate { … }` /
  `by optionalAggregate { … }` delegate (no accessor is generated — the delegate `val` is the
  navigation member) for the case where the reference key is computed rather than a stored scalar;
  the scalar form above is the recommended default. See
  [GitHub #255](https://github.com/octaviospain/lirp/issues/255).

### Changed

- **`@Aggregate` renamed to `@ToManyAggregates`** — the existing annotation for collection-typed
  aggregate references is renamed. All call sites that used `@Aggregate` must be updated to
  `@ToManyAggregates`. The annotation's parameters (`bubbleUp`, `onDelete`) are unchanged.
  `@ToManyAggregates` is now **collection-only**: applying it to a single (non-collection) reference
  is a compile error directing you to `@ToOneAggregate`, making the to-one/to-many split
  self-enforcing. See [Migration](#migration-aggregate-to-tomanyaggregates) below.

### Removed

- **Single-entity `@Aggregate` (to-one) removed** — the two-declaration pattern (`var xId` scalar
  + `@Aggregate @PersistenceIgnore val x by optionalAggregate { xId }`) is replaced entirely by
  `@ToOneAggregate` placed directly on the persisted FK scalar. All single-entity `@Aggregate`
  usages must be migrated to `@ToOneAggregate`. See [Migration](#migration-toone-aggregate) below.

- **Core projection package** — all projection types moved from `net.transgressoft.lirp.persistence`
  to `net.transgressoft.lirp.persistence.projection`, and the `CoreFactories` file was renamed to
  `ProjectionFactories`.
- **FX projection package** — all FX projection map classes moved from
  `net.transgressoft.lirp.persistence.fx` to `net.transgressoft.lirp.persistence.fx.projection`.
  Scalar (`fxString`, …) and aggregate (`fxAggregateList`, `fxAggregateSet`) factories remain in
  `FxFactories` / `FxProperties`.
- **FX Java factory entry point** — the static projection factory methods moved from `FxProperties`
  to the new `FxProjections` object.
- **Projection `Map` suffix dropped** — every projection type and factory dropped its `Map` suffix
  (`ProjectionMap` → `Projection`, `RegistryProjectionMap` → `RegistryProjection`,
  `FxProjectionMap` → `FxProjection`, the `MultiKey*` / `Transformed*` variants, and the matching
  `projectionMap(…)` → `projection(…)` factory functions). See
  [Migration](#projection-types-and-factories-drop-the-map-suffix) below.

- **Entity-clone fields on mutation events** — `ReactiveMutationEvent.oldEntity` /
  `ReactiveMutationEvent.newEntity` have been removed. The two-arg constructor
  `ReactiveMutationEvent(oldEntity, newEntity)` is replaced by a single-arg form
  `ReactiveMutationEvent(entity)`. Subscribers that previously read `event.oldEntity` /
  `event.newEntity` must switch to `event as PropertyChanged<K, R, V>` and read `oldValue` /
  `newValue` directly.
- **`FxScalarPropertyDelegate.bindMutationCallback(Function1)`** — the single-argument form
  `callback: (() -> Unit) -> Unit` is replaced by the three-argument form
  `callback: (Any?, Any?, () -> Unit) -> Unit` that receives the captured `oldValue` and `newValue`.
  Custom `FxScalarPropertyDelegate` implementations must update their override signatures.
- **Assignable change-callback slots** — the `onChange` and `onBucketsChanged` mutable properties on
  the projection maps were removed in favour of the additive `addOnChangeListener` /
  `addOnBucketsChangedListener` methods. A consumer can observe projection changes but can no longer
  overwrite another subscriber's wiring.

## Migration from 2.x to 3.0

### Single-entity `@Aggregate` replaced by `@ToOneAggregate` {#migration-toone-aggregate}

The two-declaration pattern for single-entity FK references is replaced by a single annotation on
the persisted FK scalar. The `@Aggregate` annotation is removed; use `@ToOneAggregate` instead. The
`aggregate()` / `optionalAggregate()` delegate factories themselves are **retained** — if a
reference key must be computed rather than read from a stored scalar, keep the
`by aggregate { … }` / `by optionalAggregate { … }` delegate and annotate it with `@ToOneAggregate`.
Flattening to the scalar form below is the recommended default.

**Before (two declarations):**

```kotlin
var ownerCompanyId: UUID? by reactiveProperty(null)

@Aggregate(bubbleUp = false, onDelete = CascadeAction.DETACH)
@PersistenceIgnore
val ownerCompany by optionalAggregate<UUID, Company> { ownerCompanyId }
```

**After (one declaration):**

```kotlin
@ToOneAggregate(target = Company::class, onDelete = CascadeAction.DETACH)
var ownerCompanyId: UUID? by reactiveProperty(null)
// KSP generates: val Vehicle.ownerCompany: ReactiveEntityReference<UUID, Company>
```

For required (non-nullable) FK scalars:

```kotlin
// Before
var vehicleId: UUID by reactiveProperty(UUID(0, 0))

@Aggregate(onDelete = CascadeAction.CASCADE)
@PersistenceIgnore
val vehicle by aggregate<UUID, Vehicle> { vehicleId }

// After
@ToOneAggregate(target = Vehicle::class, onDelete = CascadeAction.CASCADE)
var vehicleId: UUID by reactiveProperty(UUID(0, 0))
```

**Navigation — import required.** The generated extension accessor `ownerCompany` lives in a
KSP-generated file and is not a member of the entity class. You must import it explicitly before
navigating:

```kotlin
import net.transgressoft.fleet.vehicle.ownerCompany  // generated — import required

val resolved = vehicle.ownerCompany.resolve()  // Optional<Company>
val refId    = vehicle.ownerCompany.referenceId  // UUID?
```

IDEs suggest the import automatically. If a call site fails to compile with
`Unresolved reference: ownerCompany`, add the import for the generated accessor.

**Migration steps:**

1. For every `@Aggregate @PersistenceIgnore val x by aggregate { xId }` or
   `@Aggregate @PersistenceIgnore val x by optionalAggregate { xId }`:
   - Remove the two-line companion declaration entirely.
   - Add `@ToOneAggregate(target = X::class, onDelete = ...)` on the scalar `var xId`.
   - Ensure the scalar name ends with `Id` — KSP enforces this at compile time. The generated
     extension accessor name is derived by stripping the `Id` suffix (`companyId` → `company`).
2. Replace all `import net.transgressoft.lirp.persistence.Aggregate` with
   `import net.transgressoft.lirp.persistence.ToOneAggregate` at single-entity call sites.
3. Remove `@PersistenceIgnore` imports that are no longer needed (the companion val is gone).
4. At every navigation call site (`entity.ownerCompany.resolve()` etc.), add the import for
   the KSP-generated extension accessor.

**Scalar naming rule:** The FK scalar must end with `Id` (case-exact). `companyId` → accessor
`company`; `liabilityInsuranceCompanyId` → accessor `liabilityInsuranceCompany`. A scalar without
the `Id` suffix produces a KSP compile error directing you to rename it.

**Nullability determines optional vs. required:** A nullable scalar (`UUID?`) produces an optional
reference — `resolve()` returns `Optional.empty()` when the FK is null. A non-nullable scalar
(`UUID`) produces a required reference. No explicit optionality parameter needed.

See [GitHub #255](https://github.com/octaviospain/lirp/issues/255) for background.

### `@Aggregate` renamed to `@ToManyAggregates` {#migration-aggregate-to-tomanyaggregates}

All `@Aggregate` annotations on collection-typed properties must be renamed to `@ToManyAggregates`.
The parameters are identical:

```kotlin
// Before
@Aggregate(bubbleUp = true, onDelete = CascadeAction.DETACH)
val tracks by aggregateList<Int, Track> { trackIds }

// After
@ToManyAggregates(bubbleUp = true, onDelete = CascadeAction.DETACH)
val tracks by aggregateList<Int, Track> { trackIds }
```

**Migration steps:**
1. Replace all `import net.transgressoft.lirp.persistence.Aggregate` with
   `import net.transgressoft.lirp.persistence.ToManyAggregates`.
2. Replace all `@Aggregate(…)` annotations with `@ToManyAggregates(…)` — parameters unchanged.

### `subscribe` is now synchronous by default

The unqualified `subscribe` overload now delivers callbacks **synchronously on the emitting thread**.
All former async `subscribe` call sites must be renamed to `subscribeAsync`:

```kotlin
// 2.x — subscribe was asynchronous (coroutine-based)
entity.subscribe { event: MutationEvent<K, R> ->
    searchService.reindex(event.entity)   // ran on a coroutine
}

// 3.0.0 — rename to subscribeAsync to keep coroutine delivery
entity.subscribeAsync { event: MutationEvent<K, R> ->
    searchService.reindex(event.entity)   // still runs on a coroutine
}

// 3.0.0 — use subscribe for fast in-process work that must be synchronous
entity.subscribe { event: MutationEvent<K, R> ->
    localCache.invalidate(event.entity.id)   // runs inline, no coroutine overhead
}
```

**Migration steps:**
1. Rename all former `subscribe { suspend lambda }` call sites to `subscribeAsync`.
2. Leave any callback that is fast and must run synchronously (cache updates, index maintenance,
   audit appends) as `subscribe`.
3. Use `subscribeAsync` for slow, blocking, or remote work that must not delay the emitting thread.

The `subscribeAsync(Consumer<E>)` Java overload replaces the former `subscribe(Consumer<E>)`.

The filtered `subscribeAsync(vararg eventTypes, Consumer<E>)` overload on reactive entities now
honors its event-type filter: a subscriber receives only the requested types. Previously the filter
argument was ignored and every event type was delivered. Java callers relying on the old
deliver-everything behavior must subscribe without a type filter to keep receiving all events.

### `changes` access arms the replay buffer (lazy bridge init)

In 2.x, the async bridge (Channel + SharedFlow) was created when the publisher was constructed.
In 3.0.0, it is created lazily on the first call to `subscribeAsync`, `changes`, or
`subscribe(Flow.Subscriber)`. Events emitted before the bridge is armed are **not** buffered.

```kotlin
// 2.x — replay worked without any boot step
val publisher = FlowEventPublisher<...>("id", PublisherConfig.withReplay(5))
publisher.emitAsync(event1)   // buffered automatically

// 3.0.0 — arm the bridge before emitting to enable replay buffering
val publisher = FlowEventPublisher<...>("id", PublisherConfig.withReplay(5))
publisher.changes   // arms the bridge; subsequent emits are buffered
publisher.emitAsync(event1)   // now buffered for replay
```

If replay buffering from startup is required, access `publisher.changes` once during initialization.

### Projection types and factories drop the `Map` suffix

Every projection type and factory lost its `Map` suffix: the type names now read `Projection`,
`RegistryProjection`, `MultiKeyProjection`, `FxProjection`, … (and the `Transformed*` variants),
and the factory functions read `projection(…)`, `registryProjection(…)`, `multiKeyProjection(…)`,
`fxProjection(…)`, … The tables below pair each 2.x name with its 3.0.0 replacement; the new
names also moved into the dedicated `…projection` subpackages.

### Core projection imports

| 2.x | 3.0.0 |
|-----|-------|
| `net.transgressoft.lirp.persistence.ProjectionMap` | `net.transgressoft.lirp.persistence.projection.Projection` |
| `net.transgressoft.lirp.persistence.projectionMap` | `net.transgressoft.lirp.persistence.projection.projection` |
| `net.transgressoft.lirp.persistence.CoreFactories` | `net.transgressoft.lirp.persistence.projection.ProjectionFactories` |

### FX projection imports (Kotlin)

| 2.x | 3.0.0 |
|-----|-------|
| `net.transgressoft.lirp.persistence.fx.FxProjectionMap` | `net.transgressoft.lirp.persistence.fx.projection.FxProjection` |
| `net.transgressoft.lirp.persistence.fx.RegistryFxProjectionMap` | `net.transgressoft.lirp.persistence.fx.projection.RegistryFxProjection` |
| `net.transgressoft.lirp.persistence.fx.fxProjectionMap` | `net.transgressoft.lirp.persistence.fx.projection.fxProjection` |
| `net.transgressoft.lirp.persistence.fx.registryFxProjectionMap` | `net.transgressoft.lirp.persistence.fx.projection.registryFxProjection` |

The new `*MultiKey*` and `Transformed*` FX types live in the same `…fx.projection` package.

### FX projection factories (Java)

| 2.x | 3.0.0 |
|-----|-------|
| `FxProperties.fxProjectionMap(…)` | `FxProjections.fxProjection(…)` |
| `FxProperties.registryFxProjectionMap(…)` | `FxProjections.registryFxProjection(…)` |

Scalar and aggregate factories (`FxProperties.fxString`, `FxProperties.fxAggregateList`, …) are
unchanged.

### Change callbacks

```kotlin
// 2.x — assignable single-subscriber slot (last writer wins)
projection.onChange = { map -> render(map) }
projection.onBucketsChanged = { keys -> invalidate(keys) }

// 3.0.0 — additive listeners; keep the handle to deregister
val changeReg = projection.addOnChangeListener { map -> render(map) }
val bucketReg = projection.addOnBucketsChangedListener { keys -> invalidate(keys) }
// later:
changeReg.close()
bucketReg.close()
```

Multiple listeners now coexist; registering a second listener no longer overwrites the first.

### Event API

Subscribers that inspected `event.oldEntity` / `event.newEntity` must pattern-match on the
concrete event type and read the captured value scalars instead:

```kotlin
// 2.x — entity clones on every mutation
entity.subscribe { event ->
    val before = event.oldEntity.title  // a full clone of the entity
    val after  = event.newEntity.title
}

// 3.0.0 — typed event with immutable captured scalars (no clone)
entity.subscribe { event ->
    when (event) {
        is PropertyChanged<*, *, *> -> {
            val pc = event as PropertyChanged<Int, MyEntity, String>
            val before = pc.oldValue   // captured at assignment time
            val after  = pc.newValue
            val prop   = pc.property.name
        }
        is BatchChanged<*, *> -> {
            val bc = event as BatchChanged<Int, MyEntity>
            bc.changes.forEach { change ->
                println("${change.property.name}: ${change.oldValue} -> ${change.newValue}")
            }
        }
        else -> { /* ReactiveMutationEvent or AggregateMutationEvent */ }
    }
}
```

`ReactiveMutationEvent` retains its single-entity form and `type = MUTATE (301)` for code paths
that do not need the per-property detail. `subscribeToMutations` now delivers `MutationEvent<K,R>`
(all direct mutation events) instead of the narrower `ReactiveMutationEvent<K,R>`.

**Custom `FxScalarPropertyDelegate` implementations** must update `bindMutationCallback`:

```kotlin
// 2.x
override fun bindMutationCallback(callback: (() -> Unit) -> Unit) { … }

// 3.0.0
override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) { … }
//                                            ^^^  ^^^  captured old/new values
```

**`CrudEvent.Type.UPDATE` subscribers — `oldEntities` is a post-mutation snapshot.** When a
`SqlRepository` re-publishes an entity mutation as a `StandardCrudEvent.Update`, the `oldEntities`
map holds a clone of the entity taken at re-publish time — i.e. the *current, already-mutated*
state, not the pre-mutation value. Because the per-mutation clone was removed from the hot path,
there is no pre-mutation snapshot to carry here. If you need the actual before/after of a specific
field, subscribe to the entity's `PropertyChanged` / `BatchChanged` events (which carry
`oldValue` / `newValue` captured at assignment time) rather than diffing the repository-level
`Update` event's `oldEntities` against `entities`.

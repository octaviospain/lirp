# Changelog

All notable changes to **LIRP (Lightweight Reactive Persistence)** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — 3.0.0

The 3.0.0 line expands the projection API from a single aggregate-source, single-key,
identity-only map into a full matrix: aggregate **and** registry sources, single-key **and**
multi-key extractors, identity **and** value-transform outputs — for both the core and the
JavaFX layers. It also reorganises projection code into dedicated `projection` subpackages,
replaces the assignable change-callback slots with an additive, clobber-safe listener API,
and replaces the entity-clone–carrying mutation event with a typed, scalar-capturing event
hierarchy.
These are **breaking changes**; see [Migration from 2.x to 3.0.0](#migration-from-2x-to-30) below.

### Added

- **Typed mutation events** — `PropertyChanged<K, R, V>` and `BatchChanged<K, R>` are now the
  primary event types emitted when reactive properties change. Both carry immutable value scalars
  (`oldValue`, `newValue`, `property`, `versionAtMutation`, `oldIndexKey`, `newIndexKey`) captured
  synchronously at assignment time, guaranteeing deferred subscribers observe the correct values
  even when the entity is mutated again before the subscriber drains. `FieldChange<R, V>` is the
  per-field change carrier inside `BatchChanged`. The `MutationEvent.Type` enum gains
  `PROPERTY_CHANGED` (302) and `BATCH_CHANGED` (303).
- **Registry-source projections** — `RegistryProjectionMap` (core) and `RegistryFxProjectionMap`
  (FX) project a `Registry`'s entities into buckets, complementing the existing aggregate-source
  projections. Registry-backed projections are `AutoCloseable` so their registry subscription can
  be released. A `SoftDeletable` contract lets soft-deleted entities be filtered out of buckets.
- **Per-bucket value-transform** — new 4-argument `projectionMap` / `registryProjectionMap`
  overloads return a derived `Map<PK, V>`, recomputing the transform only for the buckets affected
  by each delta. Registry-source transform factories return `CloseableProjectionMap<PK, V>` (a
  `Map` that is also `AutoCloseable`). The corresponding FX types `TransformedFxProjectionMap` /
  `TransformedRegistryFxProjectionMap` expose an `ObservableMap<PK, V>` and run the transform off
  the FX thread.
- **Multi-key extractor projections** — `MultiKeyProjectionMap` / `MultiKeyRegistryProjectionMap`
  (core) and `FxMultiKeyProjectionMap` / `RegistryFxMultiKeyProjectionMap` (+ transformed variants,
  FX) place an entity into every bucket its `Collection<PK>` key-set extractor yields, using a
  reverse index and add-before-remove ordering so a key-set change never leaves an entity
  transiently absent from all buckets.
- **FX single-pulse batching** — the FX projection maps coalesce all bucket changes from one source
  event into a single `Platform.runLater` pulse, so bound UI controls never observe an intermediate
  inconsistent state.
- **Full FX factory matrix** — `FxProjectionFactories.kt` (Kotlin top-level functions) and the
  Java-facing `FxProjections` object expose every combination of aggregate/registry × single/
  multi-key × identity/transform.
- **`@InternalLirpApi`** — a `@RequiresOptIn(level = ERROR)` annotation marking the cross-module
  projection adapter SPI (e.g. `MultiKeyProjectionMap.reconcile`). External Kotlin consumers must
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

### Added

- **Two-phase FX-safe value transform** — new `dataTransform` / `fxFactory` overloads on
  `fxProjectionMap`, `registryFxProjectionMap`, `fxMultiKeyProjectionMap`, and
  `registryFxMultiKeyProjectionMap` split bucket projection into an off-thread data-extraction
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

### Removed

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

### Core projection imports

| 2.x | 3.0.0 |
|-----|-------|
| `net.transgressoft.lirp.persistence.ProjectionMap` | `net.transgressoft.lirp.persistence.projection.ProjectionMap` |
| `net.transgressoft.lirp.persistence.projectionMap` | `net.transgressoft.lirp.persistence.projection.projectionMap` |
| `net.transgressoft.lirp.persistence.CoreFactories` | `net.transgressoft.lirp.persistence.projection.ProjectionFactories` |

### FX projection imports (Kotlin)

| 2.x | 3.0.0 |
|-----|-------|
| `net.transgressoft.lirp.persistence.fx.FxProjectionMap` | `net.transgressoft.lirp.persistence.fx.projection.FxProjectionMap` |
| `net.transgressoft.lirp.persistence.fx.RegistryFxProjectionMap` | `net.transgressoft.lirp.persistence.fx.projection.RegistryFxProjectionMap` |
| `net.transgressoft.lirp.persistence.fx.fxProjectionMap` | `net.transgressoft.lirp.persistence.fx.projection.fxProjectionMap` |
| `net.transgressoft.lirp.persistence.fx.registryFxProjectionMap` | `net.transgressoft.lirp.persistence.fx.projection.registryFxProjectionMap` |

The new `*MultiKey*` and `Transformed*` FX types live in the same `…fx.projection` package.

### FX projection factories (Java)

| 2.x | 3.0.0 |
|-----|-------|
| `FxProperties.fxProjectionMap(…)` | `FxProjections.fxProjectionMap(…)` |
| `FxProperties.registryFxProjectionMap(…)` | `FxProjections.registryFxProjectionMap(…)` |

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

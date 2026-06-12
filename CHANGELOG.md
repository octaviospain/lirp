# Changelog

All notable changes to **LIRP (Lightweight Reactive Persistence)** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — 3.0.0

The 3.0.0 line expands the projection API from a single aggregate-source, single-key,
identity-only map into a full matrix: aggregate **and** registry sources, single-key **and**
multi-key extractors, identity **and** value-transform outputs — for both the core and the
JavaFX layers. It also reorganises projection code into dedicated `projection` subpackages and
replaces the assignable change-callback slots with an additive, clobber-safe listener API.
These are **breaking changes**; see [Migration from 2.x to 3.0.0](#migration-from-2x-to-30) below.

### Added

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

### Changed

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

- **Assignable change-callback slots** — the `onChange` and `onBucketsChanged` mutable properties on
  the projection maps were removed in favour of the additive `addOnChangeListener` /
  `addOnBucketsChangedListener` methods. A consumer can observe projection changes but can no longer
  overwrite another subscriber's wiring.

## Migration from 2.x to 3.0

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

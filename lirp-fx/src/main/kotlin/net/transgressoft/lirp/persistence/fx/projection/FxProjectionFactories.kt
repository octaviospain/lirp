/******************************************************************************
 *     Copyright (C) 2025  Octavio Calleya Garcia                             *
 *                                                                            *
 *     This program is free software: you can redistribute it and/or modify   *
 *     it under the terms of the GNU General Public License as published by   *
 *     the Free Software Foundation, either version 3 of the License, or      *
 *     (at your option) any later version.                                    *
 *                                                                            *
 *     This program is distributed in the hope that it will be useful,        *
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of         *
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
 *     GNU General Public License for more details.                           *
 *                                                                            *
 *     You should have received a copy of the GNU General Public License      *
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>. *
 ******************************************************************************/

package net.transgressoft.lirp.persistence.fx.projection

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.persistence.FxObservableCollection
import net.transgressoft.lirp.persistence.Registry
import net.transgressoft.lirp.persistence.projection.ObservableProjection
import net.transgressoft.lirp.persistence.projection.ProjectionEntryChange
import javafx.collections.ObservableMap

/**
 * Creates a read-only [javafx.collections.ObservableMap] projection delegate that groups entities
 * from an [FxObservableCollection] source by a secondary key.
 *
 * The returned [FxProjection] lazily initializes on the first Kotlin `by`-delegation
 * access, subscribing to the source collection's change listener and building initial state
 * from the source's current contents. Subsequent adds and removes fire incremental
 * [javafx.collections.MapChangeListener.Change] notifications per affected bucket key.
 *
 * Keys are maintained in natural sorted order via a [java.util.concurrent.ConcurrentSkipListMap] backing.
 * The projected map is read-only; calling `put` or `remove` on it throws [UnsupportedOperationException].
 *
 * Usage:
 * ```kotlin
 * val audioItemsByAlbum by fxProjection(::audioItems, AudioItem::albumName)
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param sourceRef lambda returning the source [FxObservableCollection] (supports `::property` syntax)
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return a read-only projection map delegate incrementally updated from the source collection
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> fxProjection(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> PK,
    dispatchToFxThread: Boolean = true
): FxProjection<K, PK, E> =
    FxProjection(sourceRef, keyExtractor, dispatchToFxThread)

/**
 * Creates a read-only [ObservableMap] projection delegate that groups all entities from a [Registry]
 * by a secondary key, with bucket mutations dispatched to the JavaFX Application Thread.
 *
 * The returned [RegistryFxProjection] lazily initializes on the first [RegistryFxProjection.getValue] or
 * [RegistryFxProjection.addListener] call, building its initial state from the registry's current contents and
 * subscribing to incremental [net.transgressoft.lirp.event.CrudEvent] notifications. Soft-deleted entities are excluded.
 *
 * Usage:
 * ```kotlin
 * val itemsByAlbum: ObservableMap<String, List<AudioItem>> by registryFxProjection(trackRepo) { it.albumName }
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param registry the source registry to project
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return a read-only observable projection map delegate incrementally updated from the registry
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> registryFxProjection(
    registry: Registry<K, E>,
    keyExtractor: (E) -> PK,
    dispatchToFxThread: Boolean = true
): RegistryFxProjection<K, PK, E> =
    RegistryFxProjection(registry, keyExtractor, dispatchToFxThread)

/**
 * Creates a value-transformed read-only [ObservableMap] projection that groups entities from an
 * [FxObservableCollection] source by a secondary key, applying [valueTransform] to each bucket.
 *
 * The [valueTransform] is invoked on the **background thread** (the thread that delivers the
 * source collection's change event). The computed `V` is staged and mirrored into the map in a
 * single FX pulse. **[valueTransform] must be pure and thread-agnostic** — it must not access
 * JavaFX properties or nodes.
 *
 * Usage:
 * ```kotlin
 * val albumStats by fxProjection(::audioItems, AudioItem::albumName) { pk, items ->
 *     AlbumStats(pk, items.size)
 * }
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the transform output type
 * @param sourceRef lambda returning the source [FxObservableCollection]
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param valueTransform pure function that maps a non-empty `(PK, List<E>)` bucket to a non-null value `V`;
 *   `V` is constrained to be non-null so the add/replace/remove encoding of [ProjectionEntryChange] stays sound
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an [FxObservableProjection] grouping transformed bucket values by secondary key;
 *   its [addOnEntriesChangedListener][ObservableProjection.addOnEntriesChangedListener]
 *   emits per-key old/new transformed values in addition to the [ObservableMap]/[javafx.collections.MapChangeListener] surface
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V : Any> fxProjection(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> PK,
    valueTransform: (PK, List<E>) -> V,
    dispatchToFxThread: Boolean = true
): FxObservableProjection<PK, V> =
    TransformedFxProjection(sourceRef, keyExtractor, valueTransform, dispatchToFxThread)

/**
 * Creates a two-phase value-transformed read-only [ObservableMap] projection that groups entities
 * from an [FxObservableCollection] source by a secondary key.
 *
 * The transform is split into two phases:
 * - [dataTransform] runs on the **background thread** that delivers source events. It extracts a
 *   pure intermediate value `D` from the `(PK, List<E>)` bucket. **Must be thread-agnostic** — it
 *   must not read or write any JavaFX property or node.
 * - [fxFactory] runs on the **FX Application Thread** inside the flush pulse, once per changed
 *   bucket. It receives the bucket key and the intermediate `D` produced by [dataTransform], and
 *   constructs the final `V`. Safe to construct `SimpleSetProperty`, call `.bind(...)`, etc.
 *
 * If [fxFactory] throws for a bucket, the failure is logged (bucket key included) and that one
 * bucket is skipped; the remaining buckets in the same pulse still flush.
 *
 * Usage:
 * ```kotlin
 * val albumViews by fxProjection(
 *     ::audioItems,
 *     AudioItem::albumName,
 *     dataTransform = { pk, items -> items.map { it.title } },
 *     fxFactory = { pk, titles -> AlbumFxView(pk, titles) }
 * )
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param D the intermediate data type produced off-thread by [dataTransform]
 * @param V the transform output type constructed on the FX Application Thread by [fxFactory]
 * @param sourceRef lambda returning the source [FxObservableCollection]
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param dataTransform pure off-thread function that extracts an intermediate value from a non-empty bucket;
 *   must not access JavaFX observables
 * @param fxFactory FX-thread function that constructs the final non-null `V` from the bucket key and the
 *   intermediate value produced by [dataTransform]; safe to build JavaFX property bindings here. `V` is
 *   constrained to be non-null so the add/replace/remove encoding of [ProjectionEntryChange] stays sound
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an [FxObservableProjection] grouping transformed bucket values by secondary key;
 *   its [addOnEntriesChangedListener][ObservableProjection.addOnEntriesChangedListener]
 *   emits per-key old/new transformed values in addition to the [ObservableMap]/[javafx.collections.MapChangeListener] surface
 */
@Suppress("UNCHECKED_CAST")
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, D, V : Any> fxProjection(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> PK,
    dataTransform: (PK, List<E>) -> D,
    fxFactory: (PK, D) -> V,
    dispatchToFxThread: Boolean = true
): FxObservableProjection<PK, V> =
    TransformedFxProjection(
        sourceRef,
        keyExtractor,
        dataTransform as (PK, List<E>) -> Any?,
        fxFactory as (PK, Any?) -> V,
        dispatchToFxThread
    )

/**
 * Creates a value-transformed read-only [ObservableMap] projection that groups all entities from a
 * [Registry] by a secondary key, applying [valueTransform] to each bucket.
 *
 * The [valueTransform] is invoked on the **background thread** (the thread delivering the registry
 * event). The computed `V` is staged and mirrored into the map in a single FX pulse.
 * **[valueTransform] must be pure and thread-agnostic** — it must not access JavaFX properties or nodes.
 *
 * Usage:
 * ```kotlin
 * val albumStats: ObservableMap<String, AlbumStats> by registryFxProjection(trackRepo, { it.albumName }) { pk, items ->
 *     AlbumStats(pk, items.size)
 * }
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the transform output type
 * @param registry the source registry to project
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param valueTransform pure function that maps a non-empty `(PK, List<E>)` bucket to a non-null value `V`;
 *   `V` is constrained to be non-null so the add/replace/remove encoding of [ProjectionEntryChange] stays sound
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an [FxObservableProjection] grouping transformed bucket values by secondary key;
 *   its [addOnEntriesChangedListener][ObservableProjection.addOnEntriesChangedListener]
 *   emits per-key old/new transformed values in addition to the [ObservableMap]/[javafx.collections.MapChangeListener] surface
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V : Any> registryFxProjection(
    registry: Registry<K, E>,
    keyExtractor: (E) -> PK,
    valueTransform: (PK, List<E>) -> V,
    dispatchToFxThread: Boolean = true
): FxObservableProjection<PK, V> =
    TransformedRegistryFxProjection(registry, keyExtractor, valueTransform, dispatchToFxThread)

/**
 * Creates a two-phase value-transformed read-only [ObservableMap] projection that groups all entities
 * from a [Registry] by a secondary key.
 *
 * The transform is split into two phases:
 * - [dataTransform] runs on the **background thread** that delivers registry events. It extracts a
 *   pure intermediate value `D` from the `(PK, List<E>)` bucket. **Must be thread-agnostic** — it
 *   must not read or write any JavaFX property or node.
 * - [fxFactory] runs on the **FX Application Thread** inside the flush pulse, once per changed
 *   bucket. It receives the bucket key and the intermediate `D` produced by [dataTransform], and
 *   constructs the final `V`. Safe to construct `SimpleSetProperty`, call `.bind(...)`, etc.
 *
 * If [fxFactory] throws for a bucket, the failure is logged (bucket key included) and that one
 * bucket is skipped; the remaining buckets in the same pulse still flush.
 *
 * Usage:
 * ```kotlin
 * val albumViews: ObservableMap<String, AlbumFxView> by registryFxProjection(
 *     trackRepo,
 *     { it.albumName },
 *     dataTransform = { pk, items -> items.map { it.title } },
 *     fxFactory = { pk, titles -> AlbumFxView(pk, titles) }
 * )
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param D the intermediate data type produced off-thread by [dataTransform]
 * @param V the transform output type constructed on the FX Application Thread by [fxFactory]
 * @param registry the source registry to project
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param dataTransform pure off-thread function that extracts an intermediate value from a non-empty bucket;
 *   must not access JavaFX observables
 * @param fxFactory FX-thread function that constructs the final non-null `V` from the bucket key and the
 *   intermediate value produced by [dataTransform]; safe to build JavaFX property bindings here. `V` is
 *   constrained to be non-null so the add/replace/remove encoding of [ProjectionEntryChange] stays sound
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an [FxObservableProjection] grouping transformed bucket values by secondary key;
 *   its [addOnEntriesChangedListener][ObservableProjection.addOnEntriesChangedListener]
 *   emits per-key old/new transformed values in addition to the [ObservableMap]/[javafx.collections.MapChangeListener] surface
 */
@Suppress("UNCHECKED_CAST")
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, D, V : Any> registryFxProjection(
    registry: Registry<K, E>,
    keyExtractor: (E) -> PK,
    dataTransform: (PK, List<E>) -> D,
    fxFactory: (PK, D) -> V,
    dispatchToFxThread: Boolean = true
): FxObservableProjection<PK, V> =
    TransformedRegistryFxProjection(
        registry,
        keyExtractor,
        dataTransform as (PK, List<E>) -> Any?,
        fxFactory as (PK, Any?) -> V,
        dispatchToFxThread
    )

/**
 * Creates a read-only [ObservableMap] multi-key projection delegate that groups entities from an
 * [FxObservableCollection] source by multiple secondary keys.
 *
 * Each entity is placed into every bucket named by a key returned from [keyExtractor]. An entity
 * with genres `{Rock, Jazz}` appears in both the `"Rock"` and `"Jazz"` buckets.
 *
 * Uses a distinct factory name to avoid overload-resolution ambiguity with [fxProjection]
 * when the lambda returns `Collection<PK>` rather than a single `PK`.
 *
 * Usage:
 * ```kotlin
 * val itemsByGenre by fxMultiKeyProjection(::audioItems) { it.genres }
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type, must extend [ReactiveEntity]
 * @param sourceRef lambda returning the source [FxObservableCollection]
 * @param keyExtractor function that extracts the set of projection keys from an entity
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return a read-only multi-key projection map delegate incrementally updated from the source collection
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E> fxMultiKeyProjection(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    dispatchToFxThread: Boolean = true
): FxMultiKeyProjection<K, PK, E> where E : IdentifiableEntity<K>, E : ReactiveEntity<K, E> =
    FxMultiKeyProjection(sourceRef, keyExtractor, dispatchToFxThread)

/**
 * Creates a value-transformed read-only [ObservableMap] multi-key projection delegate that groups
 * entities from an [FxObservableCollection] source by multiple secondary keys, applying
 * [valueTransform] to each bucket.
 *
 * Each entity is placed into every bucket named by a key returned from [keyExtractor]. The
 * [valueTransform] is invoked on the **background thread** (the thread delivering source events).
 * **[valueTransform] must be pure and thread-agnostic** — it must not access JavaFX properties or nodes.
 *
 * Usage:
 * ```kotlin
 * val genreStats by fxMultiKeyProjection(::audioItems, { it.genres }) { pk, items ->
 *     GenreStats(pk, items.size)
 * }
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type, must extend [ReactiveEntity]
 * @param V the transform output type
 * @param sourceRef lambda returning the source [FxObservableCollection]
 * @param keyExtractor function that extracts the set of projection keys from an entity
 * @param valueTransform pure function that maps a non-empty `(PK, List<E>)` bucket to a non-null value `V`;
 *   `V` is constrained to be non-null so the add/replace/remove encoding of [ProjectionEntryChange] stays sound
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an [FxObservableProjection] grouping transformed bucket values by multiple secondary keys;
 *   its [addOnEntriesChangedListener][ObservableProjection.addOnEntriesChangedListener]
 *   emits per-key old/new transformed values in addition to the [ObservableMap]/[javafx.collections.MapChangeListener] surface
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E, V : Any> fxMultiKeyProjection(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    valueTransform: (PK, List<E>) -> V,
    dispatchToFxThread: Boolean = true
): FxObservableProjection<PK, V> where E : IdentifiableEntity<K>, E : ReactiveEntity<K, E> =
    TransformedFxMultiKeyProjection(sourceRef, keyExtractor, valueTransform, dispatchToFxThread)

/**
 * Creates a two-phase value-transformed read-only [ObservableMap] multi-key projection delegate that
 * groups entities from an [FxObservableCollection] source by multiple secondary keys.
 *
 * Each entity is placed into every bucket named by a key returned from [keyExtractor]. The transform
 * is split into two phases:
 * - [dataTransform] runs on the **background thread** that delivers source events. It extracts a
 *   pure intermediate value `D` from the `(PK, List<E>)` bucket. **Must be thread-agnostic** — it
 *   must not read or write any JavaFX property or node.
 * - [fxFactory] runs on the **FX Application Thread** inside the flush pulse, once per changed
 *   bucket. It receives the bucket key and the intermediate `D` produced by [dataTransform], and
 *   constructs the final `V`. Safe to construct `SimpleSetProperty`, call `.bind(...)`, etc.
 *
 * If [fxFactory] throws for a bucket, the failure is logged (bucket key included) and that one
 * bucket is skipped; the remaining buckets in the same pulse still flush.
 *
 * Usage:
 * ```kotlin
 * val genreViews by fxMultiKeyProjection(
 *     ::audioItems,
 *     { it.genres },
 *     dataTransform = { pk, items -> items.map { it.title } },
 *     fxFactory = { pk, titles -> GenreFxView(pk, titles) }
 * )
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type, must extend [ReactiveEntity]
 * @param D the intermediate data type produced off-thread by [dataTransform]
 * @param V the transform output type constructed on the FX Application Thread by [fxFactory]
 * @param sourceRef lambda returning the source [FxObservableCollection]
 * @param keyExtractor function that extracts the set of projection keys from an entity
 * @param dataTransform pure off-thread function that extracts an intermediate value from a non-empty bucket;
 *   must not access JavaFX observables
 * @param fxFactory FX-thread function that constructs the final non-null `V` from the bucket key and the
 *   intermediate value produced by [dataTransform]; safe to build JavaFX property bindings here. `V` is
 *   constrained to be non-null so the add/replace/remove encoding of [ProjectionEntryChange] stays sound
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an [FxObservableProjection] grouping transformed bucket values by multiple secondary keys;
 *   its [addOnEntriesChangedListener][ObservableProjection.addOnEntriesChangedListener]
 *   emits per-key old/new transformed values in addition to the [ObservableMap]/[javafx.collections.MapChangeListener] surface
 */
@Suppress("UNCHECKED_CAST")
fun <K : Comparable<K>, PK : Comparable<PK>, E, D, V : Any> fxMultiKeyProjection(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    dataTransform: (PK, List<E>) -> D,
    fxFactory: (PK, D) -> V,
    dispatchToFxThread: Boolean = true
): FxObservableProjection<PK, V> where E : IdentifiableEntity<K>, E : ReactiveEntity<K, E> =
    TransformedFxMultiKeyProjection(
        sourceRef,
        keyExtractor,
        dataTransform as (PK, List<E>) -> Any?,
        fxFactory as (PK, Any?) -> V,
        dispatchToFxThread
    )

/**
 * Creates a read-only [ObservableMap] multi-key projection delegate that groups all entities from a
 * [Registry] by multiple secondary keys.
 *
 * Each entity is placed into every bucket named by a key returned from [keyExtractor]. In-place
 * key-set changes are handled natively with add-before-remove ordering — no per-entity subscriptions
 * are needed.
 *
 * Uses a distinct factory name to avoid overload-resolution ambiguity with [registryFxProjection]
 * when the lambda returns `Collection<PK>` rather than a single `PK`.
 *
 * Usage:
 * ```kotlin
 * val itemsByGenre: ObservableMap<String, List<AudioItem>> by registryFxMultiKeyProjection(trackRepo) { it.genres }
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param registry the source registry whose entities are projected
 * @param keyExtractor function that extracts the set of projection keys from an entity
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return a read-only multi-key projection map delegate incrementally updated from the registry
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> registryFxMultiKeyProjection(
    registry: Registry<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    dispatchToFxThread: Boolean = true
): RegistryFxMultiKeyProjection<K, PK, E> =
    RegistryFxMultiKeyProjection(registry, keyExtractor, dispatchToFxThread)

/**
 * Creates a value-transformed read-only [ObservableMap] multi-key projection delegate that groups
 * all entities from a [Registry] by multiple secondary keys, applying [valueTransform] to each bucket.
 *
 * Each entity is placed into every bucket named by a key returned from [keyExtractor]. The
 * [valueTransform] is invoked on the **background thread** (the thread delivering registry events).
 * **[valueTransform] must be pure and thread-agnostic** — it must not access JavaFX properties or nodes.
 *
 * Usage:
 * ```kotlin
 * val genreStats: ObservableMap<String, GenreStats> by registryFxMultiKeyProjection(trackRepo, { it.genres }) { pk, items ->
 *     GenreStats(pk, items.size)
 * }
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the transform output type
 * @param registry the source registry whose entities are projected
 * @param keyExtractor function that extracts the set of projection keys from an entity
 * @param valueTransform pure function that maps a non-empty `(PK, List<E>)` bucket to a non-null value `V`;
 *   `V` is constrained to be non-null so the add/replace/remove encoding of [ProjectionEntryChange] stays sound
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an [FxObservableProjection] grouping transformed bucket values by multiple secondary keys;
 *   its [addOnEntriesChangedListener][ObservableProjection.addOnEntriesChangedListener]
 *   emits per-key old/new transformed values in addition to the [ObservableMap]/[javafx.collections.MapChangeListener] surface
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V : Any> registryFxMultiKeyProjection(
    registry: Registry<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    valueTransform: (PK, List<E>) -> V,
    dispatchToFxThread: Boolean = true
): FxObservableProjection<PK, V> =
    TransformedRegistryFxMultiKeyProjection(registry, keyExtractor, valueTransform, dispatchToFxThread)

/**
 * Creates a two-phase value-transformed read-only [ObservableMap] multi-key projection delegate that
 * groups all entities from a [Registry] by multiple secondary keys.
 *
 * Each entity is placed into every bucket named by a key returned from [keyExtractor]. The transform
 * is split into two phases:
 * - [dataTransform] runs on the **background thread** that delivers registry events. It extracts a
 *   pure intermediate value `D` from the `(PK, List<E>)` bucket. **Must be thread-agnostic** — it
 *   must not read or write any JavaFX property or node.
 * - [fxFactory] runs on the **FX Application Thread** inside the flush pulse, once per changed
 *   bucket. It receives the bucket key and the intermediate `D` produced by [dataTransform], and
 *   constructs the final `V`. Safe to construct `SimpleSetProperty`, call `.bind(...)`, etc.
 *
 * If [fxFactory] throws for a bucket, the failure is logged (bucket key included) and that one
 * bucket is skipped; the remaining buckets in the same pulse still flush.
 *
 * Usage:
 * ```kotlin
 * val genreViews: ObservableMap<String, GenreFxView> by registryFxMultiKeyProjection(
 *     trackRepo,
 *     { it.genres },
 *     dataTransform = { pk, items -> items.map { it.title } },
 *     fxFactory = { pk, titles -> GenreFxView(pk, titles) }
 * )
 * ```
 *
 * @param K the entity ID type
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param D the intermediate data type produced off-thread by [dataTransform]
 * @param V the transform output type constructed on the FX Application Thread by [fxFactory]
 * @param registry the source registry whose entities are projected
 * @param keyExtractor function that extracts the set of projection keys from an entity
 * @param dataTransform pure off-thread function that extracts an intermediate value from a non-empty bucket;
 *   must not access JavaFX observables
 * @param fxFactory FX-thread function that constructs the final non-null `V` from the bucket key and the
 *   intermediate value produced by [dataTransform]; safe to build JavaFX property bindings here. `V` is
 *   constrained to be non-null so the add/replace/remove encoding of [ProjectionEntryChange] stays sound
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an [FxObservableProjection] grouping transformed bucket values by multiple secondary keys;
 *   its [addOnEntriesChangedListener][ObservableProjection.addOnEntriesChangedListener]
 *   emits per-key old/new transformed values in addition to the [ObservableMap]/[javafx.collections.MapChangeListener] surface
 */
@Suppress("UNCHECKED_CAST")
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, D, V : Any> registryFxMultiKeyProjection(
    registry: Registry<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    dataTransform: (PK, List<E>) -> D,
    fxFactory: (PK, D) -> V,
    dispatchToFxThread: Boolean = true
): FxObservableProjection<PK, V> =
    TransformedRegistryFxMultiKeyProjection(
        registry,
        keyExtractor,
        dataTransform as (PK, List<E>) -> Any?,
        fxFactory as (PK, Any?) -> V,
        dispatchToFxThread
    )
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
import javafx.collections.ObservableMap

/**
 * Creates a read-only [javafx.collections.ObservableMap] projection delegate that groups entities
 * from an [FxObservableCollection] source by a secondary key.
 *
 * The returned [FxProjectionMap] lazily initializes on the first Kotlin `by`-delegation
 * access, subscribing to the source collection's change listener and building initial state
 * from the source's current contents. Subsequent adds and removes fire incremental
 * [javafx.collections.MapChangeListener.Change] notifications per affected bucket key.
 *
 * Keys are maintained in natural sorted order via a [java.util.concurrent.ConcurrentSkipListMap] backing.
 * The projected map is read-only; calling `put` or `remove` on it throws [UnsupportedOperationException].
 *
 * Usage:
 * ```kotlin
 * val audioItemsByAlbum by fxProjectionMap(::audioItems, AudioItem::albumName)
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
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> fxProjectionMap(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> PK,
    dispatchToFxThread: Boolean = true
): FxProjectionMap<K, PK, E> =
    FxProjectionMap(sourceRef, keyExtractor, dispatchToFxThread)

/**
 * Creates a read-only [ObservableMap] projection delegate that groups all entities from a [Registry]
 * by a secondary key, with bucket mutations dispatched to the JavaFX Application Thread.
 *
 * The returned [RegistryFxProjectionMap] lazily initializes on the first [RegistryFxProjectionMap.getValue] or
 * [RegistryFxProjectionMap.addListener] call, building its initial state from the registry's current contents and
 * subscribing to incremental [net.transgressoft.lirp.event.CrudEvent] notifications. Soft-deleted entities are excluded.
 *
 * Usage:
 * ```kotlin
 * val itemsByAlbum: ObservableMap<String, List<AudioItem>> by registryFxProjectionMap(trackRepo) { it.albumName }
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
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> registryFxProjectionMap(
    registry: Registry<K, E>,
    keyExtractor: (E) -> PK,
    dispatchToFxThread: Boolean = true
): RegistryFxProjectionMap<K, PK, E> =
    RegistryFxProjectionMap(registry, keyExtractor, dispatchToFxThread)

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
 * val albumStats by fxProjectionMap(::audioItems, AudioItem::albumName) { pk, items ->
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
 * @param valueTransform pure function that maps a non-empty `(PK, List<E>)` bucket to a value `V`
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return a read-only observable projection map delegate that emits transformed bucket values
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> fxProjectionMap(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> PK,
    valueTransform: (PK, List<E>) -> V,
    dispatchToFxThread: Boolean = true
): TransformedFxProjectionMap<K, PK, E, V> =
    TransformedFxProjectionMap(sourceRef, keyExtractor, valueTransform, dispatchToFxThread)

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
 * val albumStats: ObservableMap<String, AlbumStats> by registryFxProjectionMap(trackRepo, { it.albumName }) { pk, items ->
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
 * @param valueTransform pure function that maps a non-empty `(PK, List<E>)` bucket to a value `V`
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return a read-only observable projection map delegate that emits transformed bucket values
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> registryFxProjectionMap(
    registry: Registry<K, E>,
    keyExtractor: (E) -> PK,
    valueTransform: (PK, List<E>) -> V,
    dispatchToFxThread: Boolean = true
): TransformedRegistryFxProjectionMap<K, PK, E, V> =
    TransformedRegistryFxProjectionMap(registry, keyExtractor, valueTransform, dispatchToFxThread)

/**
 * Creates a read-only [ObservableMap] multi-key projection delegate that groups entities from an
 * [FxObservableCollection] source by multiple secondary keys.
 *
 * Each entity is placed into every bucket named by a key returned from [keyExtractor]. An entity
 * with genres `{Rock, Jazz}` appears in both the `"Rock"` and `"Jazz"` buckets.
 *
 * Uses a distinct factory name to avoid overload-resolution ambiguity with [fxProjectionMap]
 * when the lambda returns `Collection<PK>` rather than a single `PK`.
 *
 * Usage:
 * ```kotlin
 * val itemsByGenre by fxMultiKeyProjectionMap(::audioItems) { it.genres }
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
fun <K : Comparable<K>, PK : Comparable<PK>, E> fxMultiKeyProjectionMap(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    dispatchToFxThread: Boolean = true
): FxMultiKeyProjectionMap<K, PK, E> where E : IdentifiableEntity<K>, E : ReactiveEntity<K, E> =
    FxMultiKeyProjectionMap(sourceRef, keyExtractor, dispatchToFxThread)

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
 * val genreStats by fxMultiKeyProjectionMap(::audioItems, { it.genres }) { pk, items ->
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
 * @param valueTransform pure function that maps a non-empty `(PK, List<E>)` bucket to a value `V`
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return a read-only multi-key projection map delegate that emits transformed bucket values
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E, V> fxMultiKeyProjectionMap(
    sourceRef: () -> FxObservableCollection<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    valueTransform: (PK, List<E>) -> V,
    dispatchToFxThread: Boolean = true
): TransformedFxMultiKeyProjectionMap<K, PK, E, V> where E : IdentifiableEntity<K>, E : ReactiveEntity<K, E> =
    TransformedFxMultiKeyProjectionMap(sourceRef, keyExtractor, valueTransform, dispatchToFxThread)

/**
 * Creates a read-only [ObservableMap] multi-key projection delegate that groups all entities from a
 * [Registry] by multiple secondary keys.
 *
 * Each entity is placed into every bucket named by a key returned from [keyExtractor]. In-place
 * key-set changes are handled natively with add-before-remove ordering — no per-entity subscriptions
 * are needed.
 *
 * Uses a distinct factory name to avoid overload-resolution ambiguity with [registryFxProjectionMap]
 * when the lambda returns `Collection<PK>` rather than a single `PK`.
 *
 * Usage:
 * ```kotlin
 * val itemsByGenre: ObservableMap<String, List<AudioItem>> by registryFxMultiKeyProjectionMap(trackRepo) { it.genres }
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
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> registryFxMultiKeyProjectionMap(
    registry: Registry<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    dispatchToFxThread: Boolean = true
): RegistryFxMultiKeyProjectionMap<K, PK, E> =
    RegistryFxMultiKeyProjectionMap(registry, keyExtractor, dispatchToFxThread)

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
 * val genreStats: ObservableMap<String, GenreStats> by registryFxMultiKeyProjectionMap(trackRepo, { it.genres }) { pk, items ->
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
 * @param valueTransform pure function that maps a non-empty `(PK, List<E>)` bucket to a value `V`
 * @param dispatchToFxThread when `true` (default), dispatches notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return a read-only multi-key projection map delegate that emits transformed bucket values
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> registryFxMultiKeyProjectionMap(
    registry: Registry<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    valueTransform: (PK, List<E>) -> V,
    dispatchToFxThread: Boolean = true
): TransformedRegistryFxMultiKeyProjectionMap<K, PK, E, V> =
    TransformedRegistryFxMultiKeyProjectionMap(registry, keyExtractor, valueTransform, dispatchToFxThread)
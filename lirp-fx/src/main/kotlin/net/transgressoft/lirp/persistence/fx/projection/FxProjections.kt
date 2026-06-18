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

/**
 * Java-facing static factory methods for all lirp-fx projection map types.
 *
 * Kotlin callers prefer the top-level functions in this package (`fxProjection`,
 * `registryFxProjection`, `fxMultiKeyProjection`, `registryFxMultiKeyProjection`).
 * Java callers use this object's `@JvmStatic` methods to avoid Kotlin top-level function call syntax.
 *
 * Scalar and collection delegates ([net.transgressoft.lirp.persistence.fx.FxProperties]) are kept
 * separate, paralleling the split between core's projection factories and entity factories.
 */
object FxProjections {

    /**
     * Creates an [FxProjectionMap] that groups entities from any [FxObservableCollection]
     * source by a projection key.
     *
     * @param K the entity ID type
     * @param PK the projection key type
     * @param E the entity type
     * @param sourceRef lambda returning the source collection
     * @param keyExtractor function extracting the projection key from an entity
     * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
     */
    @JvmStatic
    @JvmOverloads
    fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> fxProjection(
        sourceRef: () -> FxObservableCollection<K, E>,
        keyExtractor: (E) -> PK,
        dispatchToFxThread: Boolean = true
    ): FxProjectionMap<K, PK, E> = FxProjectionMap(sourceRef, keyExtractor, dispatchToFxThread)

    /**
     * Creates a value-transformed [TransformedFxProjectionMap] that groups entities from an
     * [FxObservableCollection] source by a secondary key, applying [valueTransform] to each bucket.
     *
     * @param K the entity ID type
     * @param PK the projection key type
     * @param E the entity type
     * @param V the transform output type
     * @param sourceRef lambda returning the source collection
     * @param keyExtractor function extracting the projection key from an entity
     * @param valueTransform pure function mapping a non-empty bucket to its display value
     * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
     */
    @JvmStatic
    fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> fxProjection(
        sourceRef: () -> FxObservableCollection<K, E>,
        keyExtractor: (E) -> PK,
        valueTransform: (PK, List<E>) -> V,
        dispatchToFxThread: Boolean = true
    ): TransformedFxProjectionMap<K, PK, E, V> =
        TransformedFxProjectionMap(sourceRef, keyExtractor, valueTransform, dispatchToFxThread)

    /**
     * Creates a [RegistryFxProjectionMap] that groups all entities from a [Registry] by a secondary key.
     *
     * @param K the entity ID type
     * @param PK the projection key type
     * @param E the entity type
     * @param registry the source registry to project
     * @param keyExtractor function extracting the projection key from an entity
     * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
     */
    @JvmStatic
    @JvmOverloads
    fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> registryFxProjection(
        registry: Registry<K, E>,
        keyExtractor: (E) -> PK,
        dispatchToFxThread: Boolean = true
    ): RegistryFxProjectionMap<K, PK, E> =
        RegistryFxProjectionMap(registry, keyExtractor, dispatchToFxThread)

    /**
     * Creates a value-transformed [TransformedRegistryFxProjectionMap] that groups all entities from a
     * [Registry] by a secondary key, applying [valueTransform] to each bucket.
     *
     * @param K the entity ID type
     * @param PK the projection key type
     * @param E the entity type
     * @param V the transform output type
     * @param registry the source registry to project
     * @param keyExtractor function extracting the projection key from an entity
     * @param valueTransform pure function mapping a non-empty bucket to its display value
     * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
     */
    @JvmStatic
    fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> registryFxProjection(
        registry: Registry<K, E>,
        keyExtractor: (E) -> PK,
        valueTransform: (PK, List<E>) -> V,
        dispatchToFxThread: Boolean = true
    ): TransformedRegistryFxProjectionMap<K, PK, E, V> =
        TransformedRegistryFxProjectionMap(registry, keyExtractor, valueTransform, dispatchToFxThread)

    /**
     * Creates a [FxMultiKeyProjectionMap] that groups entities from an [FxObservableCollection] source
     * by multiple secondary keys. Each entity appears in every bucket named by the keys it returns.
     *
     * @param K the entity ID type
     * @param PK the projection key type
     * @param E the entity type, must extend [ReactiveEntity]
     * @param sourceRef lambda returning the source collection
     * @param keyExtractor function that extracts the set of projection keys from an entity
     * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
     */
    @JvmStatic
    @JvmOverloads
    fun <K : Comparable<K>, PK : Comparable<PK>, E> fxMultiKeyProjection(
        sourceRef: () -> FxObservableCollection<K, E>,
        keyExtractor: (E) -> Collection<PK>,
        dispatchToFxThread: Boolean = true
    ): FxMultiKeyProjectionMap<K, PK, E> where E : IdentifiableEntity<K>, E : ReactiveEntity<K, E> =
        FxMultiKeyProjectionMap(sourceRef, keyExtractor, dispatchToFxThread)

    /**
     * Creates a value-transformed [TransformedFxMultiKeyProjectionMap] that groups entities from an
     * [FxObservableCollection] source by multiple secondary keys, applying [valueTransform] to each bucket.
     *
     * @param K the entity ID type
     * @param PK the projection key type
     * @param E the entity type, must extend [ReactiveEntity]
     * @param V the transform output type
     * @param sourceRef lambda returning the source collection
     * @param keyExtractor function that extracts the set of projection keys from an entity
     * @param valueTransform pure function mapping a non-empty bucket to its display value
     * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
     */
    @JvmStatic
    fun <K : Comparable<K>, PK : Comparable<PK>, E, V> fxMultiKeyProjection(
        sourceRef: () -> FxObservableCollection<K, E>,
        keyExtractor: (E) -> Collection<PK>,
        valueTransform: (PK, List<E>) -> V,
        dispatchToFxThread: Boolean = true
    ): TransformedFxMultiKeyProjectionMap<K, PK, E, V> where E : IdentifiableEntity<K>, E : ReactiveEntity<K, E> =
        TransformedFxMultiKeyProjectionMap(sourceRef, keyExtractor, valueTransform, dispatchToFxThread)

    /**
     * Creates a [RegistryFxMultiKeyProjectionMap] that groups all entities from a [Registry] by multiple
     * secondary keys. Each entity appears in every bucket named by the keys it returns.
     *
     * @param K the entity ID type
     * @param PK the projection key type
     * @param E the entity type
     * @param registry the source registry whose entities are projected
     * @param keyExtractor function that extracts the set of projection keys from an entity
     * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
     */
    @JvmStatic
    @JvmOverloads
    fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> registryFxMultiKeyProjection(
        registry: Registry<K, E>,
        keyExtractor: (E) -> Collection<PK>,
        dispatchToFxThread: Boolean = true
    ): RegistryFxMultiKeyProjectionMap<K, PK, E> =
        RegistryFxMultiKeyProjectionMap(registry, keyExtractor, dispatchToFxThread)

    /**
     * Creates a value-transformed [TransformedRegistryFxMultiKeyProjectionMap] that groups all entities
     * from a [Registry] by multiple secondary keys, applying [valueTransform] to each bucket.
     *
     * @param K the entity ID type
     * @param PK the projection key type
     * @param E the entity type
     * @param V the transform output type
     * @param registry the source registry whose entities are projected
     * @param keyExtractor function that extracts the set of projection keys from an entity
     * @param valueTransform pure function mapping a non-empty bucket to its display value
     * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
     */
    @JvmStatic
    fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> registryFxMultiKeyProjection(
        registry: Registry<K, E>,
        keyExtractor: (E) -> Collection<PK>,
        valueTransform: (PK, List<E>) -> V,
        dispatchToFxThread: Boolean = true
    ): TransformedRegistryFxMultiKeyProjectionMap<K, PK, E, V> =
        TransformedRegistryFxMultiKeyProjectionMap(registry, keyExtractor, valueTransform, dispatchToFxThread)
}
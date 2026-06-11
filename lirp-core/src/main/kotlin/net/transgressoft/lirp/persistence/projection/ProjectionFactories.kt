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

package net.transgressoft.lirp.persistence.projection

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.Registry

/**
 * Creates a read-only projection that groups entities from a source collection by a secondary key.
 *
 * The returned [ProjectionMap] lazily initializes on the first Kotlin `by`-delegation access,
 * building its initial state from the source's current contents. When the source is a
 * [MutableAggregateList] or [MutableAggregateSet], subsequent mutations are reflected
 * automatically without any manual notification. For other [AggregateCollectionRef] implementations,
 * only the initial snapshot is captured.
 *
 * Keys are maintained in natural sorted order via a [java.util.concurrent.ConcurrentSkipListMap] backing. The projected
 * map is read-only; mutations must flow through the source collection.
 *
 * Usage:
 * ```kotlin
 * val audioItemsByTitle by projectionMap(::audioItems) { it.title }
 * ```
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param sourceRef lambda returning the source collection (supports `::property` syntax)
 * @param keyExtractor trailing-lambda grouping function that extracts the projection key from an entity
 * @return a [ProjectionMap] delegate grouping entities by [keyExtractor]
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> projectionMap(
    sourceRef: () -> AggregateCollectionRef<K, E>,
    keyExtractor: (E) -> PK
): ProjectionMap<K, PK, E> = ProjectionMap(sourceRef, keyExtractor)

/**
 * Creates a read-only projection that groups all entities from a [Registry] by a secondary key.
 *
 * The returned [RegistryProjectionMap] lazily initializes on the first map access,
 * building its initial state from the registry's current contents and subscribing to
 * incremental [net.transgressoft.lirp.event.CrudEvent] notifications for ongoing maintenance.
 * Soft-deleted entities (those implementing [net.transgressoft.lirp.entity.SoftDeletable] with a
 * non-null `deletedAt`) are excluded from all buckets.
 *
 * Keys are maintained in natural sorted order via a [java.util.concurrent.ConcurrentSkipListMap].
 *
 * Usage:
 * ```kotlin
 * val itemsByAlbum by registryProjectionMap(trackRepo) { it.albumName }
 * ```
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param registry the source registry to project
 * @param keyExtractor trailing-lambda grouping function that extracts the projection key from an entity
 * @return a [RegistryProjectionMap] delegate grouping registry entities by [keyExtractor]
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> registryProjectionMap(
    registry: Registry<K, E>,
    keyExtractor: (E) -> PK
): RegistryProjectionMap<K, PK, E> = RegistryProjectionMap(registry, keyExtractor)

/**
 * Creates a read-only value-transformed projection that groups entities from a source collection
 * by a secondary key and applies [valueTransform] to each bucket.
 *
 * The [valueTransform] is re-invoked only for buckets whose contents changed in a given delta.
 * Buckets that were not affected by a mutation retain their cached value without triggering
 * a transform recompute (per-affected-bucket recompute).
 *
 * When a bucket becomes empty and its key is removed from the backing projection, the corresponding
 * key is also removed from the returned map — [valueTransform] is never called over an empty list.
 *
 * Usage:
 * ```kotlin
 * val trackCountByTitle = projectionMap(::audioItems) { it.title } { pk, items -> "${pk}:${items.size}" }
 * ```
 *
 * **Weak cross-key consistency:** Two consecutive reads on different keys are NOT a single
 * snapshot. Iteration is CME-free via [java.util.concurrent.ConcurrentHashMap].
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the value type produced by [valueTransform]
 * @param sourceRef lambda returning the source collection (supports `::property` syntax)
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param valueTransform trailing-lambda applied to each `(PK, List<E>)` bucket to produce a `V` value;
 *   invoked only for buckets affected by the latest delta
 * @return a [Map] of type `Map<PK, V>` grouping transformed bucket values by [keyExtractor]
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> projectionMap(
    sourceRef: () -> AggregateCollectionRef<K, E>,
    keyExtractor: (E) -> PK,
    valueTransform: (PK, List<E>) -> V
): Map<PK, V> = TransformedProjectionMap(ProjectionMap(sourceRef, keyExtractor), valueTransform)

/**
 * Creates a read-only value-transformed projection that groups all entities from a [Registry] by
 * a secondary key and applies [valueTransform] to each bucket.
 *
 * The [valueTransform] is re-invoked only for buckets whose contents changed in a given delta.
 * Buckets that were not affected by a mutation retain their cached value without triggering
 * a transform recompute (per-affected-bucket recompute).
 *
 * When a bucket becomes empty and its key is removed from the backing projection, the corresponding
 * key is also removed from the returned map — [valueTransform] is never called over an empty list.
 *
 * Usage:
 * ```kotlin
 * val summaryByAlbum = registryProjectionMap(trackRepo) { it.albumName } { pk, items -> AlbumSummary(pk, items.size) }
 * ```
 *
 * **Weak cross-key consistency:** Two consecutive reads on different keys are NOT a single
 * snapshot. Iteration is CME-free via [java.util.concurrent.ConcurrentHashMap].
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the value type produced by [valueTransform]
 * @param registry the source registry to project
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param valueTransform trailing-lambda applied to each `(PK, List<E>)` bucket to produce a `V` value;
 *   invoked only for buckets affected by the latest delta
 * @return a [CloseableProjectionMap] of type `Map<PK, V>` grouping transformed bucket values by
 *   [keyExtractor]; [close][CloseableProjectionMap.close] releases the registry subscription
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> registryProjectionMap(
    registry: Registry<K, E>,
    keyExtractor: (E) -> PK,
    valueTransform: (PK, List<E>) -> V
): CloseableProjectionMap<PK, V> = TransformedRegistryProjectionMap(RegistryProjectionMap(registry, keyExtractor), valueTransform)

/**
 * Creates a read-only multi-key projection that groups entities from a source collection by every
 * key that [keyExtractor] returns. A single entity with keys `{Rock, Jazz}` appears in both the
 * `"Rock"` and `"Jazz"` buckets simultaneously.
 *
 * The projection initializes lazily on the first map access and reflects subsequent mutations
 * automatically when the source is a [MutableAggregateList] or [MutableAggregateSet].
 *
 * Keys are maintained in natural sorted order via a [java.util.concurrent.ConcurrentSkipListMap].
 * An empty key collection from [keyExtractor] places the entity in zero buckets with no error.
 * Duplicate keys in the returned collection are deduplicated before bucketing.
 *
 * **Weak cross-key consistency:** Two consecutive reads on different keys are NOT a single
 * snapshot. Iteration is CME-free via [java.util.concurrent.ConcurrentSkipListMap].
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param sourceRef lambda returning the source collection (supports `::property` syntax)
 * @param keyExtractor trailing-lambda that extracts a collection of projection keys from an entity
 * @return a [MultiKeyProjectionMap] delegate grouping entities by every key in [keyExtractor]
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> multiKeyProjectionMap(
    sourceRef: () -> AggregateCollectionRef<K, E>,
    keyExtractor: (E) -> Collection<PK>
): MultiKeyProjectionMap<K, PK, E> = MultiKeyProjectionMap(sourceRef, keyExtractor)

/**
 * Creates a read-only value-transformed multi-key projection that groups entities from a source
 * collection by every key that [keyExtractor] returns and applies [valueTransform] to each bucket.
 *
 * The [valueTransform] is re-invoked only for buckets whose contents changed in a given delta.
 * An empty key collection from [keyExtractor] places the entity in zero buckets with no error.
 * Duplicate keys in the returned collection are deduplicated before bucketing.
 *
 * **Weak cross-key consistency:** Two consecutive reads on different keys are NOT a single
 * snapshot. Iteration is CME-free via [java.util.concurrent.ConcurrentHashMap].
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the value type produced by [valueTransform]
 * @param sourceRef lambda returning the source collection (supports `::property` syntax)
 * @param keyExtractor grouping function that extracts a collection of projection keys from an entity
 * @param valueTransform trailing-lambda applied to each `(PK, List<E>)` bucket to produce a `V` value;
 *   invoked only for buckets affected by the latest delta
 * @return a [Map] of type `Map<PK, V>` grouping transformed bucket values by every key in [keyExtractor]
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> multiKeyProjectionMap(
    sourceRef: () -> AggregateCollectionRef<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    valueTransform: (PK, List<E>) -> V
): Map<PK, V> = TransformedMultiKeyProjectionMap(MultiKeyProjectionMap(sourceRef, keyExtractor), valueTransform)

/**
 * Creates a read-only multi-key projection that groups all entities from a [Registry] by every key
 * that [keyExtractor] returns. A single entity with genres `{Rock, Jazz}` appears in both the
 * `"Rock"` and `"Jazz"` buckets simultaneously.
 *
 * The projection initializes lazily on the first map access, seeds from [Registry.iterator], and
 * subscribes to incremental [net.transgressoft.lirp.event.CrudEvent] notifications for ongoing
 * maintenance. Soft-deleted entities are excluded from all buckets.
 *
 * On a key-set change via Update, new buckets are populated before stale buckets are removed so
 * the entity is never transiently absent from all buckets mid-move (add-before-remove ordering).
 * An empty key collection places the entity in zero buckets with no error. Duplicate keys are
 * deduplicated before bucketing.
 *
 * **Weak cross-key consistency:** Two consecutive reads on different keys are NOT a single
 * snapshot. Iteration is CME-free via [java.util.concurrent.ConcurrentSkipListMap].
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param registry the source registry to project
 * @param keyExtractor trailing-lambda that extracts a collection of projection keys from an entity
 * @return a [MultiKeyRegistryProjectionMap] delegate grouping registry entities by every key in [keyExtractor]
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> registryMultiKeyProjectionMap(
    registry: Registry<K, E>,
    keyExtractor: (E) -> Collection<PK>
): MultiKeyRegistryProjectionMap<K, PK, E> = MultiKeyRegistryProjectionMap(registry, keyExtractor)

/**
 * Creates a read-only value-transformed multi-key projection that groups all entities from a
 * [Registry] by every key that [keyExtractor] returns and applies [valueTransform] to each bucket.
 *
 * The [valueTransform] is re-invoked only for buckets whose contents changed in a given delta.
 * Soft-deleted entities are excluded from all buckets. On a key-set change via Update, new buckets
 * are populated before stale buckets are removed (add-before-remove ordering). Duplicate keys are
 * deduplicated before bucketing.
 *
 * **Weak cross-key consistency:** Two consecutive reads on different keys are NOT a single
 * snapshot. Iteration is CME-free via [java.util.concurrent.ConcurrentHashMap].
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the value type produced by [valueTransform]
 * @param registry the source registry to project
 * @param keyExtractor grouping function that extracts a collection of projection keys from an entity
 * @param valueTransform trailing-lambda applied to each `(PK, List<E>)` bucket to produce a `V` value;
 *   invoked only for buckets affected by the latest delta
 * @return a [CloseableProjectionMap] of type `Map<PK, V>` grouping transformed bucket values by every
 *   key in [keyExtractor]; [close][CloseableProjectionMap.close] releases the registry subscription
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V> registryMultiKeyProjectionMap(
    registry: Registry<K, E>,
    keyExtractor: (E) -> Collection<PK>,
    valueTransform: (PK, List<E>) -> V
): CloseableProjectionMap<PK, V> = TransformedMultiKeyRegistryProjectionMap(MultiKeyRegistryProjectionMap(registry, keyExtractor), valueTransform)
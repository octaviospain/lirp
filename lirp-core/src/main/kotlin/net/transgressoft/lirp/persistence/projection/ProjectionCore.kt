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
import java.util.Collections
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Shared bucket engine for projection maps.
 *
 * Holds the backing [ConcurrentSkipListMap] and the [onChange] callback, and provides all
 * bucket-mutation operations used by both aggregate-source and registry-source projections.
 * This class is a pure composition target: it is not abstract and has no supertype.
 *
 * No reverse-index or entity-id logic lives here; callers are responsible for tracking
 * which bucket key an entity belongs to across updates.
 *
 * [handleAdded], [handleRemoved], [handleRemovedFromBucket], and [removeFromAnyBucket] assume the
 * single-bucket-per-entity model and fire a notification per call. The `*Silent` primitives
 * ([addToBucketSilent], [removeByIdFromBucketSilent], [removeFromBucketSilent], [replaceInBucketSilent])
 * plus [fireBucketsChanged] exist for multi-key callers that mutate several buckets for one logical
 * delta and emit a single batched notification.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param keyExtractor grouping function that extracts the projection key from an entity
 */
internal class ProjectionCore<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val keyExtractor: (E) -> PK
) {
    val backingMap = ConcurrentSkipListMap<PK, List<E>>()
    val readOnlyView: Map<PK, List<E>> = Collections.unmodifiableMap(backingMap)

    /**
     * Optional callback invoked after each projection change with the current map state.
     * Fires after every incremental update that results in at least one addition, removal, or replacement.
     *
     * The callback fires on the same thread that performed the mutation; subscribers requiring a
     * specific thread must marshal themselves.
     */
    var onChange: ((Map<PK, List<E>>) -> Unit)? = null

    /**
     * Optional callback invoked alongside [onChange] after each non-noop bucket mutation.
     * Carries only the set of projection keys whose buckets were actually modified in the current
     * operation — enabling listeners to recompute only affected entries rather than scanning
     * the whole map.
     *
     * Fires on the same thread that performed the mutation. Single-subscriber: the last assignment
     * wins. Early-return paths (no-op replace, nothing-removed) fire neither [onChange] nor this
     * callback.
     */
    var onBucketsChanged: ((changedKeys: Set<PK>) -> Unit)? = null

    /**
     * Returns an unmodifiable frozen snapshot of [elements] suitable for storage as a bucket value.
     */
    fun freezeBucket(elements: List<E>): List<E> = Collections.unmodifiableList(ArrayList(elements))

    /**
     * Inserts each element in [elements] into its bucket as determined by [keyExtractor], creating
     * the bucket if absent. Fires [onChange] and [onBucketsChanged] when at least one element was added.
     */
    fun handleAdded(elements: List<E>) {
        val changedKeys = mutableSetOf<PK>()
        for (element in elements) {
            val key = keyExtractor(element)
            backingMap[key] = freezeBucket((backingMap[key] ?: emptyList()) + element)
            changedKeys += key
        }
        if (changedKeys.isNotEmpty()) {
            onChange?.invoke(readOnlyView)
            onBucketsChanged?.invoke(changedKeys)
        }
    }

    /**
     * Removes each element in [elements] from its bucket. When the primary bucket lookup by
     * [keyExtractor] misses (e.g. the key field changed before removal), falls back to
     * [removeFromAnyBucket]. Fires [onChange] and [onBucketsChanged] when at least one element was removed.
     */
    fun handleRemoved(elements: List<E>) {
        val changedKeys = mutableSetOf<PK>()
        for (element in elements) {
            val key = keyExtractor(element)
            val bucket = backingMap[key]
            if (bucket != null && element in bucket) {
                val filtered = bucket.filter { it != element }
                if (filtered.isEmpty()) backingMap.remove(key)
                else backingMap[key] = freezeBucket(filtered)
                changedKeys += key
            } else {
                val removedKey = removeFromAnyBucket(element)
                if (removedKey != null) changedKeys += removedKey
            }
        }
        if (changedKeys.isNotEmpty()) {
            onChange?.invoke(readOnlyView)
            onBucketsChanged?.invoke(changedKeys)
        }
    }

    /**
     * Scans all buckets and removes the first occurrence of [element], returning the bucket key
     * it was removed from, or `null` if not found.
     *
     * Used as a fallback when the element's key field has already been mutated so the primary
     * bucket lookup would miss.
     *
     * Assumes the single-bucket-per-entity model used by the single-key projections; multi-key
     * callers must not use it (they drive removal through a reverse index and the silent batch
     * primitives instead).
     */
    fun removeFromAnyBucket(element: E): PK? {
        for (entry in backingMap.entries) {
            if (element in entry.value) {
                val filtered = entry.value.filter { it != element }
                // ConcurrentSkipListMap entry iterators return SimpleImmutableEntry instances whose setValue throws
                // UnsupportedOperationException — go through the map directly instead.
                if (filtered.isEmpty()) backingMap.remove(entry.key)
                else backingMap[entry.key] = freezeBucket(filtered)
                return entry.key
            }
        }
        return null
    }

    /**
     * Removes [element] from the bucket at [key]. If the element is not found in that bucket,
     * falls back to [removeFromAnyBucket]. Fires [onChange] and [onBucketsChanged] when at least
     * one element was removed.
     */
    fun handleRemovedFromBucket(element: E, key: PK) {
        val bucket = backingMap[key]
        if (bucket != null && element in bucket) {
            val filtered = bucket.filter { it != element }
            if (filtered.isEmpty()) backingMap.remove(key)
            else backingMap[key] = freezeBucket(filtered)
            onChange?.invoke(readOnlyView)
            onBucketsChanged?.invoke(setOf(key))
        } else {
            val removedKey = removeFromAnyBucket(element)
            if (removedKey != null) {
                onChange?.invoke(readOnlyView)
                onBucketsChanged?.invoke(setOf(removedKey))
            }
        }
    }

    /**
     * Removes the entity with the given [entityId] from the bucket at [key], using an ID-based
     * lookup rather than object equality. Used when the entity object may have already changed
     * (e.g., key field mutation) so equality-based lookups would miss. Fires [onChange] and
     * [onBucketsChanged] when removed.
     */
    fun handleRemovedByIdFromBucket(entityId: K, key: PK) {
        val bucket = backingMap[key] ?: return
        val filtered = bucket.filter { it.id != entityId }
        if (filtered.size == bucket.size) return // nothing removed
        if (filtered.isEmpty()) backingMap.remove(key) else backingMap[key] = freezeBucket(filtered)
        onChange?.invoke(readOnlyView)
        onBucketsChanged?.invoke(setOf(key))
    }

    /**
     * Replaces the entity with the same [id][IdentifiableEntity.id] as [newEntity] in the bucket
     * at [key]. No-op when the bucket is absent, the entity is not in the bucket, or the entity
     * is already equal to [newEntity]. Fires [onChange] and [onBucketsChanged] when the replacement occurs.
     */
    fun handleReplaceInBucket(newEntity: E, key: PK) {
        val bucket = backingMap[key] ?: return
        val oldEntity = bucket.firstOrNull { it.id == newEntity.id } ?: return
        if (oldEntity == newEntity) return
        backingMap[key] = freezeBucket(bucket.map { if (it.id == newEntity.id) newEntity else it })
        onChange?.invoke(readOnlyView)
        onBucketsChanged?.invoke(setOf(key))
    }

    /**
     * Inserts [element] into the bucket at [key] without firing any callback, creating the bucket if
     * absent. Returns [key]. Silent primitive for multi-key callers that mutate several buckets for one
     * logical delta and emit a single notification via [fireBucketsChanged].
     */
    fun addToBucketSilent(element: E, key: PK): PK {
        backingMap[key] = freezeBucket((backingMap[key] ?: emptyList()) + element)
        return key
    }

    /**
     * Removes the entity with [entityId] from the bucket at [key] without firing any callback.
     * Returns [key] when an element was removed, or `null` on no-op.
     */
    fun removeByIdFromBucketSilent(entityId: K, key: PK): PK? {
        val bucket = backingMap[key] ?: return null
        val filtered = bucket.filter { it.id != entityId }
        if (filtered.size == bucket.size) return null
        if (filtered.isEmpty()) backingMap.remove(key) else backingMap[key] = freezeBucket(filtered)
        return key
    }

    /**
     * Removes [element] (by equality) from the bucket at [key] without firing any callback.
     * Returns [key] when an element was removed, or `null` on no-op.
     */
    fun removeFromBucketSilent(element: E, key: PK): PK? {
        val bucket = backingMap[key] ?: return null
        if (element !in bucket) return null
        val filtered = bucket.filter { it != element }
        if (filtered.isEmpty()) backingMap.remove(key) else backingMap[key] = freezeBucket(filtered)
        return key
    }

    /**
     * Replaces the entity sharing [newEntity]'s id in the bucket at [key] without firing any callback.
     * Returns [key] when a replacement occurred, or `null` on no-op (bucket/entity absent or already equal).
     */
    fun replaceInBucketSilent(newEntity: E, key: PK): PK? {
        val bucket = backingMap[key] ?: return null
        val oldEntity = bucket.firstOrNull { it.id == newEntity.id } ?: return null
        if (oldEntity == newEntity) return null
        backingMap[key] = freezeBucket(bucket.map { if (it.id == newEntity.id) newEntity else it })
        return key
    }

    /**
     * Fires [onChange] then [onBucketsChanged] once with [changedKeys] when the set is non-empty.
     * Multi-key callers accumulate affected keys across several silent bucket ops and emit one delta.
     */
    fun fireBucketsChanged(changedKeys: Set<PK>) {
        if (changedKeys.isNotEmpty()) {
            onChange?.invoke(readOnlyView)
            onBucketsChanged?.invoke(changedKeys)
        }
    }
}
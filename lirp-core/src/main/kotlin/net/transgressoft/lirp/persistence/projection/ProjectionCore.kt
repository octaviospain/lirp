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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared bucket engine for projection maps.
 *
 * Holds the backing [ConcurrentSkipListMap] and multi-subscriber listener registries, and
 * provides all bucket-mutation operations used by both aggregate-source and registry-source
 * projections. This class is a pure composition target: it is not abstract and has no supertype.
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
 * When [entryOrdering] is non-null, each per-key bucket's `List<E>` is maintained in sorted order
 * according to that comparator. Elements with equal sort keys retain their arrival order (the newly
 * arriving equal element is inserted after the existing equal run). When [entryOrdering] is null,
 * buckets keep insertion order and every existing code path is unchanged.
 *
 * The [entryOrdering] comparator must obey the `Comparator` contract (total order, transitivity).
 * A non-transitive or throwing comparator will, at worst, misplace an element or propagate the
 * exception on the event-delivery thread; it will not raise "Comparison method violates its general
 * contract" because the single-element upper-bound insert never invokes `Collections.sort`.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param entryOrdering optional comparator that maintains each bucket's `List<E>` in sorted order;
 *   `null` (the default) preserves insertion order
 */
internal class ProjectionCore<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val keyExtractor: (E) -> PK,
    private val entryOrdering: Comparator<E>? = null
) {
    val backingMap = ConcurrentSkipListMap<PK, List<E>>()
    val readOnlyView: Map<PK, List<E>> = Collections.unmodifiableMap(backingMap)

    private val onChangeListeners = CopyOnWriteArrayList<(Map<PK, List<E>>) -> Unit>()
    private val onBucketsChangedListeners = CopyOnWriteArrayList<(Set<PK>) -> Unit>()

    /**
     * Registers [listener] to be invoked after each projection change with the current map state.
     * Multiple listeners are supported; each is called in registration order on the mutating thread.
     * The returned [AutoCloseable] deregisters this listener when closed.
     */
    fun addOnChangeListener(listener: (Map<PK, List<E>>) -> Unit): AutoCloseable {
        onChangeListeners.add(listener)
        return AutoCloseable { onChangeListeners.remove(listener) }
    }

    /**
     * Registers [listener] to be invoked alongside onChange listeners after each non-noop bucket
     * mutation. Carries only the set of projection keys whose buckets were actually modified.
     * Multiple listeners are supported; each is called in registration order on the mutating thread.
     * The returned [AutoCloseable] deregisters this listener when closed.
     */
    fun addOnBucketsChangedListener(listener: (Set<PK>) -> Unit): AutoCloseable {
        onBucketsChangedListeners.add(listener)
        return AutoCloseable { onBucketsChangedListeners.remove(listener) }
    }

    private fun fireOnChange() {
        for (listener in onChangeListeners) listener(readOnlyView)
    }

    private fun fireOnBucketsChanged(changedKeys: Set<PK>) {
        for (listener in onBucketsChangedListeners) listener(changedKeys)
    }

    /**
     * Returns an unmodifiable frozen snapshot of [elements] suitable for storage as a bucket value.
     */
    fun freezeBucket(elements: List<E>): List<E> = Collections.unmodifiableList(ArrayList(elements))

    /**
     * Inserts [element] into [bucket] at the upper-bound position determined by [cmp], producing a
     * new frozen unmodifiable list. The upper-bound rule places the new element after any existing
     * elements that compare equal, preserving arrival order among equal elements.
     */
    private fun insertedSorted(bucket: List<E>, element: E, cmp: Comparator<E>): List<E> {
        var lo = 0
        var hi = bucket.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cmp.compare(element, bucket[mid]) < 0) hi = mid else lo = mid + 1
        }
        val out = ArrayList<E>(bucket.size + 1)
        out.addAll(bucket)
        out.add(lo, element)
        return Collections.unmodifiableList(out)
    }

    /**
     * Inserts each element in [elements] into its bucket as determined by [keyExtractor], creating
     * the bucket if absent. Fires onChange and onBucketsChanged listeners when at least one element was added.
     * When [entryOrdering] is non-null, elements are inserted at their sorted position within the bucket.
     */
    fun handleAdded(elements: List<E>) {
        val changedKeys = mutableSetOf<PK>()
        for (element in elements) {
            val key = keyExtractor(element)
            val existing = backingMap[key] ?: emptyList()
            backingMap[key] = if (entryOrdering != null) insertedSorted(existing, element, entryOrdering) else freezeBucket(existing + element)
            changedKeys += key
        }
        if (changedKeys.isNotEmpty()) {
            fireOnChange()
            fireOnBucketsChanged(changedKeys)
        }
    }

    /**
     * Removes each element in [elements] from its bucket. When the primary bucket lookup by
     * [keyExtractor] misses (e.g. the key field changed before removal), falls back to
     * [removeFromAnyBucket]. Fires onChange and onBucketsChanged listeners when at least one element was removed.
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
            fireOnChange()
            fireOnBucketsChanged(changedKeys)
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
     * falls back to [removeFromAnyBucket]. Fires onChange and onBucketsChanged listeners when at least
     * one element was removed.
     */
    fun handleRemovedFromBucket(element: E, key: PK) {
        val bucket = backingMap[key]
        if (bucket != null && element in bucket) {
            val filtered = bucket.filter { it != element }
            if (filtered.isEmpty()) backingMap.remove(key)
            else backingMap[key] = freezeBucket(filtered)
            fireOnChange()
            fireOnBucketsChanged(setOf(key))
        } else {
            val removedKey = removeFromAnyBucket(element)
            if (removedKey != null) {
                fireOnChange()
                fireOnBucketsChanged(setOf(removedKey))
            }
        }
    }

    /**
     * Removes the entity with the given [entityId] from the bucket at [key], using an ID-based
     * lookup rather than object equality. Used when the entity object may have already changed
     * (e.g., key field mutation) so equality-based lookups would miss. Fires onChange and
     * onBucketsChanged listeners when removed.
     */
    fun handleRemovedByIdFromBucket(entityId: K, key: PK) {
        val bucket = backingMap[key] ?: return
        val filtered = bucket.filter { it.id != entityId }
        if (filtered.size == bucket.size) return // nothing removed
        if (filtered.isEmpty()) backingMap.remove(key) else backingMap[key] = freezeBucket(filtered)
        fireOnChange()
        fireOnBucketsChanged(setOf(key))
    }

    /**
     * Replaces the entity with the same [id][IdentifiableEntity.id] as [newEntity] in the bucket
     * at [key]. No-op when the bucket is absent, the entity is not in the bucket, or the entity
     * is already equal to [newEntity]. Fires onChange and onBucketsChanged listeners when the replacement occurs.
     */
    fun handleReplaceInBucket(newEntity: E, key: PK) {
        val bucket = backingMap[key] ?: return
        val oldEntity = bucket.firstOrNull { it.id == newEntity.id } ?: return
        if (oldEntity == newEntity) return
        backingMap[key] = freezeBucket(bucket.map { if (it.id == newEntity.id) newEntity else it })
        fireOnChange()
        fireOnBucketsChanged(setOf(key))
    }

    /**
     * Inserts [element] into the bucket at [key] without firing any listener, creating the bucket if
     * absent. Returns [key]. Silent primitive for multi-key callers that mutate several buckets for one
     * logical delta and emit a single notification via [fireBucketsChanged].
     * When [entryOrdering] is non-null, the element is inserted at its sorted position within the bucket.
     */
    fun addToBucketSilent(element: E, key: PK): PK {
        val existing = backingMap[key] ?: emptyList()
        backingMap[key] = if (entryOrdering != null) insertedSorted(existing, element, entryOrdering) else freezeBucket(existing + element)
        return key
    }

    /**
     * Removes the entity with [entityId] from the bucket at [key] without firing any listener.
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
     * Removes [element] (by equality) from the bucket at [key] without firing any listener.
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
     * Replaces the entity sharing [newEntity]'s id in the bucket at [key] without firing any listener.
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
     * Removes the entity sharing [newEntity]'s id from the bucket at [key] and re-inserts [newEntity]
     * at the position determined by [entryOrdering]. Fires onChange and onBucketsChanged listeners when
     * the bucket actually changes (position or reference differs). This method must only be called when
     * [entryOrdering] is non-null; it compares the computed sorted index against the current index to
     * avoid unnecessary rebuilds when the position is unchanged.
     *
     * Unlike [handleReplaceInBucket], this method does not short-circuit on entity equality — it always
     * evaluates the sorted position of [newEntity] so that an in-place property mutation that changes the
     * comparator's sort key repositions the element correctly.
     */
    fun repositionInBucket(newEntity: E, key: PK) {
        requireNotNull(entryOrdering) { "repositionInBucket requires a non-null entryOrdering" }
        val bucket = backingMap[key] ?: return
        val oldIndex = bucket.indexOfFirst { it.id == newEntity.id }
        if (oldIndex < 0) return
        // Build the bucket without the entity to compute its new sorted position.
        val withoutEntity = bucket.filterIndexed { index, _ -> index != oldIndex }
        var lo = 0
        var hi = withoutEntity.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entryOrdering.compare(newEntity, withoutEntity[mid]) < 0) hi = mid else lo = mid + 1
        }
        val newIndex = lo
        // Only rebuild when position or reference has changed.
        if (newIndex == oldIndex && bucket[oldIndex] === newEntity) return
        val out = ArrayList<E>(bucket.size)
        out.addAll(withoutEntity)
        out.add(newIndex, newEntity)
        backingMap[key] = Collections.unmodifiableList(out)
        fireOnChange()
        fireOnBucketsChanged(setOf(key))
    }

    /**
     * Silent variant of [repositionInBucket] for multi-key callers. Removes the entity sharing
     * [newEntity]'s id from the bucket at [key] and re-inserts [newEntity] at its sorted position
     * without firing any listener. Returns [key] when the bucket changed (position or reference
     * differs), or `null` when no change was needed. This method must only be called when
     * [entryOrdering] is non-null.
     */
    fun repositionInBucketSilent(newEntity: E, key: PK): PK? {
        requireNotNull(entryOrdering) { "repositionInBucketSilent requires a non-null entryOrdering" }
        val bucket = backingMap[key] ?: return null
        val oldIndex = bucket.indexOfFirst { it.id == newEntity.id }
        if (oldIndex < 0) return null
        val withoutEntity = bucket.filterIndexed { index, _ -> index != oldIndex }
        var lo = 0
        var hi = withoutEntity.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entryOrdering.compare(newEntity, withoutEntity[mid]) < 0) hi = mid else lo = mid + 1
        }
        val newIndex = lo
        if (newIndex == oldIndex && bucket[oldIndex] === newEntity) return null
        val out = ArrayList<E>(bucket.size)
        out.addAll(withoutEntity)
        out.add(newIndex, newEntity)
        backingMap[key] = Collections.unmodifiableList(out)
        return key
    }

    /**
     * Fires all onChange listeners then all onBucketsChanged listeners once with [changedKeys] when
     * the set is non-empty. Multi-key callers accumulate affected keys across several silent bucket
     * ops and emit one delta.
     */
    fun fireBucketsChanged(changedKeys: Set<PK>) {
        if (changedKeys.isNotEmpty()) {
            fireOnChange()
            fireOnBucketsChanged(changedKeys)
        }
    }
}
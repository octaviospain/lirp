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
import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.MutableAggregateList
import net.transgressoft.lirp.persistence.MutableAggregateSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty

/**
 * A read-only grouped view that derives a `Map<PK, List<E>>` from a source aggregate collection,
 * placing each entity under every bucket key that [keyExtractor] returns for it.
 *
 * Unlike [ProjectionMap] (one entity per bucket), a multi-key projection places the same entity
 * into multiple buckets simultaneously. A `MutableMultiKeyAudioItem` with genres `{Rock, Jazz}`
 * appears in both the `"Rock"` and `"Jazz"` buckets.
 *
 * The projection uses a [java.util.concurrent.ConcurrentSkipListMap] (via [ProjectionCore]) for natural
 * key ordering with CME-free iteration. A `ConcurrentHashMap<K, Set<PK>>` reverse index
 * (entity id → current set of bucket keys) enables complete removal when an entity is
 * removed from the source.
 *
 * **Empty key set:** When [keyExtractor] returns an empty collection the entity is
 * placed in zero buckets. No error is raised. Removing an entity always cleans up all its buckets.
 *
 * **Duplicate keys:** The collection returned by [keyExtractor] is deduplicated to a
 * [Set] before any bucketing operation, so duplicate keys do not create double entries.
 *
 * **Weak cross-key consistency:** Two consecutive `get()` calls for different bucket
 * keys are NOT a single snapshot. Iteration is CME-free via
 * [java.util.concurrent.ConcurrentSkipListMap]; individual bucket reads are not cross-thread atomic.
 * When the source is a [MutableAggregateList] or [MutableAggregateSet], mutations are serialized
 * through the source collection's internal lock, so concurrent writes are safe.
 *
 * **Aggregate-source limitation:** like [ProjectionMap], an in-place mutation of an entity's key set
 * (a reactive `genres` change on an entity already present in the source, with no add/remove on the
 * aggregate) is NOT reflected — aggregate `CollectionChangeEvent`s carry only added/removed elements,
 * no update. The entity stays bucketed under its keys at insertion time. The registry-source
 * [MultiKeyRegistryProjectionMap] reflects in-place key changes via its Update path.
 *
 * The map is read-only. All mutations flow through the source collection.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key (bucket key) type, must be [Comparable]
 * @param E the entity type
 * @param sourceRef deferred reference to the source collection (resolved on first access)
 * @param keyExtractor function that extracts the set of projection keys from an entity;
 *   each returned key names one bucket the entity belongs to
 */
class MultiKeyProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val sourceRef: () -> AggregateCollectionRef<K, E>,
    private val keyExtractor: (E) -> Collection<PK>
) : AbstractMap<PK, List<E>>() {

    // Bucket engine — stores one List<E> per PK bucket key in a ConcurrentSkipListMap.
    // All per-key bucket ops are driven explicitly via addEntityToKey / per-key removal.
    private val core = ProjectionCore<K, PK, E> { error("ProjectionCore keyExtractor must not be called in MultiKeyProjectionMap") }

    /**
     * Reverse index: entity id → the current set of bucket keys it occupies.
     * Enables complete removal via the source-callback removed-element list.
     */
    private val reverseIndex = ConcurrentHashMap<K, Set<PK>>()

    private val initLock = Any()

    @Volatile
    private var initialized = false

    /**
     * Optional callback invoked after each projection change with the current map state.
     * Fires after every incremental update that results in at least one addition or removal.
     */
    internal var onChange: ((Map<PK, List<E>>) -> Unit)?
        get() = core.onChange
        set(value) {
            core.onChange = value
        }

    /** Fires alongside [onChange] carrying only the keys changed by the latest delta. Single-subscriber. */
    internal var onBucketsChanged: ((Set<PK>) -> Unit)?
        get() = core.onBucketsChanged
        set(value) {
            core.onBucketsChanged = value
        }

    private fun initialize() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val source = sourceRef()
            source.resolveAll().forEach { entity -> addToBucket(entity) }
            subscribeToSource(source)
            initialized = true
        }
    }

    /**
     * Returns the current contents of the [key] bucket WITHOUT triggering lazy initialization.
     * The value-transform decorator's `onBucketsChanged` hook calls this; that hook fires only after
     * [initialize] has populated the core, so it must never re-enter [initialize] (which would recurse).
     */
    internal fun bucketSnapshot(key: PK): List<E>? = core.readOnlyView[key]

    @Suppress("UNCHECKED_CAST")
    private fun subscribeToSource(source: AggregateCollectionRef<K, E>) {
        val callback: (CollectionChangeEvent<*>) -> Unit = { event ->
            if (event.type == CollectionChangeEvent.Type.CLEAR) {
                rebuild(source)
            } else {
                if (event.added.isNotEmpty()) {
                    (event.added as List<E>).forEach { entity -> addToBucket(entity) }
                }
                if (event.removed.isNotEmpty()) {
                    (event.removed as List<E>).forEach { entity -> removeFromAllBuckets(entity) }
                }
            }
        }
        when (source) {
            is MutableAggregateList<*, *> -> source.innerDelegate.addProjectionCallback(callback)
            is MutableAggregateSet<*, *> -> source.innerDelegate.addProjectionCallback(callback)
        }
    }

    private fun rebuild(source: AggregateCollectionRef<K, E>) {
        core.backingMap.clear()
        reverseIndex.clear()
        source.resolveAll().forEach { entity -> addToBucket(entity) }
    }

    /**
     * Adds [entity] to each of its bucket keys and records the key set in the reverse index.
     * When the key collection is empty, no buckets are created and the entity is not indexed.
     */
    private fun addToBucket(entity: E) {
        val newKeys = keyExtractor(entity).toSet() // deduplicate
        val oldKeys = reverseIndex[entity.id]
        if (oldKeys != null) {
            // WR-02: a re-add of an already-indexed entity reconciles as a key-set delta instead of
            // overwriting the index and orphaning the entity in previously-recorded buckets.
            applyKeyDelta(entity, oldKeys, newKeys)
            return
        }
        if (newKeys.isEmpty()) return // empty key set → no buckets, no error
        val changed = mutableSetOf<PK>()
        for (key in newKeys) changed += core.addToBucketSilent(entity, key)
        reverseIndex[entity.id] = newKeys
        core.fireBucketsChanged(changed) // WR-01: one batched delta per logical add
    }

    /**
     * Applies the [oldKeys]→[newKeys] bucket delta for [entity] (add-before-remove), then replaces the
     * entity in unchanged buckets, and emits a single batched notification. Mirrors the registry variant.
     */
    private fun applyKeyDelta(entity: E, oldKeys: Set<PK>, newKeys: Set<PK>) {
        val toAdd = newKeys - oldKeys
        val toRemove = oldKeys - newKeys
        val unchanged = oldKeys intersect newKeys
        val changed = mutableSetOf<PK>()
        for (key in toAdd) changed += core.addToBucketSilent(entity, key)
        if (newKeys.isEmpty()) reverseIndex.remove(entity.id) else reverseIndex[entity.id] = newKeys
        for (key in toRemove) core.removeByIdFromBucketSilent(entity.id, key)?.let { changed += it }
        for (key in unchanged) core.replaceInBucketSilent(entity, key)?.let { changed += it }
        core.fireBucketsChanged(changed)
    }

    /**
     * Removes [entity] from ALL its recorded bucket keys and clears it from the reverse index.
     * Removal is by entity equality so it works even when the entity's key field has not changed.
     */
    private fun removeFromAllBuckets(entity: E) {
        val keys = reverseIndex.remove(entity.id) ?: return
        val changed = mutableSetOf<PK>()
        for (key in keys) core.removeFromBucketSilent(entity, key)?.let { changed += it }
        core.fireBucketsChanged(changed) // WR-01: one batched delta per logical removal
    }

    // AbstractMap read overrides — all call initialize() first and delegate to core.readOnlyView

    override val size: Int get() {
        initialize()
        return core.readOnlyView.size
    }

    override val entries: Set<Map.Entry<PK, List<E>>> get() {
        initialize()
        return core.readOnlyView.entries
    }

    override val keys: Set<PK> get() {
        initialize()
        return core.readOnlyView.keys
    }

    override val values: Collection<List<E>> get() {
        initialize()
        return core.readOnlyView.values
    }

    override fun containsKey(key: PK): Boolean {
        initialize()
        return core.readOnlyView.containsKey(key)
    }

    override fun containsValue(value: List<E>): Boolean {
        initialize()
        return core.readOnlyView.containsValue(value)
    }

    override fun get(key: PK): List<E>? {
        initialize()
        return core.readOnlyView[key]
    }

    override fun isEmpty(): Boolean {
        initialize()
        return core.readOnlyView.isEmpty()
    }

    /**
     * Returns `this` projection map, initializing the source state on the first call.
     *
     * Implements Kotlin `by`-delegation: `val grouped by multiKeyProjectionMap(::audioItems) { it.genres }`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): MultiKeyProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
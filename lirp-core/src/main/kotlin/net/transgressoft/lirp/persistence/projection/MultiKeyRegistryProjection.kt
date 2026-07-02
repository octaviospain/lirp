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
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.Registry
import net.transgressoft.lirp.persistence.isSoftDeleted
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty

/**
 * A read-only grouped view that derives a `Map<PK, List<E>>` from a [Registry] source,
 * placing each entity under every bucket key that [keyExtractor] returns for it.
 *
 * Unlike [RegistryProjection] (one entity per bucket), a multi-key projection places
 * the same entity into multiple buckets simultaneously. A `MutableMultiKeyAudioItem` with
 * genres `{Rock, Jazz}` appears in both the `"Rock"` and `"Jazz"` buckets.
 *
 * The projection uses a [java.util.concurrent.ConcurrentSkipListMap] (via [ProjectionCore]) for natural
 * key ordering with CME-free iteration. Bucketing logic is driven by a
 * `ConcurrentHashMap<K, Set<PK>>` reverse index (entity id → current set of bucket keys)
 * enabling O(1) old-key-set lookup on Update events without relying on old-state snapshots.
 *
 * **Add-before-remove ordering:** On a key-set update, new buckets are populated
 * first; then stale buckets are cleaned up. This guarantees the entity is never
 * transiently absent from all buckets mid-move.
 *
 * **Empty key set:** When [keyExtractor] returns an empty collection the entity
 * is placed in zero buckets. No error is raised.
 *
 * **Duplicate keys:** The collection returned by [keyExtractor] is deduplicated
 * to a [Set] before any bucketing operation, so duplicate keys do not create double entries.
 *
 * **Weak cross-key consistency:** Two consecutive `get()` calls for different bucket
 * keys are NOT a single snapshot. Iteration is CME-free via
 * [java.util.concurrent.ConcurrentSkipListMap]; compound read-modify-write of a single bucket is not
 * cross-thread atomic. Bucket maintenance is effectively single-writer because all mutations
 * flow through the registry event-delivery path.
 *
 * Soft-deleted entities (those implementing [SoftDeletable] with a non-null `deletedAt`)
 * are removed from ALL their buckets and from the reverse index.
 *
 * **Lifecycle:** the projection holds a live registry subscription from first access until [close].
 * Create it once per long-lived delegate, or call [close] when discarding a transient projection, to
 * avoid leaking the subscription and the projection's callbacks.
 *
 * The map is read-only. All mutations flow through the registry.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key (bucket key) type, must be [Comparable]
 * @param E the entity type
 * @param registry the source registry whose entities are projected
 * @param keyExtractor function that extracts the set of projection keys from an entity;
 *   each returned key names one bucket the entity belongs to
 * @param entryOrdering optional comparator that maintains each bucket's `List<E>` in sorted order;
 *   `null` (the default) preserves insertion order. Equal elements retain arrival order.
 * @param bucketKeyOrdering optional comparator that orders buckets (map entries) by their projection
 *   key; `null` (the default) preserves PK natural order. A mandatory `Comparator.naturalOrder<PK>()`
 *   tiebreak is always composed in to guarantee a stable total order over distinct keys.
 */
class MultiKeyRegistryProjection<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val registry: Registry<K, E>,
    private val keyExtractor: (E) -> Collection<PK>,
    private val entryOrdering: Comparator<E>? = null,
    bucketKeyOrdering: Comparator<PK>? = null
) : AbstractMap<PK, List<E>>(), AutoCloseable {

    // Bucket engine — stores one List<E> per PK bucket key in a ConcurrentSkipListMap.
    // All per-key bucket ops are driven explicitly through the silent batch primitives; the
    // ProjectionCore keyExtractor is never invoked.
    private val core =
        ProjectionCore<K, PK, E>(
            keyExtractor = { error("ProjectionCore keyExtractor must not be called in MultiKeyRegistryProjection") },
            entryOrdering = entryOrdering,
            bucketKeyOrdering = bucketKeyOrdering
        )

    /**
     * Reverse index: entity id → the current set of bucket keys it occupies.
     * Using `ConcurrentHashMap` keeps this index isolated from the
     * [java.util.concurrent.ConcurrentSkipListMap] used for bucket storage in [ProjectionCore].
     */
    private val reverseIndex = ConcurrentHashMap<K, Set<PK>>()

    private val initLock = Any()

    @Volatile
    private var initialized = false

    /** Held to prevent GC from cancelling the underlying coroutine job. */
    private lateinit var subscription: LirpEventSubscription<*, *, *>

    /**
     * Registers [listener] to be invoked after each projection change with the current map state.
     * Multiple listeners may be registered; each fires on the mutating thread in registration order.
     * The returned [AutoCloseable] deregisters this listener when closed.
     *
     * This is the primary seam for adapter layers (such as the FX decorator) that need to
     * observe and react to projection changes from a separate module.
     */
    fun addOnChangeListener(listener: (Map<PK, List<E>>) -> Unit): AutoCloseable =
        core.addOnChangeListener(listener)

    /**
     * Registers [listener] to be invoked alongside onChange listeners after each non-noop bucket
     * mutation, carrying only the keys changed by the latest delta. Multiple listeners may be
     * registered; each fires on the mutating thread in registration order.
     * The returned [AutoCloseable] deregisters this listener when closed.
     *
     * Adapter layers use this hook to coalesce bucket-level changes into a single notification
     * batch without needing to diff the full map state.
     */
    fun addOnBucketsChangedListener(listener: (Set<PK>) -> Unit): AutoCloseable =
        core.addOnBucketsChangedListener(listener)

    private fun initialize() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            // Subscribe BEFORE seeding so events emitted concurrently during the seed iteration are
            // not dropped (the publisher short-circuits emissions while there are no subscribers, and
            // the registry iterator is only weakly consistent). Events arriving while seeding are
            // buffered and replayed in arrival order after the seed completes, so the seed thread
            // stays the sole writer during seeding. Entities captured by both the seed iterator and a
            // buffered create are de-duplicated by the reverse-index guard in onCreated.
            val seedBuffer = SeedEventBuffer<K, E>()
            subscription =
                registry.subscribeAsync(
                    CrudEvent.Type.CREATE,
                    CrudEvent.Type.UPDATE,
                    CrudEvent.Type.DELETE,
                    CrudEvent.Type.SOFT_DELETE,
                    CrudEvent.Type.RESTORE
                ) { event -> if (!seedBuffer.deferIfSeeding(event)) handleCrudEvent(event) }
            for (entity in registry) {
                if (!isSoftDeleted(entity)) addToBucket(entity)
            }
            seedBuffer.completeSeed { handleCrudEvent(it) }
            initialized = true
        }
    }

    private fun handleCrudEvent(event: CrudEvent<K, E>) {
        when (event) {
            is StandardCrudEvent.Create -> event.entities.values.forEach(::onCreated)
            is StandardCrudEvent.Delete -> event.entities.values.forEach(::onDeleted)
            is StandardCrudEvent.Update -> event.entities.forEach { (id, entity) -> onUpdated(id, entity) }
            is StandardCrudEvent.SoftDelete -> event.entities.keys.forEach(::removeSoftDeleted)
            is StandardCrudEvent.Restore -> event.entities.values.forEach(::onCreated)
            else -> { /* CONFLICT, RECOVERY_FAILED — not subscribed */ }
        }
    }

    private fun onCreated(entity: E) {
        if (isSoftDeleted(entity)) return
        // Skip entities already bucketed — e.g. one both captured by the seed iterator and replayed
        // as a buffered create from the seed window — so it is not added to its buckets twice.
        if (reverseIndex.containsKey(entity.id)) return
        addToBucket(entity)
    }

    private fun onDeleted(entity: E) {
        val oldKeys = reverseIndex.remove(entity.id) ?: return
        val changed = mutableSetOf<PK>()
        for (key in oldKeys) core.removeByIdFromBucketSilent(entity.id, key)?.let { changed += it }
        core.fireBucketsChanged(changed)
    }

    private fun onUpdated(id: K, entity: E) {
        if (isSoftDeleted(entity)) {
            removeSoftDeleted(id)
            return
        }
        val oldKeys = reverseIndex[id] ?: emptySet()
        val newKeys = keyExtractor(entity).toSet()
        val toAdd = newKeys - oldKeys
        val toRemove = oldKeys - newKeys
        val unchanged = oldKeys intersect newKeys
        val changed = mutableSetOf<PK>()
        // add new buckets FIRST so the entity is never transiently absent from all buckets
        for (key in toAdd) changed += core.addToBucketSilent(entity, key)
        if (newKeys.isEmpty()) reverseIndex.remove(id) else reverseIndex[id] = newKeys
        for (key in toRemove) core.removeByIdFromBucketSilent(id, key)?.let { changed += it }
        for (key in unchanged) {
            val changedKey =
                if (entryOrdering == null) core.replaceInBucketSilent(entity, key)
                else core.repositionInBucketSilent(entity, key)
            changedKey?.let { changed += it }
        }
        core.fireBucketsChanged(changed) // one batched delta for the whole key-set update
    }

    /**
     * Adds [entity] to each of its bucket keys and records the key set in the reverse index.
     * When the key collection is empty, no buckets are created and the entity is not indexed.
     */
    private fun addToBucket(entity: E) {
        val keys = keyExtractor(entity).toSet() // deduplicate
        if (keys.isEmpty()) return // empty key set → no buckets, no error
        val changed = mutableSetOf<PK>()
        for (key in keys) changed += core.addToBucketSilent(entity, key)
        reverseIndex[entity.id] = keys
        core.fireBucketsChanged(changed)
    }

    /**
     * Removes a soft-deleted entity from ALL its buckets and from the reverse index.
     * Removal is by id because the updated entity now carries a non-null `deletedAt`.
     */
    private fun removeSoftDeleted(id: K) {
        val oldKeys = reverseIndex.remove(id) ?: return
        val changed = mutableSetOf<PK>()
        for (key in oldKeys) core.removeByIdFromBucketSilent(id, key)?.let { changed += it }
        core.fireBucketsChanged(changed)
    }

    /**
     * Returns the current contents of the [key] bucket WITHOUT triggering lazy initialization.
     *
     * Adapter layers (such as the FX decorator) call this from their [addOnBucketsChangedListener] hook to
     * read the latest bucket contents after each delta. The hook fires only after initialization has
     * populated the core, so this method must never re-enter [initialize] (which would recurse).
     */
    fun bucketSnapshot(key: PK): List<E>? = core.readOnlyView[key]

    /**
     * Cancels the registry subscription, releasing the projection's hold on the event stream so it and
     * its callbacks become eligible for GC. Idempotent and safe to call before first access (no-op when
     * the projection never initialized). After closing, the projection no longer receives updates.
     */
    override fun close() {
        // Guarded by the same monitor as initialize() so a close racing a first access cannot
        // observe a half-assigned subscription.
        synchronized(initLock) {
            if (::subscription.isInitialized) subscription.cancel()
        }
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
     * Returns `this` projection map, initializing the registry state on the first call.
     *
     * Implements Kotlin `by`-delegation: `val grouped by registryMultiKeyProjection(repo) { it.genres }`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): MultiKeyRegistryProjection<K, PK, E> {
        initialize()
        return this
    }
}
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
import net.transgressoft.lirp.entity.SoftDeletable
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.Registry
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty

/**
 * A read-only grouped view that derives a `Map<PK, List<E>>` from a [Registry] source,
 * grouping all entities by a secondary key via [keyExtractor].
 *
 * The projection uses a [java.util.concurrent.ConcurrentSkipListMap] (via [ProjectionCore]) for natural
 * key ordering with CME-free iteration, and supports multiple listeners via [addOnChangeListener]
 * and [addOnBucketsChangedListener]. It has no JavaFX dependency.
 *
 * The projection initializes lazily on the first [getValue] (Kotlin `by` delegation) or map
 * access call, building its initial state from [Registry.iterator] and then subscribing to
 * incremental [CrudEvent] notifications. Soft-deleted entities (those implementing
 * [SoftDeletable] with a non-null [deletedAt]) are filtered out of all buckets.
 *
 * Key-change re-bucketing on [CrudEvent.Update] is driven by an internal
 * `ConcurrentHashMap<K, PK>` reverse index (entity id → current bucket key), which
 * provides O(1) old-key lookup without relying on the event's old-state snapshot.
 *
 * **Lifecycle:** the projection holds a live registry subscription from first access until [close].
 * Create it once per long-lived delegate, or call [close] when discarding a transient projection, to
 * avoid leaking the subscription and the projection's callbacks.
 *
 * The map is read-only. All mutations flow through the registry.
 *
 * **Consistency window:** During lazy initialization, the projection seeds from [Registry.iterator]
 * and then subscribes to incremental [CrudEvent] notifications. Mutations that occur in the narrow
 * window between iterator exhaustion and the subscription coroutine starting its collect loop will
 * not appear in the projection until the affected entity receives a subsequent event. This mirrors
 * the weakly-consistent contract of the underlying [java.util.concurrent.ConcurrentSkipListMap] iteration.
 *
 * **Thread safety:** same weakly-consistent contract as [ProjectionMap] — iteration is
 * CME-free via [java.util.concurrent.ConcurrentSkipListMap]; compound read-modify-write of a
 * single bucket is not cross-thread atomic.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable] (used as the backing [java.util.concurrent.ConcurrentSkipListMap] key)
 * @param E the entity type
 * @param registry the source registry whose entities are projected
 * @param keyExtractor grouping function that extracts the projection key from an entity
 */
class RegistryProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val registry: Registry<K, E>,
    private val keyExtractor: (E) -> PK
) : AbstractMap<PK, List<E>>(), AutoCloseable {

    private val core = ProjectionCore<K, PK, E>(keyExtractor)

    /** Reverse index: entity id → current bucket key. Enables O(1) old-key lookup on Update. */
    private val reverseIndex = ConcurrentHashMap<K, PK>()

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
            // Seed first so the initial snapshot is complete before any events are processed;
            // soft-deleted entities are excluded from all buckets.
            for (entity in registry) {
                if (!isSoftDeleted(entity)) addToBucket(entity)
            }
            // Subscribe after the seed is complete to avoid double-applying events that arrive
            // during iteration.
            subscription =
                registry.subscribe(
                    CrudEvent.Type.CREATE,
                    CrudEvent.Type.UPDATE,
                    CrudEvent.Type.DELETE
                ) { event -> handleCrudEvent(event) }
            initialized = true
        }
    }

    private fun handleCrudEvent(event: CrudEvent<K, E>) {
        when (event) {
            is StandardCrudEvent.Create -> event.entities.values.forEach(::onCreated)
            is StandardCrudEvent.Delete -> event.entities.values.forEach(::onDeleted)
            is StandardCrudEvent.Update -> event.entities.forEach { (id, entity) -> onUpdated(id, entity) }
            else -> { /* CONFLICT, RECOVERY_FAILED — not subscribed */ }
        }
    }

    private fun onCreated(entity: E) {
        if (!isSoftDeleted(entity)) addToBucket(entity)
    }

    private fun onDeleted(entity: E) {
        val oldKey = reverseIndex.remove(entity.id)
        // A Delete carries the stored object, so equality-based removal is safe. Fall back to a
        // full scan only when the entity was never indexed (e.g. it was soft-deleted at seed time).
        if (oldKey != null) core.handleRemovedFromBucket(entity, oldKey) else core.handleRemoved(listOf(entity))
    }

    private fun onUpdated(id: K, entity: E) {
        if (isSoftDeleted(entity)) {
            removeSoftDeleted(id)
            return
        }
        val oldKey = reverseIndex[id]
        val newKey = keyExtractor(entity)
        when {
            // Not currently bucketed: a restore from soft-delete, or a create arriving as an update.
            oldKey == null -> addToBucket(entity)
            // Bucket key changed: move the entity from its old bucket to the new one.
            oldKey != newKey -> reBucket(id, entity, oldKey)
            // Same bucket key, only non-key content changed.
            else -> core.handleReplaceInBucket(entity, newKey)
        }
    }

    /** Adds [entity] to its bucket and records its current key in the reverse index. */
    private fun addToBucket(entity: E) {
        core.handleAdded(listOf(entity))
        reverseIndex[entity.id] = keyExtractor(entity)
    }

    /** Moves [entity] out of its [oldKey] bucket and into the bucket for its current key. */
    private fun reBucket(id: K, entity: E, oldKey: PK) {
        // Remove by id: the updated entity carries the new key value, so equality-based lookup
        // against the old bucket would miss.
        core.handleRemovedByIdFromBucket(id, oldKey)
        addToBucket(entity)
    }

    /**
     * Removes a soft-deleted entity from its bucket. Removal is by id because the updated entity
     * carries the new `deletedAt` value and would not compare equal to the stored instance.
     */
    private fun removeSoftDeleted(id: K) {
        reverseIndex.remove(id)?.let { oldKey -> core.handleRemovedByIdFromBucket(id, oldKey) }
    }

    private fun isSoftDeleted(entity: E): Boolean =
        (entity as? SoftDeletable)?.deletedAt != null

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
     * Implements Kotlin `by`-delegation: `val grouped by registryProjectionMap(repo) { it.key }`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): RegistryProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
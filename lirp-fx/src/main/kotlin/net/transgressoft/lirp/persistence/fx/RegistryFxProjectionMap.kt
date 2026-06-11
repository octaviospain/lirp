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

package net.transgressoft.lirp.persistence.fx

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.SoftDeletable
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.Registry
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.FXCollections
import javafx.collections.MapChangeListener
import javafx.collections.ObservableMap
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.reflect.KProperty

/**
 * A read-only [ObservableMap] projection that groups all entities from a [Registry] by a
 * secondary key, with all bucket mutations dispatched to the JavaFX Application Thread.
 *
 * Entities are grouped by [keyExtractor] into frozen [List] buckets keyed by projection key
 * type [PK]. The backing map is a [ConcurrentSkipListMap] wrapped by [FXCollections.observableMap],
 * so projection keys are always iterated in natural sorted order with CME-free iteration.
 *
 * The projection initializes lazily: the registry subscription and initial state build happen
 * on the first [getValue] or [addListener] call, not at construction time.
 *
 * Key changes are tracked via an internal `id → projection key` reverse index
 * ([ConcurrentHashMap]), so no old-entity snapshot from the [CrudEvent] is required.
 * Soft-deleted entities (those implementing [SoftDeletable] with a non-null [deletedAt])
 * are excluded from all buckets.
 *
 * When [dispatchToFxThread] is `true` (the default), every incremental bucket mutation is
 * dispatched to the JavaFX Application Thread via [Platform.runLater]. For single-entity events,
 * exactly one `Platform.runLater` call is scheduled per entity mutation, resulting in one or two
 * [MapChangeListener.Change] notifications depending on the operation (same-key updates fire a
 * remove followed by a re-add to ensure listener notification even when the list content compares
 * equal). Batch events carrying multiple entities schedule one runLater call per entity.
 *
 * When `false`, mutations are applied inline on the calling thread. This is intended for
 * test scenarios where the JavaFX Application Thread is not available or desirable.
 *
 * Mutation methods ([put], [remove], [putAll], [clear]) throw [UnsupportedOperationException];
 * all mutations flow through the source registry.
 *
 * **Consistency window:** During lazy initialization, the projection seeds from [Registry.iterator]
 * and then subscribes to incremental [CrudEvent] notifications. Mutations that occur in the narrow
 * window between iterator exhaustion and the subscription coroutine starting its collect loop will
 * not appear in the projection until the affected entity receives a subsequent event. This mirrors
 * the weakly-consistent contract of the underlying [java.util.concurrent.ConcurrentSkipListMap] iteration.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable] (used as the backing [ConcurrentSkipListMap] key)
 * @param E the entity type
 * @param registry the source registry to project
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class RegistryFxProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val registry: Registry<K, E>,
    private val keyExtractor: (E) -> PK,
    val dispatchToFxThread: Boolean = true
) : ObservableMap<PK, List<E>> {
    private val innerObservableMap: ObservableMap<PK, List<E>> =
        FXCollections.observableMap(ConcurrentSkipListMap<PK, List<E>>())

    // Entity id → current projection key; enables O(1) old-key lookup on Update without
    // requiring an old-entity snapshot from the CrudEvent.
    private val reverseIndex = ConcurrentHashMap<K, PK>()

    @Volatile
    private var initialized = false

    // Held to prevent the JVM from GC-cancelling the underlying coroutine job
    private lateinit var subscription: LirpEventSubscription<*, *, *>

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Populate initial state directly — writes bypass mutateMap so state is visible
            // synchronously before initialized=true, mirroring FxProjectionMap.populateInitialState.
            for (entity in registry) {
                if (isSoftDeleted(entity)) continue
                val key = keyExtractor(entity)
                addToBucket(key, entity)
                reverseIndex[entity.id] = key
            }
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
        if (isSoftDeleted(entity)) return
        val key = keyExtractor(entity)
        mutateMap {
            addToBucket(key, entity)
            reverseIndex[entity.id] = key
        }
    }

    private fun onDeleted(entity: E) {
        mutateMap {
            // Read and write reverseIndex on the same dispatch thread so concurrent events for the
            // same entity cannot observe a stale key.
            val key = reverseIndex.remove(entity.id)
            if (key != null) removeFromBucket(key, entity.id) else removeFromAnyBucket(entity)
        }
    }

    private fun onUpdated(id: K, newEntity: E) {
        mutateMap {
            // Read oldKey inside the lambda, co-located with all reverseIndex writes, so interleaving
            // events for the same entity cannot observe a stale key.
            val oldKey = reverseIndex[id]
            if (isSoftDeleted(newEntity)) {
                if (oldKey != null) removeFromBucket(oldKey, id) else removeFromAnyBucket(newEntity)
                reverseIndex.remove(id)
                return@mutateMap
            }
            val newKey = keyExtractor(newEntity)
            when {
                // Not currently bucketed: a restore from soft-delete, or a create arriving as an update.
                oldKey == null -> addToBucket(newKey, newEntity)
                // Bucket key changed: move the entity from its old bucket to the new one.
                oldKey != newKey -> {
                    removeFromBucket(oldKey, id)
                    addToBucket(newKey, newEntity)
                }
                // Same bucket key, only non-key content changed.
                else -> replaceInBucket(newKey, id, newEntity)
            }
            reverseIndex[id] = newKey
        }
    }

    /** Adds [entity] to the [key] bucket if not already present. Must run on the dispatch thread. */
    private fun addToBucket(key: PK, entity: E) {
        val current = innerObservableMap[key] ?: emptyList()
        if (entity !in current) innerObservableMap[key] = freezeBucket(current + entity)
    }

    /**
     * Removes the entity with [id] from the [key] bucket, dropping the key when the bucket empties.
     * Must run on the dispatch thread.
     */
    private fun removeFromBucket(key: PK, id: K) {
        val current = innerObservableMap[key] ?: return
        val filtered = current.filter { it.id != id }
        if (filtered.isEmpty()) innerObservableMap.remove(key)
        else innerObservableMap[key] = freezeBucket(filtered)
    }

    /** Replaces the entity with [id] in the [key] bucket with [entity]. Must run on the dispatch thread. */
    private fun replaceInBucket(key: PK, id: K, entity: E) {
        val bucket = innerObservableMap[key] ?: return
        val updated = bucket.map { if (it.id == id) entity else it }
        // Remove before re-inserting so the ObservableMap fires a change notification even when the
        // old and new List values compare equal — JavaFX's ObservableMapWrapper skips callObservers
        // when oldValue.equals(newValue).
        innerObservableMap.remove(key)
        innerObservableMap[key] = freezeBucket(updated)
    }

    private fun removeFromAnyBucket(entity: E) {
        for (entry in innerObservableMap.entries) {
            if (entry.value.any { it.id == entity.id }) {
                val filtered = entry.value.filter { it.id != entity.id }
                // Use map put/remove directly so the ObservableMap wrapper fires change listeners (not entry mutation).
                if (filtered.isEmpty()) innerObservableMap.remove(entry.key)
                else innerObservableMap[entry.key] = freezeBucket(filtered)
                return
            }
        }
    }

    private fun freezeBucket(elements: List<E>): List<E> =
        Collections.unmodifiableList(ArrayList(elements))

    private fun isSoftDeleted(entity: E): Boolean =
        (entity as? SoftDeletable)?.deletedAt != null

    private fun mutateMap(action: () -> Unit) {
        if (dispatchToFxThread) {
            if (Platform.isFxApplicationThread()) action()
            else Platform.runLater(action)
        } else {
            action()
        }
    }

    // ObservableMap<PK, List<E>> — read operations delegate to innerObservableMap after initialization

    override val size: Int get() {
        initialize()
        return innerObservableMap.size
    }

    // Safe: ObservableMap declares MutableSet<MutableEntry> but the returned set is unmodifiable via Collections.unmodifiableSet.
    // Callers cannot mutate through this view; the cast satisfies the interface contract without exposing true mutability.
    // Returns the live entry set backed by innerObservableMap — consistent with keys and values,
    // which also return live views. ConcurrentSkipListMap entries are CME-safe for iteration.
    @Suppress("UNCHECKED_CAST")
    override val entries: MutableSet<MutableMap.MutableEntry<PK, List<E>>> get() {
        initialize()
        return Collections.unmodifiableSet(innerObservableMap.entries) as MutableSet<MutableMap.MutableEntry<PK, List<E>>>
    }

    // Safe: same as entries — Collections.unmodifiableSet wraps the keys. The MutableSet return type is required by
    // ObservableMap's interface but the returned set throws UnsupportedOperationException on mutation attempts.
    @Suppress("UNCHECKED_CAST")
    override val keys: MutableSet<PK> get() {
        initialize()
        return Collections.unmodifiableSet(innerObservableMap.keys) as MutableSet<PK>
    }

    // Safe: same as entries/keys — Collections.unmodifiableCollection wraps the values. The MutableCollection return type
    // is required by ObservableMap's interface but the returned collection is effectively immutable.
    @Suppress("UNCHECKED_CAST")
    override val values: MutableCollection<List<E>> get() {
        initialize()
        return Collections.unmodifiableCollection(innerObservableMap.values) as MutableCollection<List<E>>
    }

    override fun containsKey(key: PK): Boolean {
        initialize()
        return innerObservableMap.containsKey(key)
    }

    override fun containsValue(value: List<E>): Boolean {
        initialize()
        return innerObservableMap.containsValue(value)
    }

    override fun get(key: PK): List<E>? {
        initialize()
        return innerObservableMap[key]
    }

    override fun isEmpty(): Boolean {
        initialize()
        return innerObservableMap.isEmpty()
    }

    // Mutation methods — this projection is read-only; all mutations flow through the source registry
    override fun put(key: PK, value: List<E>): List<E> = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun remove(key: PK): List<E>? = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun putAll(from: Map<out PK, List<E>>) = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun clear() = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    companion object {
        private const val READ_ONLY_MESSAGE = "RegistryFxProjectionMap is read-only"
    }

    // Listener methods delegate to innerObservableMap; addListener also triggers initialization
    // so the source subscription is established before the first change fires.
    override fun addListener(listener: MapChangeListener<in PK, in List<E>>) {
        initialize()
        innerObservableMap.addListener(listener)
    }

    override fun removeListener(listener: MapChangeListener<in PK, in List<E>>) =
        innerObservableMap.removeListener(listener)

    override fun addListener(listener: InvalidationListener) {
        initialize()
        innerObservableMap.addListener(listener)
    }

    override fun removeListener(listener: InvalidationListener) =
        innerObservableMap.removeListener(listener)

    /**
     * Returns `this` projection map, initializing the registry subscription on the first call.
     *
     * Implements Kotlin `by`-delegation: `val byAlbum: ObservableMap<String, List<AudioItem>> by registryFxProjectionMap(...)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): RegistryFxProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.SoftDeletable
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.StandardCrudEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty

/**
 * A read-only grouped view that derives a `Map<PK, List<E>>` from a [Registry] source,
 * grouping all entities by a secondary key via [keyExtractor].
 *
 * The projection uses a [java.util.concurrent.ConcurrentSkipListMap] (via [ProjectionCore]) for natural
 * key ordering with CME-free iteration, and fires an optional [onChange] callback when the
 * projection state changes. It has no JavaFX dependency.
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
) : AbstractMap<PK, List<E>>() {

    private val core = ProjectionCore<K, PK, E>(keyExtractor)

    /** Reverse index: entity id → current bucket key. Enables O(1) old-key lookup on Update. */
    private val reverseIndex = ConcurrentHashMap<K, PK>()

    @Volatile
    private var initialized = false

    /** Held to prevent GC from cancelling the underlying coroutine job. */
    private lateinit var subscription: LirpEventSubscription<*, *, *>

    /**
     * Optional callback invoked after each projection change with the current map state.
     * Fires after every incremental update that results in at least one addition, removal, or replacement.
     *
     * The callback fires on the same thread that performed the registry mutation; subscribers
     * requiring a specific thread must marshal themselves.
     */
    internal var onChange: ((Map<PK, List<E>>) -> Unit)?
        get() = core.onChange
        set(value) {
            core.onChange = value
        }

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // Seed first so the initial snapshot is complete before any events are processed;
            // soft-deleted entities are excluded from all buckets.
            for (entity in registry) {
                if (isSoftDeleted(entity)) continue
                val key = keyExtractor(entity)
                core.handleAdded(listOf(entity))
                reverseIndex[entity.id] = key
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
            is StandardCrudEvent.Create ->
                event.entities.values.forEach { entity ->
                    if (!isSoftDeleted(entity)) {
                        core.handleAdded(listOf(entity))
                        reverseIndex[entity.id] = keyExtractor(entity)
                    }
                }
            is StandardCrudEvent.Delete ->
                event.entities.values.forEach { entity ->
                    val oldKey = reverseIndex[entity.id]
                    if (oldKey != null) {
                        core.handleRemovedFromBucket(entity, oldKey)
                    } else {
                        core.handleRemoved(listOf(entity))
                    }
                    reverseIndex.remove(entity.id)
                }
            is StandardCrudEvent.Update ->
                event.entities.forEach { (id, newEntity) ->
                    val oldKey = reverseIndex[id]
                    if (isSoftDeleted(newEntity)) {
                        // Soft-delete: treat as removal. Remove by ID because the entity object
                        // may differ from the one stored in the bucket.
                        if (oldKey != null) core.handleRemovedByIdFromBucket(id, oldKey)
                        reverseIndex.remove(id)
                    } else {
                        val newKey = keyExtractor(newEntity)
                        when {
                            oldKey == null -> {
                                // Restore from soft-delete or first-time create arriving as update
                                core.handleAdded(listOf(newEntity))
                                reverseIndex[id] = newKey
                            }
                            oldKey != newKey -> {
                                // Key changed: remove from the old bucket by ID (the new entity
                                // object carries the new key value, so equality-based lookup would miss)
                                // and add to the new bucket.
                                core.handleRemovedByIdFromBucket(id, oldKey)
                                core.handleAdded(listOf(newEntity))
                                reverseIndex[id] = newKey
                            }
                            else -> {
                                // Same key, non-key content changed
                                core.handleReplaceInBucket(newEntity, newKey)
                            }
                        }
                    }
                }
            else -> { /* CONFLICT, RECOVERY_FAILED — not subscribed */ }
        }
    }

    private fun isSoftDeleted(entity: E): Boolean =
        (entity as? SoftDeletable)?.deletedAt != null

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
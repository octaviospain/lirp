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
import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.event.StandardCollectionChangeEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Abstract base for mutable collection-typed aggregate reference delegates that lazily resolve a group
 * of entities from a bound [Registry] and allow runtime add/remove operations.
 *
 * Extends [AbstractAggregateCollectionRefDelegate] with a mutable backing ID store and collection
 * emission callback injection for event emission. Each mutation operation computes a per-operation
 * diff (added/removed elements) and emits a [CollectionChangeEvent] through the
 * [emitCollectionChangeEvent][net.transgressoft.lirp.entity.ReactiveEntityBase.emitCollectionChangeEvent]
 * path, wrapped in an [AggregateMutationEvent][net.transgressoft.lirp.event.AggregateMutationEvent].
 *
 * **Thread safety:** A [ReentrantLock] guards all reads and writes to [backingIds].
 * The mutation is applied while the lock is held; the collection emission callback is invoked
 * after the lock is released.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 */
abstract class AbstractMutableAggregateCollectionRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>> :
    AbstractAggregateCollectionRefDelegate<K, E>(),
    MutableAggregateCollectionRef<K, E> {

    /** Guards all reads and writes to [backingIds]. */
    protected val lock = ReentrantLock()

    @Volatile
    protected var collectionEmissionCallback: ((CollectionChangeEvent<*>) -> Unit)? = null

    /**
     * The mutable backing ID collection, initialized from the initial IDs at construction time.
     * Subclasses provide a concrete typed implementation (e.g., [ArrayList] or [LinkedHashSet]).
     * All access must be guarded by [lock].
     */
    protected abstract val backingIds: MutableCollection<K>

    /**
     * Injects the callback that emits a [CollectionChangeEvent] on the owning entity's publisher.
     * Called by [RegistryBase.bindEntityRefs][net.transgressoft.lirp.persistence.RegistryBase] after registry binding.
     */
    internal fun bindCollectionEmissionCallback(callback: (CollectionChangeEvent<*>) -> Unit) {
        collectionEmissionCallback = callback
    }

    /**
     * Secondary callbacks for projection maps that receive the full [CollectionChangeEvent] for each mutation.
     * Supports multiple concurrent projections on the same source without overwriting each other.
     * Invoked after [collectionEmissionCallback] via [notifyProjection].
     */
    private val projectionCallbacks = CopyOnWriteArrayList<(CollectionChangeEvent<*>) -> Unit>()

    /**
     * Registers [callback] as a projection listener that will receive [CollectionChangeEvent]s after each mutation.
     */
    internal fun addProjectionCallback(callback: (CollectionChangeEvent<*>) -> Unit) {
        projectionCallbacks.add(callback)
    }

    /**
     * Forwards the [CollectionChangeEvent] to all registered [projectionCallbacks].
     * Called by [mutate] and [mutateVoid] after the main emission callback.
     */
    internal fun notifyProjection(event: CollectionChangeEvent<*>) {
        projectionCallbacks.forEach { it(event) }
    }

    override fun provideIds(): Collection<K> = lock.withLock { ArrayList(backingIds) }

    override val referenceIds: Collection<K> get() = lock.withLock { ArrayList(backingIds) }

    /**
     * Executes [action] on [backingIds] under the lock and returns the result.
     * If [changed] is true and [eventBuilder] is provided, emits the [CollectionChangeEvent] after mutation
     * and forwards the diff to [projectionCallback] if one is registered.
     */
    protected fun mutate(action: MutableCollection<K>.() -> Boolean, eventBuilder: (() -> CollectionChangeEvent<*>)? = null): Boolean {
        val changed = lock.withLock { backingIds.action() }
        if (changed && eventBuilder != null) {
            val event = eventBuilder()
            collectionEmissionCallback?.invoke(event)
            notifyProjection(event)
        }
        return changed
    }

    /**
     * Executes [action] on [backingIds] under the lock without returning a result.
     * If [eventBuilder] is provided, emits the [CollectionChangeEvent] after mutation
     * and forwards the diff to [projectionCallback] if one is registered.
     */
    protected fun mutateVoid(action: MutableCollection<K>.() -> Unit, eventBuilder: (() -> CollectionChangeEvent<*>)? = null) {
        lock.withLock { backingIds.action() }
        eventBuilder?.let {
            val event = it()
            collectionEmissionCallback?.invoke(event)
            notifyProjection(event)
        }
    }

    override fun clear() {
        val removedEntities: List<*>
        lock.withLock {
            if (backingIds.isEmpty()) return
            val reg = boundRegistry()
            removedEntities =
                if (reg != null) {
                    ArrayList(backingIds).mapNotNull { reg.findById(it).orElse(null) }
                } else {
                    emptyList<Any>()
                }
            backingIds.clear()
        }
        val clearEvent = StandardCollectionChangeEvent.clear(removedEntities)
        collectionEmissionCallback?.invoke(clearEvent)
        notifyProjection(clearEvent)
    }

    /**
     * Directly replaces the contents of [backingIds] without triggering mutation events.
     * Intended for use by the framework serializer during deserialization within
     * `withEventsDisabled` to restore persisted IDs without side effects.
     */
    internal fun setBackingIds(ids: Collection<K>) {
        lock.withLock {
            backingIds.clear()
            backingIds.addAll(ids)
        }
    }

    /**
     * Adds all [elements] to this collection in a single batch mutation, emitting exactly one
     * [CollectionChangeEvent] regardless of collection size.
     *
     * Returns `true` if the collection was modified. For list-backed collections, all elements
     * are reported in the event. Subclasses with set semantics override to report only the
     * elements actually added.
     */
    override fun addAll(elements: Collection<E>): Boolean {
        val ids = elements.map { it.id }
        return mutate(
            action = { addAll(ids) },
            eventBuilder = { StandardCollectionChangeEvent.add(elements.toList()) }
        )
    }

    /**
     * Removes all [elements] from this collection in a single batch mutation, emitting exactly one
     * [CollectionChangeEvent] regardless of collection size.
     *
     * Returns `true` if the collection was modified. For list-backed collections, all elements
     * are reported in the event. Subclasses with set semantics override to report only the
     * elements actually removed.
     */
    override fun removeAll(elements: Collection<E>): Boolean {
        val ids = elements.map { it.id }
        return mutate(
            action = { removeAll(ids) },
            eventBuilder = { StandardCollectionChangeEvent.remove(elements.toList()) }
        )
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val idsToKeep = elements.map { it.id }.toSet()
        var removedEntities: List<*> = emptyList<Any>()
        val changed =
            lock.withLock {
                val reg = boundRegistry()
                val idsToRemove = backingIds.filter { it !in idsToKeep }
                removedEntities =
                    if (reg != null) {
                        idsToRemove.mapNotNull { reg.findById(it).orElse(null) }
                    } else {
                        emptyList<Any>()
                    }
                backingIds.retainAll(idsToKeep)
            }
        if (changed) {
            val event = StandardCollectionChangeEvent.remove(removedEntities)
            collectionEmissionCallback?.invoke(event)
            notifyProjection(event)
        }
        return changed
    }

    override fun contains(element: E): Boolean = lock.withLock { element.id in backingIds }

    override fun containsAll(elements: Collection<E>): Boolean =
        lock.withLock {
            elements.all { it.id in backingIds }
        }

    override fun isEmpty(): Boolean = lock.withLock { backingIds.isEmpty() }

    override val size: Int get() = lock.withLock { backingIds.size }
}
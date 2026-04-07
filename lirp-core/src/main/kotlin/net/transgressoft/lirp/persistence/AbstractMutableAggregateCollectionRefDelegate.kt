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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Abstract base for mutable collection-typed aggregate reference delegates that lazily resolve a group
 * of entities from a bound [Registry] and allow runtime add/remove operations.
 *
 * Extends [AbstractAggregateCollectionRefDelegate] with a mutable backing ID store and mutation
 * callback injection for event emission.
 *
 * **Thread safety:** A [ReentrantLock] guards all reads and writes to [backingIds].
 * The mutation callback is always invoked with the mutation action as a lambda so that
 * `mutateAndPublish` in [ReactiveEntityBase][net.transgressoft.lirp.entity.ReactiveEntityBase]
 * calls `clone()` BEFORE the action executes. This ensures the before/after comparison correctly
 * detects the change, enabling [MutationEvent][net.transgressoft.lirp.event.MutationEvent] emission.
 *
 * **Deep-copy requirement:** Entities using this delegate MUST deep-copy the backing ID
 * collection in their `clone()` implementation. Without a deep copy, the `mutateAndPublish`
 * equality check will always return true (entity before == entity after the mutation), silencing
 * all mutation events.
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
    private var mutationCallback: ((() -> Unit) -> Unit)? = null

    /**
     * The mutable backing ID collection, initialized from the initial IDs at construction time.
     * Subclasses provide a concrete typed implementation (e.g., [ArrayList] or [LinkedHashSet]).
     * All access must be guarded by [lock].
     */
    protected abstract val backingIds: MutableCollection<K>

    /**
     * Injects the mutation callback that triggers event emission on the owning entity.
     * Called by [RegistryBase.bindEntityRefs] after registry binding.
     *
     * The callback receives the mutation action as a lambda executed INSIDE
     * [ReactiveEntityBase.mutateAndPublish][net.transgressoft.lirp.entity.ReactiveEntityBase],
     * so `clone()` is called before the mutation runs — enabling correct before/after event comparison.
     */
    internal fun bindMutationCallback(callback: (() -> Unit) -> Unit) {
        mutationCallback = callback
    }

    override fun provideIds(): Collection<K> = lock.withLock { ArrayList(backingIds) }

    override val referenceIds: Collection<K> get() = lock.withLock { ArrayList(backingIds) }

    /**
     * Executes [action] on [backingIds] without returning a result, delegating to the mutation
     * callback so that `mutateAndPublish` fires BEFORE the action modifies [backingIds].
     *
     * Used for indexed mutations (e.g., `add(index, element)`, `removeAt(index)`) where no Boolean
     * result is needed. If no callback is bound, the action runs directly.
     */
    protected fun mutateVoid(action: MutableCollection<K>.() -> Unit) {
        val cb = mutationCallback
        if (cb != null) {
            cb.invoke { lock.withLock { backingIds.action() } }
        } else {
            lock.withLock { backingIds.action() }
        }
    }

    /**
     * Executes [action] on [backingIds], delegating to the mutation callback so that
     * `mutateAndPublish` fires BEFORE the action modifies [backingIds]. This ordering ensures
     * `clone()` captures the pre-mutation state for event equality comparison.
     *
     * If no callback is bound (entity not yet added to a repository), the action runs directly.
     */
    protected fun mutate(action: MutableCollection<K>.() -> Boolean): Boolean {
        val cb = mutationCallback
        return if (cb != null) {
            var changed = false
            cb.invoke {
                lock.withLock {
                    changed = backingIds.action()
                }
            }
            changed
        } else {
            lock.withLock { backingIds.action() }
        }
    }

    override fun clear() {
        val cb = mutationCallback
        if (cb != null) {
            cb.invoke {
                lock.withLock { backingIds.clear() }
            }
        } else {
            lock.withLock { backingIds.clear() }
        }
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
     * [MutationEvent][net.transgressoft.lirp.event.MutationEvent] and one persistence update
     * regardless of collection size.
     *
     * Returns `true` if the collection was modified (at least one element was not already present).
     */
    override fun addAll(elements: Collection<E>): Boolean {
        val ids = elements.map { it.id }
        return mutate { addAll(ids) }
    }

    /**
     * Removes all [elements] from this collection in a single batch mutation, emitting exactly one
     * [MutationEvent][net.transgressoft.lirp.event.MutationEvent] and one persistence update
     * regardless of collection size.
     *
     * Returns `true` if the collection was modified (at least one element was present and removed).
     */
    override fun removeAll(elements: Collection<E>): Boolean {
        val ids = elements.map { it.id }
        return mutate { removeAll(ids) }
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val idsToKeep = elements.map { it.id }.toSet()
        return mutate { retainAll(idsToKeep) }
    }

    override fun contains(element: E): Boolean = lock.withLock { element.id in backingIds }

    override fun containsAll(elements: Collection<E>): Boolean =
        lock.withLock {
            elements.all { it.id in backingIds }
        }

    override fun isEmpty(): Boolean = lock.withLock { backingIds.isEmpty() }

    override val size: Int get() = lock.withLock { backingIds.size }
}
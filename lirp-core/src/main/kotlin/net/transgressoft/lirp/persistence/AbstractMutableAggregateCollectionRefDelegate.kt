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
import kotlin.reflect.KProperty

/**
 * Abstract base for mutable collection-typed aggregate reference delegates that lazily resolve a group
 * of entities from a bound [Registry] and allow runtime add/remove operations.
 *
 * Extends [AbstractAggregateCollectionRefDelegate] with a mutable backing ID store, write-back via
 * `idSetter`, and mutation callback injection for event emission.
 *
 * **Dual-lambda ownership model (D-01):** The delegate owns its own mutable backing collection
 * initialized from `idProvider()` at construction time. The `idSetter` writes mutated IDs back to
 * the entity's serializable field after each mutation, keeping the entity's constructor-level field
 * in sync for JSON/SQL serialization.
 *
 * **idSetter optionality (D-02):** The `idSetter` is nullable. If omitted, mutations update the
 * internal backing store but are NOT written back to the entity field. Serialization will not reflect
 * runtime changes in that case. Document this risk in entity KDoc when using without `idSetter`.
 *
 * **Thread safety (D-10):** A [ReentrantLock] guards all reads and writes to [backingIds].
 * The mutation callback is always invoked OUTSIDE the lock to prevent deadlock (Pitfall 2), since
 * `mutateAndPublish` in [ReactiveEntityBase][net.transgressoft.lirp.entity.ReactiveEntityBase]
 * calls `clone()` which may access the same backing IDs.
 *
 * **Deep-copy requirement (D-11):** Entities using this delegate MUST deep-copy the backing ID
 * collection in their `clone()` implementation. Without a deep copy, the `mutateAndPublish`
 * equality check will always return true (entity before == entity after the mutation), silencing
 * all mutation events. Example: `copy(itemIds = ArrayList(itemIds))` for a list field.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 * @param idSetter optional lambda to write mutated IDs back to the owning entity's serializable field
 */
abstract class AbstractMutableAggregateCollectionRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
    private val idSetter: ((Collection<K>) -> Unit)?
) : AbstractAggregateCollectionRefDelegate<K, E>(),
    MutableReactiveEntityCollectionReference<K, E> {

    /** Guards all reads and writes to [backingIds]. */
    protected val lock = ReentrantLock()

    @Volatile
    private var mutationCallback: ((() -> Unit) -> Unit)? = null

    /**
     * The mutable backing ID collection, initialized from the [idProvider] at construction time.
     * Subclasses provide a concrete typed implementation (e.g., [ArrayList] or [LinkedHashSet]).
     * All access must be guarded by [lock].
     */
    protected abstract val backingIds: MutableCollection<K>

    /**
     * Injects the mutation callback that triggers event emission on the owning entity.
     * Called by [RegistryBase.bindEntityRefs] after registry binding.
     *
     * The callback receives the `idSetter` invocation as a lambda so it can be executed
     * INSIDE [ReactiveEntityBase.mutateAndPublish], enabling correct before/after comparison
     * for event emission: the entity field is updated by `applyMutation` during the publish cycle,
     * so the clone captured before reflects the pre-mutation state.
     */
    internal fun bindMutationCallback(callback: (() -> Unit) -> Unit) {
        mutationCallback = callback
    }

    override fun provideIds(): Collection<K> = lock.withLock { ArrayList(backingIds) }

    override val referenceIds: Collection<K> get() = lock.withLock { ArrayList(backingIds) }

    /**
     * Invokes the mutation callback (or [idSetter] directly if no callback is bound) with [snapshot].
     *
     * If the callback throws (e.g. entity is closed), the exception propagates to the caller
     * so that it can roll back [backingIds] to the pre-mutation state.
     */
    private fun invokeCallback(snapshot: Collection<K>) {
        val cb = mutationCallback
        if (cb != null) {
            cb.invoke { idSetter?.invoke(snapshot) }
        } else {
            idSetter?.invoke(snapshot)
        }
    }

    /**
     * Executes [action] on [backingIds] under the lock, then invokes the mutation callback outside the lock.
     *
     * If the callback throws (e.g. entity is closed), [backingIds] is rolled back to the pre-mutation
     * snapshot to prevent state divergence between delegate and entity.
     *
     * The callback is invoked outside the lock to prevent deadlock: `mutateAndPublish`
     * calls `clone()` which may re-enter the same lock to snapshot [backingIds].
     */
    protected fun mutate(action: MutableCollection<K>.() -> Boolean): Boolean {
        val changed: Boolean
        val newSnapshot: Collection<K>
        val rollback: Collection<K>
        lock.lock()
        try {
            rollback = ArrayList(backingIds)
            changed = backingIds.action()
            newSnapshot = if (changed) ArrayList(backingIds) else emptyList()
        } finally {
            lock.unlock()
        }
        if (changed) {
            try {
                invokeCallback(newSnapshot)
            } catch (ex: Exception) {
                // Rollback backingIds to pre-mutation state if the callback fails
                // (e.g. entity is closed and mutateAndPublish throws)
                lock.withLock {
                    backingIds.clear()
                    backingIds.addAll(rollback)
                }
                throw ex
            }
        }
        return changed
    }

    override fun clear() {
        val rollback: Collection<K>
        lock.lock()
        try {
            rollback = ArrayList(backingIds)
            backingIds.clear()
        } finally {
            lock.unlock()
        }
        try {
            invokeCallback(emptyList())
        } catch (ex: Exception) {
            lock.withLock {
                backingIds.clear()
                backingIds.addAll(rollback)
            }
            throw ex
        }
    }

    /**
     * Adds all [elements] to this collection by delegating to [add] for each element.
     *
     * Each successful addition triggers an independent mutation callback and event emission.
     * This per-element semantics enables fine-grained tracking at the cost of N callbacks for N elements.
     */
    override fun addAll(elements: Collection<E>): Boolean {
        var changed = false
        for (e in elements) {
            if (add(e))
                changed = true
        }
        return changed
    }

    /**
     * Removes all [elements] from this collection by delegating to [remove] for each element.
     *
     * Each successful removal triggers an independent mutation callback and event emission.
     * This per-element semantics enables fine-grained tracking at the cost of N callbacks for N elements.
     */
    override fun removeAll(elements: Collection<E>): Boolean {
        var changed = false
        for (e in elements) {
            if (remove(e))
                changed = true
        }
        return changed
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val idsToKeep = elements.map { it.id }.toSet()
        val changed: Boolean
        val newSnapshot: Collection<K>
        val rollback: Collection<K>
        lock.lock()
        try {
            rollback = ArrayList(backingIds)
            changed = backingIds.retainAll(idsToKeep)
            newSnapshot =
                if (changed)
                    ArrayList(backingIds)
                else
                    emptyList()
        } finally {
            lock.unlock()
        }
        if (changed) {
            try {
                invokeCallback(newSnapshot)
            } catch (ex: Exception) {
                lock.withLock {
                    backingIds.clear()
                    backingIds.addAll(rollback)
                }
                throw ex
            }
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

    override fun getValue(thisRef: Any?, property: KProperty<*>): MutableReactiveEntityCollectionReference<K, E> = this
}
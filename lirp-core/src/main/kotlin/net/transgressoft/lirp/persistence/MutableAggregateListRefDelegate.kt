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
import kotlin.concurrent.withLock
import kotlin.reflect.KProperty

/**
 * Property delegate that implements a lazily-resolved, mutable aggregate reference to an ordered list
 * of entities stored in a [Registry]. Preserves insertion order and allows duplicate IDs (bag semantics).
 *
 * Returned by the [mutableAggregateList] factory function for use with Kotlin property delegation.
 * The internal backing list is owned by this delegate and initialized from [initialIds] at
 * construction time; subsequent mutations are reflected directly in [referenceIds].
 *
 * See [AbstractMutableAggregateCollectionRefDelegate] for shared behavior: locking,
 * mutation callback injection, and the deep-copy requirement.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 * @param initialIds the initial list of referenced entity IDs at construction time
 */
internal class MutableAggregateListRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
    initialIds: List<K>
) : AbstractMutableAggregateCollectionRefDelegate<K, E>(),
    LirpDelegate {

    override val backingIds: MutableList<K> = ArrayList(initialIds)

    override fun add(element: E): Boolean = mutate { add(element.id) }

    override fun remove(element: E): Boolean = mutate { remove(element.id) }

    override fun iterator(): MutableIterator<E> = resolveAll().toMutableList().iterator()

    override val referenceIds: List<K> get() = lock.withLock { ArrayList(backingIds) }

    override fun resolveAll(): List<E> {
        val reg = boundRegistry() ?: return emptyList()
        return lock.withLock { ArrayList(backingIds) }.mapNotNull { reg.findById(it).orElse(null) }
    }

    internal fun setAt(index: Int, id: K): Boolean =
        mutate {
            (this as MutableList<K>)[index] = id
            true
        }

    internal fun addAt(index: Int, id: K) = mutateVoid { (this as MutableList<K>).add(index, id) }

    internal fun removeAtIndex(index: Int): K {
        var removed: K? = null
        mutate {
            removed = (this as MutableList<K>).removeAt(index)
            true
        }
        return removed!!
    }

    internal fun addAllAt(index: Int, ids: List<K>) =
        mutateVoid {
            (this as MutableList<K>).addAll(index, ids)
        }
}

/**
 * Proxy that exposes a [MutableAggregateListRefDelegate] as a standard [MutableList].
 *
 * Holds [innerDelegate] for registry binding, ID tracking, and reactive event emission.
 * All indexed mutations ([set], [add], [removeAt]) route through the delegate's locked,
 * callback-aware helpers so that mutation events fire correctly.
 *
 * Inheriting from [AbstractMutableList] provides `subList()`, `listIterator()`, and bulk operations
 * for free — they all funnel through the four override methods defined here.
 *
 * Implements [LirpDelegate] so that [RegistryBase] and [LirpEntitySerializer] can detect and
 * unwrap this proxy to reach the inner delegate.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 */
class MutableAggregateListProxy<K : Comparable<K>, E : IdentifiableEntity<K>>
    internal constructor(
        internal val innerDelegate: MutableAggregateListRefDelegate<K, E>
    ) : AbstractMutableList<E>(), AggregateCollectionRef<K, E> by innerDelegate, LirpDelegate {

        override val size: Int get() = innerDelegate.size

        override fun get(index: Int): E {
            val ids = innerDelegate.referenceIds
            val id = ids[index]
            val reg =
                innerDelegate.boundRegistryInternal()
                    ?: throw NoSuchElementException("Aggregate collection not yet bound to a registry")
            return reg.findById(id).orElseThrow { NoSuchElementException("Entity(id=$id) not found in registry") }
        }

        override fun set(index: Int, element: E): E {
            val old = get(index)
            innerDelegate.setAt(index, element.id)
            return old
        }

        override fun add(index: Int, element: E) {
            innerDelegate.addAt(index, element.id)
            modCount++
        }

        override fun removeAt(index: Int): E {
            val old = get(index)
            innerDelegate.removeAtIndex(index)
            modCount++
            return old
        }

        // Delegate batch operations to innerDelegate for single-event emission semantics
        override fun addAll(elements: Collection<E>): Boolean {
            val changed = innerDelegate.addAll(elements)
            if (changed) modCount++
            return changed
        }

        override fun addAll(index: Int, elements: Collection<E>): Boolean {
            if (elements.isEmpty()) return false
            innerDelegate.addAllAt(index, elements.map { it.id })
            modCount++
            return true
        }

        override fun removeAll(elements: Collection<E>): Boolean {
            val changed = innerDelegate.removeAll(elements)
            if (changed) modCount++
            return changed
        }

        override fun retainAll(elements: Collection<E>): Boolean {
            val changed = innerDelegate.retainAll(elements)
            if (changed) modCount++
            return changed
        }

        override fun clear() {
            if (innerDelegate.size > 0) {
                innerDelegate.clear()
                modCount++
            }
        }

        operator fun getValue(thisRef: Any?, property: KProperty<*>): MutableAggregateListProxy<K, E> = this
    }

/**
 * Creates a property delegate for a mutable ordered aggregate collection reference.
 *
 * The returned object is a [MutableList] proxy that wraps an internal delegate owning the mutable
 * backing ID list, initialized from [initialIds] at property delegation time. Add, remove, and
 * indexed mutation operations update the internal list and trigger mutation event emission on the
 * owning entity after registry binding. Duplicate IDs are allowed (bag semantics).
 *
 * IMPORTANT: Entities using this delegate MUST deep-copy the backing list field in `clone()`:
 * ```
 * copy(itemIds = ArrayList(items.referenceIds.toList()))
 * ```
 * Without a deep copy, the `mutateAndPublish` equality check will always return true and
 * mutation events will never be emitted.
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type, must extend [IdentifiableEntity]
 * @param initialIds the initial list of referenced entity IDs
 * @return a [MutableList] property delegate for ordered mutable list references
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> mutableAggregateList(
    initialIds: List<K> = emptyList()
): MutableAggregateListProxy<K, E> = MutableAggregateListProxy(MutableAggregateListRefDelegate(initialIds))
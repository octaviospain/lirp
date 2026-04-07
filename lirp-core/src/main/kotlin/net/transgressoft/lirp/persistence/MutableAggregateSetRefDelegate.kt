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
 * Property delegate that implements a lazily-resolved, mutable aggregate reference to a unique set
 * of entities stored in a [Registry]. Preserves insertion order (via [LinkedHashSet]) and enforces
 * uniqueness — duplicate entity IDs are not stored.
 *
 * Returned by the [mutableAggregateSet] factory function for use with Kotlin property delegation.
 * The internal backing set is owned by this delegate and initialized from [initialIds] at
 * construction time; subsequent mutations are reflected directly in [referenceIds].
 *
 * See [AbstractMutableAggregateCollectionRefDelegate] for shared behavior: locking,
 * mutation callback injection, and the deep-copy requirement.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 * @param initialIds the initial set of referenced entity IDs at construction time
 */
internal class MutableAggregateSetRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
    initialIds: Set<K>
) : AbstractMutableAggregateCollectionRefDelegate<K, E>(),
    LirpDelegate {

    override val backingIds: MutableSet<K> = LinkedHashSet(initialIds)

    override fun add(element: E): Boolean = mutate { add(element.id) }

    override fun remove(element: E): Boolean = mutate { remove(element.id) }

    override fun iterator(): MutableIterator<E> = resolveAll().toMutableList().iterator()

    override val referenceIds: Set<K> get() = lock.withLock { LinkedHashSet(backingIds) }

    override fun resolveAll(): Set<E> {
        val reg = boundRegistry() ?: return emptySet()
        return lock.withLock { LinkedHashSet(backingIds) }
            .mapNotNull { reg.findById(it).orElse(null) }
            .toCollection(LinkedHashSet())
    }
}

/**
 * Proxy that exposes a [MutableAggregateSetRefDelegate] as a standard [MutableSet].
 *
 * Composes the inner delegate via [AggregateCollectionRef] delegation so that [referenceIds],
 * [resolveAll], and cascade operations are forwarded. [add] and [remove] route through the
 * delegate's locked, callback-aware mutation helpers so that reactive events fire correctly.
 *
 * The [iterator] takes a snapshot of IDs at the time of iteration; [MutableIterator.remove] removes
 * the entity from the backing ID set via the delegate's locked [remove] path.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 */
class MutableAggregateSetProxy<K : Comparable<K>, E : IdentifiableEntity<K>>
    internal constructor(
        internal val innerDelegate: MutableAggregateSetRefDelegate<K, E>
    ) : AbstractMutableSet<E>(), AggregateCollectionRef<K, E> by innerDelegate, LirpDelegate {

        override val size: Int get() = innerDelegate.size

        override fun add(element: E): Boolean = innerDelegate.add(element)

        override fun iterator(): MutableIterator<E> {
            val snapshot = innerDelegate.referenceIds.toList()
            val reg = innerDelegate.boundRegistryInternal()
            return object : MutableIterator<E> {
                private val idIterator = snapshot.iterator()
                private var lastReturned: E? = null

                override fun hasNext() = idIterator.hasNext()

                override fun next(): E {
                    val id = idIterator.next()
                    val entity =
                        reg?.findById(id)?.orElseThrow {
                            NoSuchElementException("Entity(id=$id) not found in registry")
                        } ?: throw NoSuchElementException("Registry not bound")
                    lastReturned = entity
                    return entity
                }

                override fun remove() {
                    val entity = lastReturned ?: throw IllegalStateException("next() not yet called or already removed")
                    innerDelegate.remove(entity)
                    lastReturned = null
                }
            }
        }

        override fun contains(element: E): Boolean = innerDelegate.contains(element)

        // Delegate batch operations to innerDelegate for single-event emission semantics
        override fun addAll(elements: Collection<E>): Boolean = innerDelegate.addAll(elements)

        override fun removeAll(elements: Collection<E>): Boolean = innerDelegate.removeAll(elements)

        override fun retainAll(elements: Collection<E>): Boolean = innerDelegate.retainAll(elements)

        override fun clear() = innerDelegate.clear()

        operator fun getValue(thisRef: Any?, property: KProperty<*>): MutableAggregateSetProxy<K, E> = this
    }

/**
 * Creates a property delegate for a mutable unique-set aggregate collection reference.
 *
 * The returned object is a [MutableSet] proxy that wraps an internal delegate owning a mutable
 * backing ID set ([LinkedHashSet]), initialized from [initialIds] at property delegation time.
 * Add and remove operations update the internal set and trigger mutation event emission on the
 * owning entity after registry binding. Uniqueness is enforced — duplicate IDs are silently ignored.
 *
 * See [mutableAggregateList] for details on `clone()` deep-copy requirements.
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type, must extend [IdentifiableEntity]
 * @param initialIds the initial set of referenced entity IDs
 * @return a [MutableSet] property delegate for unique mutable set references
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> mutableAggregateSet(
    initialIds: Set<K> = emptySet()
): MutableAggregateSetProxy<K, E> = MutableAggregateSetProxy(MutableAggregateSetRefDelegate(initialIds))
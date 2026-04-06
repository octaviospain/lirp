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
 * Creates a property delegate for a mutable unique-set aggregate collection reference.
 *
 * The returned delegate owns a mutable backing ID set ([LinkedHashSet]), initialized from [initialIds]
 * at property delegation time. Calls to [MutableAggregateCollectionRef.add] and
 * [MutableAggregateCollectionRef.remove] update the internal set. After registry binding,
 * mutations also trigger mutation event emission on the owning entity. Uniqueness is enforced —
 * duplicate IDs are silently ignored.
 *
 * See [mutableAggregateList] for details on `clone()` deep-copy requirements.
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type, must extend [IdentifiableEntity]
 * @param initialIds the initial set of referenced entity IDs
 * @return a [MutableAggregateCollectionRef] delegate for unique mutable set references
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> mutableAggregateSet(
    initialIds: Set<K> = emptySet()
): MutableAggregateCollectionRef<K, E> = MutableAggregateSetRefDelegate(initialIds)
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
}

/**
 * Creates a property delegate for a mutable ordered aggregate collection reference.
 *
 * The returned delegate owns a mutable backing ID list, initialized from [initialIds] at property
 * delegation time. Calls to [MutableAggregateCollectionRef.add] and
 * [MutableAggregateCollectionRef.remove] update the internal list. After registry
 * binding, mutations also trigger mutation event emission on the owning entity.
 * Duplicate IDs are allowed (bag semantics).
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
 * @return a [MutableAggregateCollectionRef] delegate for ordered mutable list references
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> mutableAggregateList(
    initialIds: List<K> = emptyList()
): MutableAggregateCollectionRef<K, E> = MutableAggregateListRefDelegate(initialIds)
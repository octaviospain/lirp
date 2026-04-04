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
 * The internal backing list is owned by this delegate and initialized from the `idProvider` at
 * construction time; subsequent mutations are reflected directly in [referenceIds].
 *
 * See [AbstractMutableAggregateCollectionRefDelegate] for shared behavior: locking, idSetter write-back,
 * mutation callback injection, and the deep-copy requirement.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 * @param idProvider lambda that returns the initial list of referenced entity IDs at construction time
 * @param idSetter optional lambda to write mutated IDs back to the owning entity's serializable field
 */
internal class MutableAggregateListRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
    idProvider: () -> List<K>,
    idSetter: ((List<K>) -> Unit)?
) : AbstractMutableAggregateCollectionRefDelegate<K, E>(
        idSetter?.let { setter -> { ids -> setter(ids.toList()) } }
    ) {

    override val backingIds: MutableList<K> = ArrayList(idProvider())

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
 * The returned delegate owns a mutable backing ID list, initialized from [idProvider] at property
 * delegation time. Calls to [MutableReactiveEntityCollectionReference.add] and
 * [MutableReactiveEntityCollectionReference.remove] update the internal list and sync changes back
 * to the entity's serializable field via [idSetter]. After registry binding, mutations also trigger
 * mutation event emission on the owning entity. Duplicate IDs are allowed (bag semantics).
 *
 * The [idSetter] is optional (default `null`). If omitted, mutations update the internal backing
 * store but are NOT written back to the entity field — serialization will not reflect runtime changes.
 * Document this limitation in entity KDoc when using without [idSetter].
 *
 * IMPORTANT: Entities using this delegate MUST deep-copy the backing list field in `clone()`:
 * ```
 * copy(itemIds = ArrayList(itemIds))
 * ```
 * Without a deep copy, the `mutateAndPublish` equality check will always return true and
 * mutation events will never be emitted.
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type, must extend [IdentifiableEntity]
 * @param idProvider lambda returning the current list of referenced entity IDs
 * @param idSetter optional lambda to write mutated IDs back to the owning entity's serializable field
 * @return an [AbstractMutableAggregateCollectionRefDelegate] implementing [MutableReactiveEntityCollectionReference]
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> mutableAggregateList(
    idProvider: () -> List<K>,
    idSetter: ((List<K>) -> Unit)? = null
): AbstractMutableAggregateCollectionRefDelegate<K, E> = MutableAggregateListRefDelegate(idProvider, idSetter)
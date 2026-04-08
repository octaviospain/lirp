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
import kotlin.reflect.KProperty

/**
 * Property delegate that implements a lazily-resolved aggregate reference to an ordered list of
 * entities stored in a [Registry]. Preserves insertion order and allows duplicate IDs.
 *
 * Returned by the [aggregateList] factory function for use with Kotlin property delegation.
 * Resolution is always performed fresh against the bound [Registry] — no caching is applied.
 *
 * See [AbstractAggregateCollectionRefDelegate] for shared behavior: binding, cascade, and thread safety.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 * @param initialIds the list of referenced entity IDs at construction time
 */
internal class AggregateListRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
    private val initialIds: List<K>
) : AbstractAggregateCollectionRefDelegate<K, E>(),
    LirpDelegate {

    override fun provideIds(): List<K> = initialIds

    override val referenceIds: List<K> get() = initialIds

    override fun resolveAll(): List<E> {
        val reg = boundRegistry() ?: return emptyList()
        return initialIds.mapNotNull { reg.findById(it).orElse(null) }
    }
}

/**
 * Proxy that exposes an [AggregateListRefDelegate] as a standard read-only [List].
 *
 * Composes the inner delegate via [AggregateCollectionRef] delegation so that [referenceIds],
 * [resolveAll], and cascade operations are forwarded. [get] resolves each entity from the bound
 * registry on demand; [size] reads from the delegate's [referenceIds].
 *
 * Inheriting from [AbstractList] provides all iteration and bulk-query operations for free.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 */
class AggregateListProxy<K : Comparable<K>, E : IdentifiableEntity<K>>
    internal constructor(
        internal val innerDelegate: AggregateListRefDelegate<K, E>
    ) : AbstractList<E>(), AggregateCollectionRef<K, E> by innerDelegate, LirpDelegate {

        override val size: Int
            get() = if (innerDelegate.boundRegistryInternal() != null) innerDelegate.referenceIds.size else 0

        override fun get(index: Int): E {
            val ids = innerDelegate.referenceIds
            val id = ids[index]
            val reg =
                innerDelegate.boundRegistryInternal()
                    ?: throw NoSuchElementException("Aggregate collection not yet bound to a registry")
            return reg.findById(id).orElseThrow { NoSuchElementException("Entity(id=$id) not found in registry") }
        }

        operator fun getValue(thisRef: Any?, property: KProperty<*>): AggregateListProxy<K, E> = this
    }

/**
 * Creates a property delegate that declares a typed aggregate reference to an ordered list of entities.
 *
 * The returned object is a [List] proxy. The [initialIds] list is captured at entity construction
 * time and used for resolution and as the source of [AggregateCollectionRef.referenceIds].
 * Duplicate IDs are preserved; order is maintained.
 *
 * **Requires KSP** — annotate the delegated property with [@Aggregate][Aggregate]
 * so the KSP processor generates the required `{ClassName}_LirpRefAccessor` class.
 *
 * Example:
 * ```kotlin
 * @Aggregate(onDelete = CascadeAction.NONE)
 * @Transient
 * val items by aggregateList<Int, AudioItem>(itemIds)
 * ```
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type, must extend [IdentifiableEntity]
 * @param initialIds the list of referenced entity IDs
 * @return a [List] property delegate for ordered list references
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateList(
    initialIds: List<K> = emptyList()
): AggregateListProxy<K, E> = AggregateListProxy(AggregateListRefDelegate(initialIds))
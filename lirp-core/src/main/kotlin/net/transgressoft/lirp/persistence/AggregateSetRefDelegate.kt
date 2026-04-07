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
 * Property delegate that implements a lazily-resolved aggregate reference to a unique set of
 * entities stored in a [Registry]. Enforces uniqueness — duplicate IDs are not allowed.
 *
 * Returned by the [aggregateSet] factory function for use with Kotlin property delegation.
 * Resolution is always performed fresh against the bound [Registry] — no caching is applied.
 *
 * See [AbstractAggregateCollectionRefDelegate] for shared behavior: binding, cascade, and thread safety.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 * @param initialIds the set of referenced entity IDs at construction time
 */
internal class AggregateSetRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
    private val initialIds: Set<K>
) : AbstractAggregateCollectionRefDelegate<K, E>(),
    LirpDelegate {

    override fun provideIds(): Set<K> = initialIds

    override val referenceIds: Set<K> get() = initialIds

    override fun resolveAll(): Set<E> {
        val reg = boundRegistry() ?: return emptySet()
        return initialIds.mapNotNull { reg.findById(it).orElse(null) }.toSet()
    }
}

/**
 * Proxy that exposes an [AggregateSetRefDelegate] as a standard read-only [Set].
 *
 * Composes the inner delegate via [AggregateCollectionRef] delegation so that [referenceIds],
 * [resolveAll], and cascade operations are forwarded. [iterator] resolves entities from the bound
 * registry on demand, skipping IDs that are no longer present (soft-delete friendly).
 *
 * Inheriting from [AbstractSet] provides all bulk-query operations for free.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 */
class AggregateSetProxy<K : Comparable<K>, E : IdentifiableEntity<K>>
    internal constructor(
        internal val innerDelegate: AggregateSetRefDelegate<K, E>
    ) : AbstractSet<E>(), AggregateCollectionRef<K, E> by innerDelegate, LirpDelegate {

        override val size: Int get() = innerDelegate.referenceIds.size

        override fun iterator(): Iterator<E> {
            val reg = innerDelegate.boundRegistryInternal() ?: return emptyList<E>().iterator()
            return innerDelegate.referenceIds.mapNotNull { reg.findById(it).orElse(null) }.iterator()
        }

        operator fun getValue(thisRef: Any?, property: KProperty<*>): AggregateSetProxy<K, E> = this
    }

/**
 * Creates a property delegate that declares a typed aggregate reference to a unique set of entities.
 *
 * The returned object is a [Set] proxy. The [initialIds] set is captured at entity construction
 * time and used for resolution and as the source of [AggregateCollectionRef.referenceIds].
 * Duplicate IDs are not permitted — Set semantics are enforced both in the initial IDs and the
 * resolved result.
 *
 * **Requires KSP** — annotate the delegated property with [@Aggregate][Aggregate]
 * so the KSP processor generates the required `{ClassName}_LirpRefAccessor` class.
 *
 * Example:
 * ```kotlin
 * @Aggregate(onDelete = CascadeAction.CASCADE)
 * @Transient
 * val playlists by aggregateSet<Long, Playlist>(playlistIds)
 * ```
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type, must extend [IdentifiableEntity]
 * @param initialIds the set of referenced entity IDs
 * @return a [Set] property delegate for unique set references
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateSet(
    initialIds: Set<K> = emptySet()
): AggregateSetProxy<K, E> = AggregateSetProxy(AggregateSetRefDelegate(initialIds))
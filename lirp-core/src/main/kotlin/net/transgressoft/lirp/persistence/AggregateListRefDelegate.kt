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

/**
 * Property delegate that implements a lazily-resolved aggregate reference to an ordered list of
 * entities stored in a [Registry]. Preserves insertion order and allows duplicate IDs.
 *
 * Returned by the [aggregateList] factory function for use with Kotlin property delegation.
 * Resolution is always performed fresh against the bound [Registry] — no caching is applied.
 *
 * See [AbstractAggregateCollectionRefDelegate] for shared behavior: binding, cascade, and thread safety.
 *
 * Example:
 * ```kotlin
 * class Playlist(override val id: Long, val itemIds: List<Int>) : ReactiveEntityBase<Long, Playlist>() {
 *     @Aggregate(onDelete = CascadeAction.NONE)
 *     @Transient
 *     val items by aggregateList<Int, AudioItem> { itemIds }
 * }
 *
 * // After adding the playlist to its repository:
 * val resolved: List<AudioItem> = playlist.items.resolveAll()
 * ```
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 * @param idProvider lambda that returns the current list of referenced entity IDs
 */
internal class AggregateListRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
    private val idProvider: () -> List<K>
) : AbstractAggregateCollectionRefDelegate<K, E>() {

    override fun provideIds(): List<K> = idProvider()

    override val referenceIds: List<K> get() = idProvider()

    override fun resolveAll(): List<E> {
        val reg = boundRegistry() ?: return emptyList()
        return idProvider().mapNotNull { reg.findById(it).orElse(null) }
    }
}

/**
 * Creates a property delegate that declares a typed aggregate reference to an ordered list of entities.
 *
 * The [idProvider] lambda is captured at entity construction time and evaluated on each
 * [ReactiveEntityCollectionReference.resolveAll] call to obtain the current referenced entity IDs.
 * Duplicate IDs are preserved; order is maintained.
 *
 * **Requires KSP** — annotate the delegated property with [@Aggregate][Aggregate]
 * so the KSP processor generates the required `{ClassName}_LirpRefAccessor` class.
 *
 * Example:
 * ```kotlin
 * @Aggregate(onDelete = CascadeAction.NONE)
 * @Transient
 * val items by aggregateList<Int, AudioItem> { itemIds }
 * ```
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type, must extend [IdentifiableEntity]
 * @param idProvider lambda returning the current list of referenced entity IDs
 * @return an [AbstractAggregateCollectionRefDelegate] implementing both [ReactiveEntityCollectionReference] and [ReadOnlyProperty][kotlin.properties.ReadOnlyProperty]
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateList(
    idProvider: () -> List<K>
): AbstractAggregateCollectionRefDelegate<K, E> = AggregateListRefDelegate(idProvider)
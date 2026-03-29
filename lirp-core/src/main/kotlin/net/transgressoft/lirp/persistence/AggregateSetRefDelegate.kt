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
 * Property delegate that implements a lazily-resolved aggregate reference to a unique set of
 * entities stored in a [Registry]. Enforces uniqueness — duplicate IDs are not allowed.
 *
 * Returned by the [aggregateSet] factory function for use with Kotlin property delegation.
 * Resolution is always performed fresh against the bound [Registry] — no caching is applied.
 *
 * See [AbstractAggregateCollectionRefDelegate] for shared behavior: binding, cascade, and thread safety.
 *
 * Example:
 * ```kotlin
 * class Library(override val id: Long, val playlistIds: Set<Long>) : ReactiveEntityBase<Long, Library>() {
 *     @Aggregate(onDelete = CascadeAction.CASCADE)
 *     @Transient
 *     val playlists by aggregateSet<Long, Playlist> { playlistIds }
 * }
 *
 * // After adding the library to its repository:
 * val resolved: Set<Playlist> = library.playlists.resolveAll()
 * ```
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 * @param idProvider lambda that returns the current set of referenced entity IDs
 */
internal class AggregateSetRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
    private val idProvider: () -> Set<K>
) : AbstractAggregateCollectionRefDelegate<K, E>() {

    override fun provideIds(): Set<K> = idProvider()

    override val referenceIds: Set<K> get() = idProvider()

    override fun resolveAll(): Set<E> {
        val reg = boundRegistry() ?: return emptySet()
        return idProvider().mapNotNull { reg.findById(it).orElse(null) }.toSet()
    }
}

/**
 * Creates a property delegate that declares a typed aggregate reference to a unique set of entities.
 *
 * The [idProvider] lambda is captured at entity construction time and evaluated on each
 * [ReactiveEntityCollectionReference.resolveAll] call to obtain the current referenced entity IDs.
 * Duplicate IDs are not permitted — Set semantics are enforced both in the ID provider and the resolved result.
 *
 * **Requires KSP** — annotate the delegated property with [@Aggregate][Aggregate]
 * so the KSP processor generates the required `{ClassName}_LirpRefAccessor` class.
 *
 * Example:
 * ```kotlin
 * @Aggregate(onDelete = CascadeAction.CASCADE)
 * @Transient
 * val playlists by aggregateSet<Long, Playlist> { playlistIds }
 * ```
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type, must extend [IdentifiableEntity]
 * @param idProvider lambda returning the current set of referenced entity IDs
 * @return an [AbstractAggregateCollectionRefDelegate] implementing both [ReactiveEntityCollectionReference] and [ReadOnlyProperty][kotlin.properties.ReadOnlyProperty]
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateSet(
    idProvider: () -> Set<K>
): AbstractAggregateCollectionRefDelegate<K, E> = AggregateSetRefDelegate(idProvider)
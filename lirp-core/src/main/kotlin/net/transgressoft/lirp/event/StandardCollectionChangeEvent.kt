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

package net.transgressoft.lirp.event

/**
 * Standard data class implementation of [CollectionChangeEvent].
 *
 * Carries the diff of a mutable aggregate collection mutation: elements added and elements removed
 * in a single change operation. Use the companion factory functions to create instances for common
 * mutation types rather than invoking the constructor directly.
 *
 * @param E the element type of the collection
 * @property type the kind of collection mutation that occurred
 * @property added elements inserted into the collection in this change
 * @property removed elements removed from the collection in this change
 */
data class StandardCollectionChangeEvent<E>(
    override val type: CollectionChangeEvent.Type,
    override val added: List<E>,
    override val removed: List<E>
) : CollectionChangeEvent<E> {

    companion object {

        /** Creates an [CollectionChangeEvent.Type.ADD] event for the given [elements]. */
        fun <E> add(elements: List<E>) =
            StandardCollectionChangeEvent(CollectionChangeEvent.Type.ADD, added = elements, removed = emptyList())

        /** Creates a [CollectionChangeEvent.Type.REMOVE] event for the given [elements]. */
        fun <E> remove(elements: List<E>) =
            StandardCollectionChangeEvent(CollectionChangeEvent.Type.REMOVE, added = emptyList(), removed = elements)

        /** Creates a [CollectionChangeEvent.Type.REPLACE] event with explicit [added] and [removed] lists. */
        fun <E> replace(added: List<E>, removed: List<E>) =
            StandardCollectionChangeEvent(CollectionChangeEvent.Type.REPLACE, added = added, removed = removed)

        /** Creates a [CollectionChangeEvent.Type.CLEAR] event carrying all [removed] elements that were in the collection. */
        fun <E> clear(removed: List<E>) =
            StandardCollectionChangeEvent(CollectionChangeEvent.Type.CLEAR, added = emptyList(), removed = removed)
    }
}
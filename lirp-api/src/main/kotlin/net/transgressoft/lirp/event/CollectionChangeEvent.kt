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
 * Represents a typed, collection-level change event with diff semantics.
 *
 * A [CollectionChangeEvent] is emitted by mutable aggregate collection delegates when items are
 * added, removed, replaced, or the collection is cleared. It carries the diff — the precise set of
 * elements that were added and removed — rather than a full snapshot, allowing subscribers to react
 * efficiently to incremental changes.
 *
 * The context (i.e., which collection property on the parent entity changed) is provided by
 * [AggregateMutationEvent.refName] when this event is wrapped and emitted as a bubble-up event.
 *
 * @param E the element type of the collection; unbounded to support any aggregate element type
 */
interface CollectionChangeEvent<E> : LirpEvent<CollectionChangeEvent.Type> {

    /**
     * Classifies the kind of mutation that occurred on the collection.
     *
     * Each value carries codes in the 400 range to avoid overlap with [CrudEvent.Type] (100–900)
     * and [MutationEvent.Type] (301).
     */
    enum class Type(override val code: Int) : EventType {
        /** Elements were inserted into the collection. */
        ADD(401),

        /** Elements were removed from the collection. */
        REMOVE(402),

        /** Elements were substituted: [CollectionChangeEvent.added] contains the new elements, [CollectionChangeEvent.removed] contains the old ones. */
        REPLACE(403),

        /** The collection was cleared: [CollectionChangeEvent.added] is empty, [CollectionChangeEvent.removed] contains all prior elements. */
        CLEAR(404)
    }

    /**
     * Elements added to the collection in this change.
     *
     * Empty for [Type.REMOVE] and [Type.CLEAR] events.
     */
    val added: List<E>

    /**
     * Elements removed from the collection in this change.
     *
     * Empty for [Type.ADD] events. For [Type.CLEAR], contains all elements that were in the
     * collection before clearing.
     */
    val removed: List<E>
}
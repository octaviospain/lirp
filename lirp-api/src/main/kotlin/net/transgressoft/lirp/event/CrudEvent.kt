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

import net.transgressoft.lirp.entity.IdentifiableEntity

/**
 * Represents a [LirpEvent] that carries a collection of [net.transgressoft.lirp.entity.IdentifiableEntity] objects
 * related to CRUD (Create, Read, Update, Delete) operations.
 *
 * CrudEvent serves as the base event type for all entity operations in the system,
 * providing a standardized way to carry entity data through the event pipeline.
 * These events typically originate from repository operations and are published
 * to subscribers interested in entity changes.
 *
 * @param K the type of the [net.transgressoft.lirp.entity.IdentifiableEntity] objects' id, which must be [Comparable]
 * @param T the type of the [net.transgressoft.lirp.entity.IdentifiableEntity] objects
 */
interface CrudEvent<K, out T: IdentifiableEntity<K>>: LirpEvent<CrudEvent.Type> where K: Comparable<K> {

    enum class Type(override val code: Int): EventType {
        CREATE(100),
        READ(200),
        UPDATE(300),
        DELETE(900);

        override fun toString() = "StandardDataEvent($name, $code)"
    }

    /**
     * A map of entities associated with this event, keyed by their identifiers.
     *
     * This contains the entities that are relevant to or affected by the event.
     * The specific meaning depends on the concrete event implementation.
     */
    val entities: Map<K, T>

    /**
     * Map of the entities before they were modified. Not empty only when the event is of type UPDATE
     */
    val oldEntities: Map<K, T>

    fun isCreate() = type == Type.CREATE

    fun isRead() = type == Type.READ

    fun isUpdate() = type == Type.UPDATE

    fun isDelete() = type == Type.DELETE
}
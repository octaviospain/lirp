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

package net.transgressoft.lirp.entity

/**
 * An entity that can be identified by an ID and a unique ID.
 *
 * @param K the type of the ID, which must be comparable
 */
interface IdentifiableEntity<K> : LirpEntity, Cloneable where K : Comparable<K> {
    /**
     * The id of the entity. It could be different for different [net.transgressoft.lirp.persistence.Repository]
     * implementations.
     */
    val id: K

    /**
     * The unique id of the entity. It should be unique across all entities of the same type and across all
     * [net.transgressoft.lirp.persistence.Repository] objects in which the entity could be stored.
     */
    val uniqueId: String

    public override fun clone(): IdentifiableEntity<K>
}

fun <K : Comparable<K>> List<IdentifiableEntity<K>>.toIds() = map { it.id }.toList()

fun <K : Comparable<K>> Set<IdentifiableEntity<K>>.toIds() = map { it.id }.toSet()
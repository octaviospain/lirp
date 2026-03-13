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

package net.transgressoft.lirp.persistence.json

import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.persistence.Repository
import java.io.Closeable
import java.io.File

/**
 * A specialized repository that stores entities in JSON format.
 *
 * This interface extends the [Repository] interface to provide persistence capabilities
 * for reactive entities using a JSON file as the storage medium. It manages the serialization
 * and deserialization of entities to and from the JSON format.
 *
 * @param K The type of the entity's identifier, which must be [Comparable]
 * @param R The type of reactive entity this repository manages
 */
interface JsonRepository<K : Comparable<K>, R : ReactiveEntity<K, R>> : Repository<K, R>, Closeable {
    /**
     * The JSON file where entity data is persisted.
     *
     * This property can be changed to redirect storage to a different file.
     */
    var jsonFile: File
}
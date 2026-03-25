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

import net.transgressoft.lirp.entity.ReactiveEntity
import java.io.Closeable

/**
 * Marker interface for repositories that persist entity state beyond the JVM lifetime.
 *
 * Extends [Repository] with [Closeable] lifecycle management. Concrete implementations such as
 * [net.transgressoft.lirp.persistence.json.JsonRepository] and future SQL-backed repositories
 * extend this interface to signal that they operate on a durable storage medium and must be
 * explicitly closed to release underlying resources.
 *
 * @param K The type of entity identifier, must be [Comparable]
 * @param R The type of reactive entity stored in this repository
 */
interface PersistentRepository<K : Comparable<K>, R : ReactiveEntity<K, R>> : Repository<K, R>, Closeable
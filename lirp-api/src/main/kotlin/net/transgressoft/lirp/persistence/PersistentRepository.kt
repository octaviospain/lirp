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
 * Interface for repositories that persist entity state beyond the JVM lifetime.
 *
 * Extends [Repository] with [Closeable] lifecycle management and on-demand loading. Concrete
 * implementations such as [net.transgressoft.lirp.persistence.json.JsonRepository] and future
 * SQL-backed repositories extend this interface to signal that they operate on a durable storage
 * medium and must be explicitly closed to release underlying resources.
 *
 * Repositories may be constructed without loading data immediately by passing `loadOnInit = false`
 * to the concrete constructor. In that case, [load] must be called explicitly before any mutating
 * operations.
 *
 * @param K The type of entity identifier, must be [Comparable]
 * @param R The type of reactive entity stored in this repository
 */
interface PersistentRepository<K : Comparable<K>, R : ReactiveEntity<K, R>> : Repository<K, R>, Closeable {

    /**
     * Loads entities from the backing store into memory.
     *
     * When `loadOnInit = true` (the default), this method is called automatically during
     * construction. When `loadOnInit = false`, callers must invoke this method explicitly before
     * performing any mutating operations.
     *
     * Events are suppressed during loading so subscribers do not receive CREATE or UPDATE
     * notifications for entities that already exist in the store.
     *
     * @throws IllegalStateException if called more than once on the same repository instance.
     */
    fun load()
}
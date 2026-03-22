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
 * A repository extends the [Registry] interface with lifecycle-management operations.
 *
 * While a [Registry] is read-only, a [Repository] allows entities to be removed and
 * the collection to be cleared. Entity creation happens via factory methods defined on
 * concrete subclasses — this is the repository-as-factory convention: the repository is
 * the sole entry point for adding entities so that registration, index construction, and
 * aggregate reference wiring all occur at one place.
 *
 * Concrete subclasses expose typed factory methods (e.g., `create(...)`, `register(...)`)
 * that call the protected `add()` implementation. Callers should depend on the specific
 * subclass when creating entities, and on [Repository] or [Registry] when only querying
 * or removing them.
 *
 * @param K The type of the entity's identifier, which must be [Comparable]
 * @param T The type of entities in the repository, which must implement [IdentifiableEntity]
 */
interface Repository<K, T: IdentifiableEntity<K>> : Registry<K, T> where K : Comparable<K> {
    /**
     * Removes the given entity from the repository.
     *
     * @param entity The entity to remove
     * @return True if the entity was removed, false if it wasn't found
     */
    fun remove(entity: T): Boolean

    /**
     * Operator overload for removing an entity using the minus operator.
     *
     * @param entity The entity to remove
     * @return True if the entity was removed, false if it wasn't found
     */
    operator fun minus(entity: T): Boolean = remove(entity)

    /**
     * Removes all given entities from the repository.
     *
     * @param entities The collection of entities to remove
     * @return True if any entity was removed, false otherwise
     */
    fun removeAll(entities: Collection<T>): Boolean

    /**
     * Operator overload for removing a set of entities using the minus operator.
     *
     * @param entities The collection of entities to remove
     * @return True if any entity was removed, false otherwise
     */
    operator fun minus(entities: Collection<T>): Boolean = removeAll(entities)

    /**
     * Removes all entities from the repository, leaving it empty.
     */
    fun clear()
}
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
 * A repository extends the [Registry] interface to provide a mutable collection of entities
 * that can be modified through add, remove, and replace operations.
 *
 * While a Registry is read-only, a Repository allows for full CRUD operations on entities,
 * making it suitable for contexts where entities need to be managed with complete lifecycle
 * control.
 *
 * @param K The type of the entity's identifier, which must be [Comparable]
 * @param T The type of entities in the repository, which must implement [IdentifiableEntity]
 */
interface Repository<K, T: IdentifiableEntity<K>> : Registry<K, T> where K : Comparable<K> {
    /**
     * Adds the given entity to the repository if it doesn't already exist.
     *
     * @param entity The entity to add
     * @return True if the entity was added, false if it already existed
     */
    fun add(entity: T): Boolean

    /**
     * Operator overload for adding an entity using the plus operator.
     *
     * @param entity The entity to add
     * @return True if the entity was added, false if it already existed
     */
    operator fun plus(entity: T): Boolean = add(entity)

    /**
     * Adds the given entity to the repository, replacing any existing entity with the same ID.
     *
     * @param entity The entity to add or replace
     * @return True if the entity was added or replaced an existing entity, false otherwise
     */
    fun addOrReplace(entity: T): Boolean

    /**
     * Adds all given entities to the repository, replacing any existing entities with the same IDs.
     *
     * This operation is atomic: if any entity fails to be processed (e.g., due to an indexing error),
     * all changes made during this call are rolled back and the repository is restored to its
     * state before the call. The exception that caused the failure is rethrown after rollback.
     *
     * @param entities The set of entities to add or replace
     * @return True if any entity was added or replaced, false otherwise
     */
    fun addOrReplaceAll(entities: Set<T>): Boolean

    /**
     * Operator overload for adding a set of entities using the plus operator.
     *
     * @param entities The set of entities to add
     * @return True if any entity was added, false otherwise
     */
    operator fun plus(entities: Set<T>): Boolean = addOrReplaceAll(entities)

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
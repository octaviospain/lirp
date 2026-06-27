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
 * While a [Registry] is read-only, a [Repository] allows entities to be added, removed,
 * and the collection to be cleared. Entity creation can happen via factory methods on
 * concrete subclasses (inheritance pattern) or by calling [add] directly through the
 * interface (composition pattern).
 *
 * @param K The type of the entity's identifier, which must be [Comparable]
 * @param T The type of entities in the repository, which must implement [IdentifiableEntity]
 */
interface Repository<K, T: IdentifiableEntity<K>> : Registry<K, T> where K : Comparable<K> {
    /**
     * Adds the given entity to this repository if no entity with the same ID already exists.
     *
     * @param entity The entity to add
     * @return `true` if the entity was added, `false` if an entity with the same ID is already present
     */
    fun add(entity: T): Boolean

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

    /**
     * Soft-deletes [entity] by setting its [net.transgressoft.lirp.entity.SoftDeletable.deletedAt]
     * to the current instant. The entity remains in memory and is excluded from default reads.
     *
     * Honors the cascade mode declared on every aggregate reference — both scalar
     * (`@ToOneAggregate`) and collection (`@ToManyAggregates`) references:
     * - **CASCADE**: propagates soft deletion to each referenced child.
     * - **RESTRICT**: blocks when at least one referenced child is still active (`deletedAt == null`).
     *   All RESTRICT checks are evaluated before any CASCADE mutation, so the check reflects the
     *   pre-cascade state of children. Throws [IllegalStateException] when a RESTRICT child is active.
     * - **DETACH** and **NONE**: leave referenced children unchanged.
     *
     * Emits [net.transgressoft.lirp.event.CrudEvent.Type.SOFT_DELETE] on success.
     *
     * @param entity The entity to soft-delete; must implement [net.transgressoft.lirp.entity.MutableSoftDeletable]
     * @return `true` if the entity was soft-deleted, `false` if it was not found or was already soft-deleted
     * @throws IllegalStateException if a RESTRICT cascade check finds an active referenced child
     */
    fun softDelete(entity: T): Boolean = throw UnsupportedOperationException("This repository does not support soft delete")

    /**
     * Restores a soft-deleted [entity] by clearing its
     * [net.transgressoft.lirp.entity.SoftDeletable.deletedAt] to `null`.
     *
     * Emits [net.transgressoft.lirp.event.CrudEvent.Type.RESTORE] on success.
     *
     * @param entity The entity to restore; must implement [net.transgressoft.lirp.entity.MutableSoftDeletable]
     * @return `true` if the entity was restored, `false` if it was not found or was not soft-deleted
     */
    fun restore(entity: T): Boolean = throw UnsupportedOperationException("This repository does not support soft delete")
}
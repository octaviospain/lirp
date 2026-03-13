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
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.CrudEvent.Type.DELETE
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.StandardCrudEvent.Create
import net.transgressoft.lirp.event.StandardCrudEvent.Delete
import net.transgressoft.lirp.event.StandardCrudEvent.Update
import mu.KotlinLogging
import java.util.Objects

/**
 * Base class for mutable entity repositories with reactive behavior.
 *
 * `RepositoryBase` extends [RegistryBase] to provide full CRUD (Create, Read, Update, Delete)
 * operations on entities. It maintains a collection of entities that can be modified through
 * add, remove, and replace operations, with all changes automatically published to subscribers.
 *
 * Key features:
 * - Full CRUD operations with event publishing
 * - Bulk operations for adding/replacing/removing multiple entities
 * - Optimized entity replacement with change detection
 * - Detailed logging of repository operations
 *
 * Since this repository is volatile, all data is lost when the application terminates
 * or when the repository instance is garbage collected.
 *
 * Example usage:
 * ```
 * class UserRepository : VolatileRepository("UserRepository")
 * ```
 *
 * @param K The type of entity identifier, must be [Comparable]
 * @param T The type of entity being stored, must implement [IdentifiableEntity]
 * @property name A descriptive name for this repository, used in logging
 * @property initialEntities Optional map of entities to initialize the repository with
 */
open class VolatileRepository<K : Comparable<K>, T : IdentifiableEntity<K>>
    @JvmOverloads
    constructor(
        name: String = "Repository",
        initialEntities: MutableMap<K, T> = hashMapOf()
    ) : RegistryBase<K, T>(initialEntities, FlowEventPublisher(name)), Repository<K, T> {
        private val log = KotlinLogging.logger(javaClass.name)

        init {
            activateEvents(CREATE, DELETE)
        }

        override fun add(entity: T): Boolean {
            val previous = entitiesById.putIfAbsent(entity.id, entity)
            if (previous == null) {
                publisher.emitAsync(Create(entity))
                log.debug { "Entity with id ${entity.id} added to repository: $entity" }
                return true
            }

            return false
        }

        override fun addOrReplace(entity: T): Boolean {
            val oldValue = entitiesById.put(entity.id, entity)
            if (oldValue == null) {
                publisher.emitAsync(Create(entity))
                log.debug { "Entity with id ${entity.id} added to repository: $entity" }
            } else if (oldValue != entity) {
                publisher.emitAsync(Update(entity, oldValue))
                log.debug { "Entity with id ${entity.id} was replaced by $entity" }
            } else {
                return false
            }
            return true
        }

        override fun addOrReplaceAll(entities: Set<T>): Boolean {
            if (entities.isEmpty()) return false

            val added = mutableListOf<T>()
            val updated = mutableListOf<T>()
            val entitiesBeforeUpdate = mutableListOf<T>()

            entities.forEach { entity ->
                val oldValue = entitiesById.put(entity.id, entity)
                if (oldValue == null) {
                    added.add(entity)
                } else if (oldValue != entity) {
                    updated.add(entity)
                    entitiesBeforeUpdate.add(oldValue)
                }
            }

            if (added.isNotEmpty()) {
                publisher.emitAsync(Create(added))
                log.debug { "${added.size} entities were added: $added" }
            }

            if (updated.isNotEmpty()) {
                publisher.emitAsync(Update(updated, entitiesBeforeUpdate))
                log.debug { "${updated.size} entities were replaced: $updated" }
            }

            return added.isNotEmpty() || updated.isNotEmpty()
        }

        override fun remove(entity: T): Boolean {
            val removed = entitiesById.remove(entity.id, entity)
            if (removed) {
                publisher.emitAsync(Delete(entity))
                log.debug { "Entity with id ${entity.id} was removed: $entity" }
            }
            return removed
        }

        override fun removeAll(entities: Collection<T>): Boolean {
            val removed = mutableListOf<T>()

            entities.forEach { entity ->
                if (entitiesById.remove(entity.id, entity)) {
                    removed.add(entity)
                }
            }

            if (removed.isNotEmpty()) {
                publisher.emitAsync(Delete(removed))
                log.debug { "${removed.size} entities were removed: $removed" }
                return true
            }

            return false
        }

        override fun clear() {
            val allEntities = HashSet(entitiesById.values)
            if (allEntities.isNotEmpty()) {
                entitiesById.clear()
                publisher.emitAsync(Delete(allEntities))
                log.debug { "${allEntities.size} entities were removed resulting in empty repository" }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as VolatileRepository<*, *>
            return entitiesById == that.entitiesById
        }

        override fun hashCode() = Objects.hash(entitiesById)
    }
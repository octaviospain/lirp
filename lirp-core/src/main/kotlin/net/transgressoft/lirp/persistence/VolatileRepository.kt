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
import mu.KotlinLogging
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory entity repository with reactive event publishing.
 *
 * Extends [RegistryBase] with CRUD operations. [add] is a public interface method that can be
 * called directly through the [Repository] interface (composition pattern) or via typed factory
 * methods on concrete subclasses (inheritance pattern).
 *
 * [add] emits a CREATE event; [remove]/[removeAll]/[clear] emit DELETE events.
 *
 * Data is volatile — all entities are lost when the repository is garbage collected.
 *
 * @param K The type of entity identifier, must be [Comparable]
 * @param T The type of entity being stored, must implement [IdentifiableEntity]
 * @property name A descriptive name for this repository, used in logging
 * @property initialEntities Optional map of entities to initialize the repository with
 */
open class VolatileRepository<K : Comparable<K>, T : IdentifiableEntity<K>>
    internal constructor(
        context: LirpContext,
        name: String,
        initialEntities: MutableMap<K, T>
    ) : RegistryBase<K, T>(context, initialEntities, FlowEventPublisher(name)), Repository<K, T> {

        internal constructor(
            context: LirpContext,
            name: String
        ) : this(context, name, ConcurrentHashMap())

        @JvmOverloads
        constructor(
            name: String = "Repository",
            initialEntities: MutableMap<K, T> = ConcurrentHashMap()
        ) : this(LirpContext.default, name, initialEntities)

        private val log = KotlinLogging.logger(javaClass.name)

        init {
            activateEvents(CREATE, DELETE)
        }

        /**
         * Adds [entity] to this repository if no entity with the same ID already exists.
         *
         * @param entity The entity to add
         * @return `true` if the entity was added, `false` if an entity with the same ID is already present
         */
        override fun add(entity: T): Boolean {
            val previous = entitiesById.putIfAbsent(entity.id, entity)
            if (previous == null) {
                discoverIndexes(entity)
                indexEntity(entity)
                discoverRefs(entity)
                bindEntityRefs(entity)
                wireRefBubbleUp(entity)
                publisher.emitAsync(Create(entity))
                log.debug { "Entity with id ${entity.id} added to repository: $entity" }
                return true
            }
            return false
        }

        override fun remove(entity: T): Boolean {
            val removed = entitiesById.remove(entity.id, entity)
            if (removed) {
                deindexEntity(entity)
                executeCascadeForEntity(entity)
                publisher.emitAsync(Delete(entity))
                log.debug { "Entity with id ${entity.id} was removed: $entity" }
            }
            return removed
        }

        override fun removeAll(entities: Collection<T>): Boolean {
            val removed = mutableListOf<T>()

            entities.forEach { entity ->
                if (entitiesById.remove(entity.id, entity)) {
                    deindexEntity(entity)
                    executeCascadeForEntity(entity)
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
                // Bulk-clear all index value maps: O(n_indexes) rather than O(n_entities)
                clearSecondaryIndexes()
                allEntities.forEach { executeCascadeForEntity(it) }
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
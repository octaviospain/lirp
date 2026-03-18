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
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory entity repository with reactive event publishing.
 *
 * Extends [RegistryBase] with full CRUD operations. All mutations are published as
 * [net.transgressoft.lirp.event.CrudEvent] events: [add] emits CREATE, [remove]/[removeAll]/[clear] emit DELETE,
 * and [addOrReplace] emits CREATE for new entities or UPDATE (with the previous entity
 * in [net.transgressoft.lirp.event.CrudEvent.oldEntities]) when replacing an existing entity. No event is emitted
 * when the replacement entity is identical to the existing one.
 *
 * Data is volatile — all entities are lost when the repository is garbage collected.
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
        initialEntities: MutableMap<K, T> = ConcurrentHashMap()
    ) : RegistryBase<K, T>(initialEntities, FlowEventPublisher(name)), Repository<K, T> {
        private val log = KotlinLogging.logger(javaClass.name)

        init {
            activateEvents(CREATE, DELETE)
        }

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

        override fun addOrReplace(entity: T): Boolean {
            val oldValue = entitiesById.put(entity.id, entity)
            if (oldValue == null) {
                discoverIndexes(entity)
                indexEntity(entity)
                discoverRefs(entity)
                bindEntityRefs(entity)
                wireRefBubbleUp(entity)
                publisher.emitAsync(Create(entity))
                log.debug { "Entity with id ${entity.id} added to repository: $entity" }
            } else if (oldValue != entity) {
                deindexEntity(oldValue)
                indexEntity(entity)
                publisher.emitAsync(Update(entity, oldValue))
                log.debug { "Entity with id ${entity.id} was replaced by $entity" }
            } else {
                return false
            }
            return true
        }

        override fun addOrReplaceAll(entities: Set<T>): Boolean {
            if (entities.isEmpty()) return false

            // Snapshot state before mutation for atomic rollback
            val snapshot = LinkedHashMap<K, T?>(entities.size)
            val added = mutableListOf<T>()
            val updated = mutableListOf<T>()
            val entitiesBeforeUpdate = mutableListOf<T>()

            try {
                entities.forEach { entity ->
                    snapshot[entity.id] = entitiesById[entity.id]

                    val oldValue = entitiesById.put(entity.id, entity)
                    if (oldValue == null) {
                        discoverIndexes(entity)
                        indexEntity(entity)
                        discoverRefs(entity)
                        bindEntityRefs(entity)
                        wireRefBubbleUp(entity)
                        added.add(entity)
                    } else if (oldValue != entity) {
                        deindexEntity(oldValue)
                        indexEntity(entity)
                        updated.add(entity)
                        entitiesBeforeUpdate.add(oldValue)
                    }
                }
            } catch (exception: Exception) {
                rollback(snapshot, added, updated, entitiesBeforeUpdate)
                throw exception
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

        private fun rollback(
            snapshot: Map<K, T?>,
            added: List<T>,
            updated: List<T>,
            entitiesBeforeUpdate: List<T>
        ) {
            // Undo index changes for entities that were successfully processed
            added.forEach { deindexEntity(it) }
            updated.forEach { deindexEntity(it) }
            entitiesBeforeUpdate.forEach { indexEntity(it) }

            // Restore the primary map to its pre-operation state
            for ((id, previousEntity) in snapshot) {
                if (previousEntity != null) {
                    entitiesById[id] = previousEntity
                } else {
                    entitiesById.remove(id)
                }
            }
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
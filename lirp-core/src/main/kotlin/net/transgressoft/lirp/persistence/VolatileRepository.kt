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
import net.transgressoft.lirp.entity.MutableSoftDeletable
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.CrudEvent.Type.DELETE
import net.transgressoft.lirp.event.CrudEvent.Type.RESTORE
import net.transgressoft.lirp.event.CrudEvent.Type.SOFT_DELETE
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.LirpErrorHandler
import net.transgressoft.lirp.event.LirpEventPublisher
import net.transgressoft.lirp.event.StandardCrudEvent.Create
import net.transgressoft.lirp.event.StandardCrudEvent.Delete
import net.transgressoft.lirp.event.StandardCrudEvent.Restore
import net.transgressoft.lirp.event.StandardCrudEvent.SoftDelete
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
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
        initialEntities: MutableMap<K, T>,
        publisher: LirpEventPublisher<CrudEvent.Type, CrudEvent<K, T>>
    ) : RegistryBase<K, T>(context, initialEntities, publisher), Repository<K, T> {

        internal constructor(
            context: LirpContext,
            name: String,
            initialEntities: MutableMap<K, T>,
            onError: LirpErrorHandler? = null
        ) : this(context, name, initialEntities, FlowEventPublisher(name, onError = onError))

        internal constructor(
            context: LirpContext,
            name: String
        ) : this(context, name, ConcurrentHashMap(), null as LirpErrorHandler?)

        @JvmOverloads
        constructor(
            name: String = "Repository",
            initialEntities: MutableMap<K, T> = ConcurrentHashMap(),
            /**
             * Optional handler invoked after the existing error log when the async event drain
             * catches an exception. The framework logs first, then notifies the handler.
             * When `null`, behavior is log-only — identical to not configuring a handler.
             */
            onError: LirpErrorHandler? = null
        ) : this(LirpContext.default, name, initialEntities, onError)

        /**
         * Creates a [VolatileRepository] that routes CRUD events to the supplied [publisher]
         * instead of the default [FlowEventPublisher].
         *
         * This constructor allows injecting any [LirpEventPublisher] implementation
         * interchangeably with the default in-process publisher, enabling transparent
         * Kafka publishing of CRUD events when paired with a
         * [net.transgressoft.lirp.kafka.KafkaEventPublisher].
         *
         * @param name A descriptive name for this repository, used in logging
         * @param initialEntities Optional map of entities to initialize the repository with
         * @param publisher The event publisher that will receive all CRUD events emitted by this repository
         */
        constructor(
            name: String = "Repository",
            initialEntities: MutableMap<K, T> = ConcurrentHashMap(),
            publisher: LirpEventPublisher<CrudEvent.Type, CrudEvent<K, T>>
        ) : this(LirpContext.default, name, initialEntities, publisher)

        private val log = KotlinLogging.logger(javaClass.name)

        init {
            activateEvents(CREATE, DELETE, SOFT_DELETE, RESTORE)
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
                discoverViaAccessors(entity)
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

        override fun softDelete(entity: T): Boolean {
            val mutable = entity as? MutableSoftDeletable ?: return false
            // Run the read-only RESTRICT validation BEFORE the parent's state is mutated so that
            // a RESTRICT violation leaves the parent fully active and still indexed, with no event
            // emitted. This preflight does not touch the cycle-guard visited set; cycle detection
            // is handled by executeSoftCascadeForEntity after the parent has been safely transitioned.
            validateRestrictForSoftDelete(entity)
            // Guard the check-then-act on the entity's resident slot under a per-entity monitor so
            // concurrent softDelete/restore/remove calls cannot double-emit events or double-run
            // deindex/cascade. The synchronized block covers only the state transition; deindex and
            // cascade happen outside the lock (they are idempotent and must not hold the lock while
            // calling into other repositories, which could deadlock).
            synchronized(entity) {
                if (!entitiesById.containsKey(entity.id)) return false
                if (mutable.deletedAt != null) return false
                mutable.deletedAt = Instant.now()
            }
            deindexEntity(entity)
            executeSoftCascadeForEntity(entity)
            publisher.emitAsync(SoftDelete(entity))
            log.debug { "Entity with id ${entity.id} was soft-deleted: $entity" }
            return true
        }

        override fun restore(entity: T): Boolean {
            val mutable = entity as? MutableSoftDeletable ?: return false
            // Mirror the same atomicity pattern as softDelete to prevent concurrent restore calls
            // from double-emitting Restore events or re-indexing the entity twice.
            synchronized(entity) {
                if (!entitiesById.containsKey(entity.id)) return false
                if (mutable.deletedAt == null) return false
                mutable.deletedAt = null
            }
            indexEntity(entity)
            publisher.emitAsync(Restore(entity))
            log.debug { "Entity with id ${entity.id} was restored: $entity" }
            return true
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as VolatileRepository<*, *>
            return entitiesById == that.entitiesById
        }

        override fun hashCode() = Objects.hash(entitiesById)
    }
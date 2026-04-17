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

package net.transgressoft.lirp.event

import net.transgressoft.lirp.entity.IdentifiableEntity

/**
 * Container class that provides standard implementations of [CrudEvent] for
 * different CRUD operation types.
 *
 * This sealed class hierarchically organizes the different types of CRUD operations
 * and provides factory methods to create appropriately typed event instances.
 */
sealed class StandardCrudEvent {

    data class Create<K, out T: IdentifiableEntity<K>>(override val entities: Map<K, T>): CrudEvent<K, T> where K: Comparable<K> {
        constructor(entity: T): this(mapOf(entity.id to entity))
        constructor(entities: Collection<T>): this(entities.associateBy { it.id })

        override val oldEntities: Map<K, T> = emptyMap()
        override val type: CrudEvent.Type = CrudEvent.Type.CREATE
    }

    data class Read<K, out T: IdentifiableEntity<K>>(override val entities: Map<K, T>): CrudEvent<K, T> where K: Comparable<K> {
        constructor(entity: T): this(mapOf(entity.id to entity))
        constructor(entities: Collection<T>): this(entities.associateBy { it.id })

        override val oldEntities: Map<K, T> = emptyMap()
        override val type: CrudEvent.Type = CrudEvent.Type.READ
    }

    data class Update<K, out T: IdentifiableEntity<K>>(override val entities: Map<K, T>, override val oldEntities: Map<K, T>)
    : CrudEvent<K, T> where K: Comparable<K> {

        constructor(entity: T, oldEntity: T): this(mapOf(entity.id to entity), mapOf(oldEntity.id to oldEntity))

        constructor(entities: Collection<T>, oldEntities: Collection<T>): this(
            entities.associateBy { it.id },
            oldEntities.associateBy { it.id }
        )

        init {
            require(eventCollectionsAreConsistent(entities, oldEntities)) {
                "The collections of entities and old entities must be consistent for an UPDATE event. " +
                    "They don't have the same size or they don't have the same keys."
            }
        }

        private fun eventCollectionsAreConsistent(entities: Map<K, T>, oldEntities: Map<K, T>): Boolean =
            entities.keys.containsAll(oldEntities.keys) && oldEntities.keys.containsAll(entities.keys) && entities.size == oldEntities.size

        override val type: CrudEvent.Type = CrudEvent.Type.UPDATE
    }

    data class Delete<K, out T: IdentifiableEntity<K>>(override val entities: Map<K, T>): CrudEvent<K, T> where K: Comparable<K> {
        constructor(entity: T): this(mapOf(entity.id to entity))
        constructor(entities: Collection<T>): this(entities.associateBy { it.id })

        override val oldEntities: Map<K, T> = emptyMap()
        override val type: CrudEvent.Type = CrudEvent.Type.DELETE
    }

    /**
     * Emitted when a `SqlRepository` detects an optimistic lock conflict during UPDATE or DELETE.
     *
     * The entity in [entities] (the `newEntity` argument) reflects the canonical state after
     * auto-reload from the database; the entity in [oldEntities] (the `oldEntity` argument) is
     * the local state attempted against SQL, post-collapse.
     *
     * Auto-reload applies the canonical state with entity mutation events disabled
     * (via `withEventsDisabled`), so no corresponding [net.transgressoft.lirp.event.MutationEvent]
     * fires during recovery. Repository subscribers should treat this `Conflict` event as the
     * authoritative notification for the recovered state.
     *
     * @param expectedVersion version the local UPDATE or DELETE was based on
     * @param actualVersion canonical version observed in the database at recovery time
     */
    data class Conflict<K, out T: IdentifiableEntity<K>>(
        override val entities: Map<K, T>,
        override val oldEntities: Map<K, T>,
        val expectedVersion: Long,
        val actualVersion: Long
    ): CrudEvent<K, T> where K: Comparable<K> {

        constructor(newEntity: T, oldEntity: T, expectedVersion: Long, actualVersion: Long): this(
            mapOf(newEntity.id to newEntity),
            mapOf(oldEntity.id to oldEntity),
            expectedVersion,
            actualVersion
        )

        init {
            require(entities.size == 1 && oldEntities.size == 1 && entities.keys == oldEntities.keys) {
                "Conflict event must carry exactly one entity (old + new) with matching id. " +
                    "Got entities=${entities.keys}, oldEntities=${oldEntities.keys}."
            }
            // D-11: `actualVersion == -1L` is a sentinel meaning "the row was deleted by a third
            // writer" — used by the SQL auto-reload path to report a third-party deletion as a
            // Conflict. All other values must observe a newer DB version than the caller's view.
            require(actualVersion == -1L || actualVersion > expectedVersion) {
                "Conflict event requires actualVersion > expectedVersion " +
                    "(or actualVersion == -1L for the row-deleted-by-third-writer sentinel): " +
                    "expectedVersion=$expectedVersion, actualVersion=$actualVersion."
            }
        }

        override val type: CrudEvent.Type = CrudEvent.Type.CONFLICT
    }
}
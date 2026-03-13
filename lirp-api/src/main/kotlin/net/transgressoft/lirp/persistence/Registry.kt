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
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.TransEventPublisher
import java.util.*
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * A registry represents a read-only collection of entities that can be queried and accessed.
 *
 * The Registry provides a foundation for entity management with query capabilities
 * but intentionally restricts modification operations. Entities within a Registry
 * are uniquely identified by their ID, allowing for consistent and predictable access.
 *
 * @param K The type of the entity's identifier, which must be [Comparable]
 * @param T The type of entities in the registry, which must implement [IdentifiableEntity]
 */
interface Registry<K, T: IdentifiableEntity<K>> : TransEventPublisher<CrudEvent.Type, CrudEvent<K, T>> where K : Comparable<K> {

    /**
     * Applies the given action to the entity with the specified ID if present.
     *
     * If the action results in a different entity state (determined by hash code comparison),
     * the entity will be replaced in the registry.
     *
     * @param id The ID of the entity to apply the action to
     * @param entityAction The action to apply to the entity
     * @return True if the entity was found and the action was applied, false otherwise
     */
    fun runForSingle(id: K, entityAction: Consumer<in T>): Boolean

    /**
     * Applies the given action to all entities with the specified IDs if present.
     *
     * If the action results in different entity states (determined by hash code comparison),
     * the entities will be replaced in the registry.
     *
     * @param ids The set of IDs of entities to apply the action to
     * @param entityAction The action to apply to the entities
     * @return True if any entity was found and the action was applied, false otherwise
     */
    fun runForMany(ids: Set<K>, entityAction: Consumer<in T>): Boolean

    /**
     * Applies the given action to all entities that match the specified predicate.
     *
     * If the action results in different entity states (determined by hash code comparison),
     * the entities will be replaced in the registry.
     *
     * @param predicate The predicate to match entities against
     * @param entityAction The action to apply to matching entities
     * @return True if any entity matched and the action was applied, false otherwise
     */
    fun runMatching(predicate: Predicate<in T>, entityAction: Consumer<in T>): Boolean

    /**
     * Applies the given action to all entities in the registry.
     *
     * If the action results in different entity states (determined by hash code comparison),
     * the entities will be replaced in the registry.
     *
     * @param entityAction The action to apply to all entities
     * @return True if the action was applied to any entity, false if the registry is empty
     */
    fun runForAll(entityAction: Consumer<in T>): Boolean

    /**
     * Checks if the registry contains an entity with the specified ID.
     *
     * @param id The ID to check for existence
     * @return True if an entity with the given ID exists, false otherwise
     */
    fun contains(id: K): Boolean

    /**
     * Checks if the registry contains any entity matching the specified predicate.
     *
     * @param predicate The predicate to match entities against
     * @return True if any entity matches the predicate, false otherwise
     */
    fun contains(predicate: Predicate<in T>): Boolean

    /**
     * Returns all entities that match the specified predicate.
     *
     * @param predicate The predicate to match entities against
     * @return A set of all entities matching the predicate
     */
    fun search(predicate: Predicate<in T>): Set<T>

    /**
     * Returns a limited number of entities that match the specified predicate.
     *
     * @param size The maximum number of entities to return
     * @param predicate The predicate to match entities against
     * @return A set of entities matching the predicate, limited to the specified size
     */
    fun search(size: Int, predicate: Predicate<in T>): Set<T>

    /**
     * Returns the first entity that matches the specified predicate.
     *
     * @param predicate The predicate to match entities against
     * @return An Optional containing the first matching entity, or empty if none match
     */
    fun findFirst(predicate: Predicate<in T>): Optional<out T>

    /**
     * Returns the entity with the specified ID if present.
     *
     * @param id The ID of the entity to find
     * @return An Optional containing the entity with the given ID, or empty if not found
     */
    fun findById(id: K): Optional<out T>

    /**
     * Returns the entity with the specified unique identifier if present.
     *
     * @param uniqueId The unique identifier of the entity to find
     * @return An Optional containing the entity with the given unique ID, or empty if not found
     */
    fun findByUniqueId(uniqueId: String): Optional<out T>

    /**
     * Returns the number of entities in the registry.
     *
     * @return The count of entities
     */
    fun size(): Int

    /**
     * Checks if the registry contains no entities.
     *
     * @return True if the registry is empty, false otherwise
     */
    val isEmpty: Boolean
}
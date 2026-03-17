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
import net.transgressoft.lirp.event.LirpEventPublisher
import java.util.*
import java.util.function.Predicate
import java.util.stream.Stream

/**
 * A read-only, iterable collection of entities that can be queried and observed.
 *
 * Registry provides query capabilities over a collection of [IdentifiableEntity] instances,
 * with entity access by ID, predicate-based search, and iteration via [Iterable].
 * Iteration is weakly-consistent when backed by a [java.util.concurrent.ConcurrentHashMap].
 *
 * @param K The type of the entity's identifier, which must be [Comparable]
 * @param T The type of entities in the registry, which must implement [IdentifiableEntity]
 */
interface Registry<K, T: IdentifiableEntity<K>> : LirpEventPublisher<CrudEvent.Type, CrudEvent<K, T>>, Iterable<T> where K : Comparable<K> {

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
     * Returns a lazy [Sequence] of entities that match the specified predicate.
     *
     * The sequence is evaluated lazily over the backing collection with O(n) worst-case complexity.
     * No intermediate collection is allocated, making this suitable for large registries or
     * short-circuit consumers. Use [Sequence.take], [Sequence.firstOrNull], or any other
     * Sequence terminal operation to limit traversal.
     *
     * This method does NOT emit [net.transgressoft.lirp.event.CrudEvent.Type.READ] events.
     * Use [search] to obtain a materialized result with event notification.
     *
     * @param predicate The predicate to match entities against
     * @return A lazy [Sequence] of matching entities
     */
    fun lazySearch(predicate: Predicate<in T>): Sequence<T>

    /**
     * Returns a Java [Stream] of entities that match the specified predicate, for Java interop callers.
     *
     * The stream is backed by a lazy [Sequence] with O(n) worst-case complexity.
     * Callers may use [Stream.limit] and [Stream.findFirst] for early termination without
     * evaluating the entire collection.
     *
     * This method does NOT emit [net.transgressoft.lirp.event.CrudEvent.Type.READ] events.
     * Use [search] to obtain a materialized result with event notification.
     *
     * @param predicate The predicate to match entities against
     * @return A sequential [Stream] of matching entities
     */
    fun searchStream(predicate: Predicate<in T>): Stream<T>

    /**
     * Returns all entities that match the specified predicate.
     *
     * Delegates to [lazySearch] internally for a single code path, then materialises the result
     * into a [Set] and emits a [net.transgressoft.lirp.event.CrudEvent.Type.READ] event.
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
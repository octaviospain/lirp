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
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.LirpEventPublisher
import net.transgressoft.lirp.event.StandardCrudEvent.Read
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * Base class for read-only entity registries with reactive query capabilities.
 *
 * Provides a searchable, iterable entity collection backed by a [ConcurrentHashMap].
 * Query results and entity reads are published to subscribers as [CrudEvent] events.
 * Iteration via [iterator] is weakly-consistent: it will not throw
 * [java.util.ConcurrentModificationException] under concurrent modification, but
 * may or may not reflect entries added or removed after iteration starts.
 *
 * @param K The type of entity identifier, must be [Comparable]
 * @param T The type of entity being stored, must implement [IdentifiableEntity]
 * @property entitiesById The internal map storing entities by their IDs
 * @property publisher The event publisher for broadcasting entity operations
 */
abstract class RegistryBase<K, T : IdentifiableEntity<K>>(
    protected val entitiesById: MutableMap<K, T> = ConcurrentHashMap(),
    protected val publisher: LirpEventPublisher<CrudEvent.Type, CrudEvent<K, T>> = FlowEventPublisher("Registry")
) : LirpEventPublisher<CrudEvent.Type, CrudEvent<K, T>> by publisher,
    Registry<K, T> where K : Comparable<K> {
    private val log = KotlinLogging.logger(javaClass.name)

    /**
     * Nested map structure: indexName -> (fieldValue -> set of entities).
     * Populated lazily on first entity add by [discoverIndexes]; entries are created per discovered @Indexed property.
     */
    private val secondaryIndexes: MutableMap<String, MutableMap<Any, MutableSet<T>>> = ConcurrentHashMap()

    /**
     * Cached index entries loaded from the KSP-generated [LirpIndexAccessor] for this entity type.
     * Each entry holds the resolved index name and a direct property getter lambda — no runtime reflection.
     * Null until discovery runs; an empty list means no generated accessor was found.
     */
    @Volatile
    private var indexEntries: List<IndexEntry<T>>? = null

    init {
        // A registry can't create or delete entities,
        // so the CREATE and DELETE events are disabled by default.
        // READ is disabled also because its use case is not clear yet
        activateEvents(UPDATE)
    }

    /**
     * Loads the KSP-generated [LirpIndexAccessor] for the entity's class via a convention-based
     * [Class.forName] lookup (`{EntityClassName}_LirpIndexAccessor`). Uses double-checked locking
     * to ensure loading runs exactly once and the result is visible to all threads.
     *
     * The generated accessor provides [IndexEntry] descriptors with direct property getter lambdas,
     * completely avoiding `kotlin-reflect` or `java.lang.reflect` overhead for property access.
     * If no generated accessor is found (KSP not applied), the index entry list remains empty.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun discoverIndexes(entity: T) {
        if (indexEntries != null) return
        synchronized(this) {
            if (indexEntries != null) return
            val entries =
                try {
                    val accessorClass = Class.forName("${entity.javaClass.name}_LirpIndexAccessor")
                    val accessor = accessorClass.getDeclaredConstructor().newInstance() as LirpIndexAccessor<T>
                    accessor.entries
                } catch (_: ClassNotFoundException) {
                    emptyList()
                }
            for (entry in entries) {
                secondaryIndexes.putIfAbsent(entry.indexName, ConcurrentHashMap())
            }
            indexEntries = entries
        }
    }

    /**
     * Adds [entity] to all secondary indexes. For each @Indexed property whose value is non-null,
     * the entity is inserted into the corresponding value bucket. Null values are silently skipped
     * because [ConcurrentHashMap] does not permit null keys.
     */
    protected fun indexEntity(entity: T) {
        val entries = indexEntries ?: return
        for ((indexName, getter) in entries) {
            val value = getter(entity) ?: continue
            secondaryIndexes[indexName]?.computeIfAbsent(value) { ConcurrentHashMap.newKeySet() }?.add(entity)
        }
    }

    /**
     * Removes [entity] from all secondary indexes. Null property values are silently skipped.
     */
    protected fun deindexEntity(entity: T) {
        val entries = indexEntries ?: return
        for ((indexName, getter) in entries) {
            val value = getter(entity) ?: continue
            secondaryIndexes[indexName]?.get(value)?.remove(entity)
        }
    }

    /**
     * Clears all value buckets in every secondary index. O(n_indexes) operation — does not iterate entities.
     * Intended for use in bulk-clear operations such as [net.transgressoft.lirp.persistence.VolatileRepository.clear].
     */
    protected fun clearSecondaryIndexes() {
        secondaryIndexes.values.forEach { it.clear() }
    }

    override fun findByIndex(indexName: String, value: Any): Set<T> {
        val indexMap =
            secondaryIndexes[indexName]
                ?: throw IllegalArgumentException("No index declared for property '$indexName'")
        return indexMap[value]?.toSet() ?: emptySet()
    }

    override fun findFirstByIndex(indexName: String, value: Any): Optional<out T> {
        val indexMap =
            secondaryIndexes[indexName]
                ?: throw IllegalArgumentException("No index declared for property '$indexName'")
        return Optional.ofNullable(indexMap[value]?.firstOrNull())
    }

    override fun iterator(): Iterator<T> = entitiesById.values.iterator()

    override fun contains(id: K) = entitiesById.containsKey(id)

    override fun contains(predicate: Predicate<in T>): Boolean =
        entitiesById.values.asSequence().any { predicate.test(it) }

    override fun lazySearch(predicate: Predicate<in T>): Sequence<T> =
        entitiesById.values.asSequence().filter { predicate.test(it) }

    override fun searchStream(predicate: Predicate<in T>): Stream<T> =
        StreamSupport.stream(lazySearch(predicate).asIterable().spliterator(), false)

    override fun search(predicate: Predicate<in T>): Set<T> =
        lazySearch(predicate).toSet().also { publisher.emitAsync(Read(it)) }

    override fun search(size: Int, predicate: Predicate<in T>): Set<T> =
        lazySearch(predicate).take(size).toSet().also { publisher.emitAsync(Read(it)) }

    override fun findFirst(predicate: Predicate<in T>): Optional<out T> =
        Optional.ofNullable(entitiesById.values.firstOrNull { predicate.test(it) })
            .also {
                if (it.isPresent)
                    publisher.emitAsync(Read(it.get()))
            }

    override fun findById(id: K): Optional<out T> =
        Optional.ofNullable(entitiesById[id])
            .also {
                if (it.isPresent)
                    publisher.emitAsync(Read(it.get()))
            }

    override fun findByUniqueId(uniqueId: String): Optional<out T> =
        Optional.ofNullable(entitiesById.values.asSequence().firstOrNull { it.uniqueId == uniqueId })
            .also {
                if (it.isPresent)
                    publisher.emitAsync(Read(it.get()))
            }

    override fun size() = entitiesById.size

    override val isEmpty: Boolean
        get() = entitiesById.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as RegistryBase<*, *>
        return entitiesById == that.entitiesById
    }

    override fun hashCode() = Objects.hash(entitiesById)
}
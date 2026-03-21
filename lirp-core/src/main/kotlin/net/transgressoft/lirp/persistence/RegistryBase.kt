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
import net.transgressoft.lirp.persistence.LirpRegistryInfo
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

    /**
     * Cached reference entries loaded from the KSP-generated [LirpRefAccessor] for this entity type.
     * Each entry holds the reference name, an ID getter lambda, the referenced class, and metadata.
     * Null until discovery runs; an empty list means no generated accessor was found.
     */
    @Volatile
    private var refEntries: List<RefEntry<T, *>>? = null

    init {
        // A registry can't create or delete entities,
        // so the CREATE and DELETE events are disabled by default.
        // READ is disabled also because its use case is not clear yet
        activateEvents(UPDATE)
        // Auto-register if @LirpRepository KSP accessor is present
        try {
            val infoClass = Class.forName(this::class.java.name + "_LirpRegistryInfo")
            val info = infoClass.getDeclaredConstructor().newInstance() as LirpRegistryInfo
            val existing = globalRegistries.putIfAbsent(info.entityClass, this)
            check(!(existing != null && existing !== this)) {
                "A repository for ${info.entityClass.simpleName} is already registered. Only one @LirpRepository per entity type is allowed."
            }
        } catch (_: ClassNotFoundException) {
            // Not a @LirpRepository-annotated subclass — skip silently
        }
    }

    override fun close() {
        globalRegistries.entries.removeIf { (_, registry) -> registry === this }
        publisher.close()
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

    /**
     * Loads the KSP-generated [LirpRefAccessor] for the entity's class via a convention-based
     * [Class.forName] lookup (`{EntityClassName}_LirpRefAccessor`). Uses double-checked locking
     * to ensure loading runs exactly once and the result is visible to all threads.
     *
     * The generated accessor provides [RefEntry] descriptors with direct ID getter lambdas,
     * completely avoiding `kotlin-reflect` or `java.lang.reflect` overhead. If no generated
     * accessor is found (KSP not applied or no [@ReactiveEntityRef][ReactiveEntityRef] annotations),
     * the reference entry list remains empty.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun discoverRefs(entity: T) {
        if (refEntries != null) return
        synchronized(this) {
            if (refEntries != null) return
            refEntries =
                try {
                    val accessorClass = Class.forName("${entity.javaClass.name}_LirpRefAccessor")
                    val accessor = accessorClass.getDeclaredConstructor().newInstance() as LirpRefAccessor<T>
                    accessor.entries
                } catch (_: ClassNotFoundException) {
                    emptyList()
                }
        }
    }

    /**
     * Binds each [AggregateRefDelegate] on [entity] to the [Registry] that holds its referenced
     * entity type. For each [RefEntry] discovered via [discoverRefs], this method:
     *
     * 1. Gets the raw ID of the referenced entity via [RefEntry.idGetter].
     * 2. Looks up the [Registry] for [RefEntry.referencedClass] in the companion-level registry map.
     * 3. If found, calls [AggregateRefDelegate.bindRegistry] on the property value.
     *
     * Only properties that are [AggregateRefDelegate] instances are processed — non-delegate
     * implementations of [ReactiveEntityReference] are skipped silently.
     */
    protected fun bindEntityRefs(entity: T) {
        val entries = refEntries ?: return
        for (entry in entries) {
            val registry = globalRegistries[entry.referencedClass] ?: continue
            // Locate the delegate by its property name using getDeclaredField to avoid KProperty overhead
            try {
                val field = entity.javaClass.getDeclaredField(entry.refName)
                field.isAccessible = true
                bindDelegateField(field[entity], registry)
            } catch (_: NoSuchFieldException) {
                // Kotlin property delegates are stored as backing fields named "<propertyName>$delegate"
                try {
                    val delegateField = entity.javaClass.getDeclaredField("${entry.refName}\$delegate")
                    delegateField.isAccessible = true
                    bindDelegateField(delegateField[entity], registry)
                } catch (_: NoSuchFieldException) {
                    log.warn { "Could not find delegate field for ref '${entry.refName}' on ${entity.javaClass.simpleName}" }
                }
            }
        }
    }

    private fun bindDelegateField(fieldValue: Any?, registry: Registry<*, *>) {
        if (fieldValue is AggregateRefDelegate<*, *>) {
            fieldValue.bindRegistryUntyped(registry)
        }
    }

    /**
     * Wires bubble-up subscriptions for all aggregate references on [entity] that have
     * `bubbleUp = true`. For each such reference, [AggregateRefDelegate.wireBubbleUp] is called
     * with the parent entity and the reference name.
     *
     * This method is called after [bindEntityRefs] so that the delegate already has the bound
     * registry before the referenced entity is resolved for subscription.
     */
    protected fun wireRefBubbleUp(entity: T) {
        val entries = refEntries ?: return
        for (entry in entries) {
            if (!entry.bubbleUp) continue
            val delegate = findDelegateField(entity, entry.refName) ?: continue
            if (delegate is AggregateRefDelegate<*, *>) {
                delegate.wireBubbleUp(entity as net.transgressoft.lirp.entity.ReactiveEntity<*, *>, entry.refName)
            }
        }
    }

    /**
     * Executes cascade actions for all aggregate references declared on [entity].
     *
     * Called by [VolatileRepository] during [net.transgressoft.lirp.persistence.VolatileRepository.remove]
     * and [net.transgressoft.lirp.persistence.VolatileRepository.clear]. Each reference delegate's
     * [AggregateRefDelegate.executeCascade] is invoked with its configured [net.transgressoft.lirp.entity.CascadeAction].
     */
    protected fun executeCascadeForEntity(entity: T) {
        val entries = refEntries ?: return
        for (entry in entries) {
            val delegate = findDelegateField(entity, entry.refName) ?: continue
            if (delegate is AggregateRefDelegate<*, *>) {
                delegate.executeCascade(entry.cascadeAction)
            }
        }
    }

    /**
     * Executes DETACH cleanup (cancels bubble-up subscriptions) for all aggregate references on [entity].
     *
     * Called by [net.transgressoft.lirp.entity.ReactiveEntityBase.close] to ensure subscription cleanup
     * when an entity is permanently closed, regardless of the configured cascade action.
     */
    protected fun detachAllRefs(entity: T) {
        val entries = refEntries ?: return
        for (entry in entries) {
            val delegate = findDelegateField(entity, entry.refName) ?: continue
            if (delegate is AggregateRefDelegate<*, *>) {
                delegate.cancelBubbleUp()
            }
        }
    }

    private fun findDelegateField(entity: T, refName: String): Any? =
        try {
            val field = entity.javaClass.getDeclaredField(refName)
            field.isAccessible = true
            field[entity]
        } catch (_: NoSuchFieldException) {
            try {
                val delegateField = entity.javaClass.getDeclaredField("${refName}\$delegate")
                delegateField.isAccessible = true
                delegateField[entity]
            } catch (_: NoSuchFieldException) {
                log.warn { "Could not find delegate field for ref '$refName' on ${entity.javaClass.simpleName}" }
                null
            }
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

    companion object {
        /**
         * Application-level registry map: entity class -> Registry that holds that entity type.
         * Registration happens automatically at construction time for `@LirpRepository`-annotated
         * subclasses via the KSP-generated `{ClassName}_LirpRegistryInfo` class. Deregistration
         * happens automatically in [close]. Used by [bindEntityRefs] to locate the correct
         * [Registry] for each aggregate reference without any manual wiring.
         *
         * Keyed by entity [Class] to avoid Kotlin type-erasure issues with generic types.
         */
        @JvmStatic
        private val globalRegistries: ConcurrentHashMap<Class<*>, Registry<*, *>> = ConcurrentHashMap()

        /**
         * Returns the registered [Registry] for the given [entityClass], or `null` if none is registered.
         */
        @JvmStatic
        internal fun registryFor(entityClass: Class<*>): Registry<*, *>? = globalRegistries[entityClass]

        /**
         * Returns the number of currently registered repositories.
         */
        @JvmStatic
        internal fun registryCount(): Int = globalRegistries.size
    }
}
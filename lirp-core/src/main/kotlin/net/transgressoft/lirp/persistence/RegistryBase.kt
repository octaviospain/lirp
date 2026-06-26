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
import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.LirpEventPublisher
import net.transgressoft.lirp.event.StandardCrudEvent.Read
import net.transgressoft.lirp.persistence.FxScalarPropertyDelegate
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.function.Predicate
import java.util.stream.Stream
import java.util.stream.StreamSupport
import kotlin.reflect.KProperty1

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
 * @param context The [LirpContext] this registry registers into. Defaults to [LirpContext.default]
 *        for production use; tests should supply a fresh context for isolation.
 * @property entitiesById The internal map storing entities by their IDs
 * @property publisher The event publisher for broadcasting entity operations
 */
abstract class RegistryBase<K, T : IdentifiableEntity<K>> internal constructor(
    internal val context: LirpContext,
    protected val entitiesById: MutableMap<K, T>,
    protected val publisher: LirpEventPublisher<CrudEvent.Type, CrudEvent<K, T>>
) : LirpEventPublisher<CrudEvent.Type, CrudEvent<K, T>> by publisher,
    Registry<K, T> where K : Comparable<K> {
    private val log = KotlinLogging.logger(javaClass.name)

    @JvmOverloads
    constructor(
        entitiesById: MutableMap<K, T> = ConcurrentHashMap(),
        publisher: LirpEventPublisher<CrudEvent.Type, CrudEvent<K, T>> = FlowEventPublisher("Registry")
    ) : this(LirpContext.default, entitiesById, publisher)

    /**
     * Hash-bucket secondary indexes: indexName -> (fieldValue -> set of entities).
     * Populated lazily on first entity add by [discoverIndexes] for every [IndexEntry] with [IndexEntry.sorted] == false.
     * An index name that appears here will never appear in [sortedIndexes] — the bucket kind is fixed at discovery time.
     */
    private val hashIndexes: MutableMap<String, ConcurrentMap<Any, MutableSet<T>>> = ConcurrentHashMap()

    /**
     * Sorted-bucket secondary indexes: indexName -> (fieldValue -> set of entities), backed by [ConcurrentSkipListMap].
     * Populated lazily on first entity add by [discoverIndexes] for every [IndexEntry] with [IndexEntry.sorted] == true.
     * An index name that appears here will never appear in [hashIndexes] — the bucket kind is fixed at discovery time.
     * The live map is exposed via [sortedBucketFor] for lock-free range-slice access by the query planner.
     *
     * **Invariant:** each [ConcurrentSkipListMap] in this map is scoped to a single
     * `(registry, indexName)` pair and must only receive values of a single runtime type. Index names
     * must not collide across entity types registered to the same [LirpContext]; if they do, the
     * explicit comparator below surfaces a clear [ClassCastException] with type information rather
     * than an opaque JVM-level cast failure.
     */
    private val sortedIndexes: MutableMap<String, ConcurrentSkipListMap<Comparable<Any>, MutableSet<T>>> =
        ConcurrentHashMap()

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
    private var refEntries: List<RefEntry<*, T>>? = null

    /**
     * Cached collection reference entries loaded from the KSP-generated [LirpRefAccessor] for this entity type.
     * Each entry holds the reference name, an IDs getter lambda, the referenced class, and metadata.
     * Null until discovery runs; an empty list means no collection references were declared.
     */
    @Volatile
    private var collectionRefEntries: List<CollectionRefEntry<*, T>>? = null

    /**
     * Cached [LirpViaAccessor] loaded from the KSP-generated `{EntityName}_LirpViaAccessor` for this entity type.
     * Holds the typed [KProperty1] descriptors consumed by the cross-aggregate Query DSL planner.
     * Null until discovery runs; remains null when no generated accessor exists (entity has no `@ToOneAggregate` / `@ToManyAggregates` properties).
     *
     * Discovery occurs lazily on first entity registration via [discoverViaAccessors].
     */
    @Volatile
    internal var viaAccessor: LirpViaAccessor<T>? = null
        private set

    /**
     * Marks whether [discoverViaAccessors] has already executed for this registry. Distinguishes
     * "no accessor exists" (null + flag=true) from "discovery has not yet run" (null + flag=false),
     * mirroring the indexEntries/refEntries discovery-state contract.
     */
    @Volatile
    private var viaAccessorDiscovered: Boolean = false

    init {
        // A registry can't create or delete entities,
        // so the CREATE and DELETE events are disabled by default.
        // READ is disabled also because its use case is not clear yet
        activateEvents(UPDATE)
        val info = KspAccessorLoader.load<LirpRegistryInfo>(this::class.java, KspAccessorLoader.REGISTRY_INFO_SUFFIX)
        if (info != null) {
            val registered = context.register(info.entityClass, this)
            check(registered || context.registryFor(info.entityClass) === this) {
                "A repository for ${info.entityClass.simpleName} is already registered. Only one @LirpRepository per entity type is allowed."
            }
        }
    }

    override fun close() {
        context.deregister(this)
        publisher.close()
    }

    /**
     * Loads the KSP-generated [LirpIndexAccessor] for the entity's class via [KspAccessorLoader],
     * which performs a convention-based lookup by class name. Uses double-checked locking
     * to ensure loading runs exactly once and the result is visible to all threads.
     *
     * The generated accessor provides [IndexEntry] descriptors with direct property getter lambdas,
     * completely avoiding `kotlin-reflect` or `java.lang.reflect` overhead for property access.
     * If no generated accessor is found (KSP not applied), the index entry list remains empty.
     *
     * Anonymous and local class entities are skipped early — they can never have KSP-generated
     * accessors because they lack stable binary names.
     */
    protected fun discoverIndexes(entity: T) {
        if (indexEntries != null) return
        synchronized(this) {
            if (indexEntries != null) return
            if (entity.javaClass.isAnonymousClass || entity.javaClass.isLocalClass) {
                indexEntries = emptyList()
                return
            }
            val entries = loadAccessorEntries(entity)
            registerIndexBuckets(entries)
            indexEntries = entries
        }
    }

    private fun loadAccessorEntries(entity: T): List<IndexEntry<T>> =
        KspAccessorLoader.load<LirpIndexAccessor<T>>(entity.javaClass, KspAccessorLoader.INDEX_ACCESSOR_SUFFIX)
            ?.entries
            ?: emptyList()

    private fun registerIndexBuckets(entries: List<IndexEntry<T>>) {
        for (entry in entries) {
            if (entry.sorted) {
                sortedIndexes.putIfAbsent(entry.indexName, ConcurrentSkipListMap(sortedIndexComparator(entry.indexName)))
            } else {
                hashIndexes.putIfAbsent(entry.indexName, ConcurrentHashMap())
            }
        }
    }

    private fun sortedIndexComparator(indexName: String): Comparator<Comparable<Any>> =
        Comparator { a, b ->
            try {
                a.compareTo(b)
            } catch (_: ClassCastException) {
                throw ClassCastException(
                    "Sorted index '$indexName' received incompatible key types: " +
                        "${a::class.simpleName} vs ${b::class.simpleName}. " +
                        "Each @Indexed(sorted=true) property must have a consistent runtime type."
                )
            }
        }

    /**
     * Adds [entity] to all secondary indexes. For each @Indexed property whose value is non-null,
     * the entity is inserted into the corresponding value bucket (sorted or hash). Null values are
     * silently skipped because neither [ConcurrentHashMap] nor [ConcurrentSkipListMap]
     * permits null keys.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun indexEntity(entity: T) {
        val entries = indexEntries ?: return
        for (entry in entries) {
            val value = entry.getter(entity) ?: continue
            if (entry.sorted) {
                sortedIndexes[entry.indexName]
                    ?.computeIfAbsent(value as Comparable<Any>) { ConcurrentHashMap.newKeySet() }
                    ?.add(entity)
            } else {
                hashIndexes[entry.indexName]
                    ?.computeIfAbsent(value) { ConcurrentHashMap.newKeySet() }
                    ?.add(entity)
            }
        }
    }

    /**
     * Removes [entity] from all secondary indexes. Null property values are silently skipped.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun deindexEntity(entity: T) {
        val entries = indexEntries ?: return
        for (entry in entries) {
            val value = entry.getter(entity) ?: continue
            if (entry.sorted) {
                removeFromBucket(sortedIndexes[entry.indexName], value as Comparable<Any>, entity)
            } else {
                removeFromBucket(hashIndexes[entry.indexName], value, entity)
            }
        }
    }

    /**
     * Moves [entity] from its old index bucket (keyed by the captured [oldIndexKey]) to its
     * current index positions derived from the live entity state.
     *
     * Called from entity mutation handlers. Both [oldIndexKey] and [newIndexKey] are captured
     * as immutable scalars at assignment time, guarding against drift when the coroutine subscriber
     * drains asynchronously while the live entity undergoes further mutations. When neither key is
     * non-null (the mutated property carries no `@Indexed` annotation), the call is a no-op; the
     * entity's existing index placement is still correct.
     *
     * When [changedPropertyName] is provided, the old-key removal is scoped to the single index
     * entry for that property, preventing cross-type bucket lookups when an entity has multiple
     * `@Indexed` properties of different value types. Without this guard, applying one property's
     * old key to a different property's sorted index triggers a [ClassCastException] inside the
     * comparator (e.g. a `String` key against an `Int`-typed `ConcurrentSkipListMap`).
     *
     * The set stored in each index bucket always holds the live object reference, so removal
     * passes [entity] rather than a separate snapshot reference.
     *
     * @param entity the entity to reindex
     * @param oldIndexKey the pre-mutation value of the `@Indexed` property, or `null` when no
     *   indexed property was touched
     * @param newIndexKey unused — retained for call-site symmetry; the post-mutation placement is
     *   derived from the live entity state via [indexEntity]
     * @param changedPropertyName the property whose index must be updated; when `null`, all index
     *   entries are candidates for removal (safe only when the entity has a single `@Indexed`
     *   property, or when called from a batch path that has already validated type safety)
     */
    @Suppress("UNCHECKED_CAST")
    protected fun reindexEntity(entity: T, oldIndexKey: Any?, newIndexKey: Any?, changedPropertyName: String? = null) {
        val entries = indexEntries ?: return
        if (oldIndexKey == null && newIndexKey == null) return

        // Remove entity from the old bucket. When changedPropertyName is provided, limit removal to
        // the matching index entry — applying one property's captured key to a different property's
        // index can cause a ClassCastException in sorted indexes if the value types differ.
        for (entry in entries) {
            if (changedPropertyName != null && entry.propertyName != changedPropertyName) continue
            if (entry.sorted) {
                // Attempt removal only when oldIndexKey is Comparable — required by sorted indexes.
                val sortedOld = oldIndexKey as? Comparable<Any> ?: continue
                removeFromBucket(sortedIndexes[entry.indexName], sortedOld, entity)
            } else {
                if (oldIndexKey != null) {
                    removeFromBucket(hashIndexes[entry.indexName], oldIndexKey, entity)
                }
            }
        }
        indexEntity(entity)
    }

    // Atomically remove [entity] from the value bucket and prune the key if the set becomes empty.
    // ConcurrentMap.computeIfPresent guarantees the remove + emptiness check happen within the
    // same per-key lock, so a concurrent add for the same key never observes a transient empty set.
    private fun <K : Any> removeFromBucket(bucket: ConcurrentMap<K, MutableSet<T>>?, key: K, entity: T) {
        bucket?.computeIfPresent(key) { _, set ->
            set.remove(entity)
            if (set.isEmpty()) null else set
        }
    }

    /**
     * Clears all value buckets in every secondary index, across both hash and sorted bucket kinds.
     * O(n_indexes) operation — does not iterate entities.
     * Intended for use in bulk-clear operations such as [net.transgressoft.lirp.persistence.VolatileRepository.clear].
     */
    protected fun clearSecondaryIndexes() {
        hashIndexes.values.forEach { it.clear() }
        sortedIndexes.values.forEach { it.clear() }
    }

    /**
     * Returns `true` if the given property corresponds to a declared `@Indexed` property
     * on this registry's entity type.
     *
     * Matches by [IndexEntry.propertyName] so that properties with a custom
     * `@Indexed(name = "...")` are correctly identified.
     */
    internal fun isPropertyIndexed(prop: KProperty1<T, *>): Boolean =
        indexEntries?.any { it.propertyName == prop.name } == true

    /**
     * Returns the index name for a property, or `null` if the property is not indexed.
     *
     * The returned name is the resolved index name (from [Indexed.name] or the property name),
     * suitable for passing to [findByIndex].
     */
    internal fun indexNameFor(prop: KProperty1<T, *>): String? =
        indexEntries?.find { it.propertyName == prop.name }?.indexName

    /**
     * Returns `true` iff the given property has a declared `@Indexed(sorted = true)` entry.
     *
     * The sorted flag drives the query planner's choice between a sorted range-slice and a hash lookup,
     * so callers can use this to guard range operations before calling [sortedBucketFor].
     */
    internal fun isPropertySortedIndexed(prop: KProperty1<T, *>): Boolean =
        indexEntries?.any { it.propertyName == prop.name && it.sorted } == true

    /**
     * Returns the live [java.util.NavigableMap] backing the sorted index for [indexName], or `null`
     * when the index is hash-bucketed or not declared.
     *
     * The returned map is the same [ConcurrentSkipListMap] used for entity storage —
     * callers must not mutate it. The lock-free `tailMap`/`headMap`/`subMap` views it provides are the
     * intended entry point for range-slice queries in the query planner.
     */
    internal fun sortedBucketFor(indexName: String): java.util.NavigableMap<Comparable<Any>, MutableSet<T>>? =
        sortedIndexes[indexName]

    /**
     * Loads the KSP-generated [LirpRefAccessor] for the entity's class via a convention-based
     * [KspAccessorLoader] lookup (`{EntityClassName}_LirpRefAccessor`). Uses double-checked locking
     * to ensure loading runs exactly once and the result is visible to all threads.
     *
     * The generated accessor provides [RefEntry] descriptors with direct ID getter lambdas,
     * completely avoiding `kotlin-reflect` or `java.lang.reflect` overhead. If no generated
     * accessor is found (KSP not applied or no `@ToOneAggregate` / `@ToManyAggregates` annotations),
     * the reference entry list remains empty.
     *
     * Anonymous and local class entities are skipped early — they can never have KSP-generated
     * accessors and do not require the [failFastIfDelegatePresent] check.
     */
    protected fun discoverRefs(entity: T) {
        if (refEntries != null) return
        synchronized(this) {
            if (refEntries != null) return
            if (entity.javaClass.isAnonymousClass || entity.javaClass.isLocalClass) {
                collectionRefEntries = emptyList()
                refEntries = emptyList()
                return
            }
            val accessor = KspAccessorLoader.load<LirpRefAccessor<T>>(entity.javaClass, KspAccessorLoader.REF_ACCESSOR_SUFFIX)
            if (accessor != null) {
                collectionRefEntries = accessor.collectionEntries
                refEntries = accessor.entries
            } else {
                failFastIfDelegatePresent(entity.javaClass, AggregateRefDelegate::class.java, "LirpRefAccessor")
                failFastIfDelegatePresent(entity.javaClass, AggregateCollectionRef::class.java, "LirpRefAccessor")
                failFastIfDelegatePresent(entity.javaClass, MutableAggregateCollectionRef::class.java, "LirpRefAccessor")
                failFastIfDelegatePresent(entity.javaClass, FxObservableCollection::class.java, "LirpRefAccessor")
                collectionRefEntries = emptyList()
                refEntries = emptyList()
            }
        }
    }

    /**
     * Loads the KSP-generated [LirpViaAccessor] for the entity's class via a convention-based
     * [KspAccessorLoader] lookup (`{EntityClassName}_LirpViaAccessor`). Uses double-checked locking
     * to ensure loading runs exactly once and the result is visible to all threads — mirrors
     * [discoverIndexes] exactly.
     *
     * The generated accessor provides typed [KProperty1] descriptors used by the cross-aggregate
     * Query DSL planner to resolve `via(prop)` references at query time, completely avoiding
     * `kotlin-reflect`. If no generated accessor is found (entity has no `@ToOneAggregate` / `@ToManyAggregates` properties
     * or KSP not applied), the cached accessor remains `null`.
     *
     * Anonymous and local class entities are skipped early — they can never have KSP-generated
     * accessors because they lack stable binary names.
     */
    protected fun discoverViaAccessors(entity: T) {
        if (viaAccessorDiscovered) return
        synchronized(this) {
            if (viaAccessorDiscovered) return
            if (entity.javaClass.isAnonymousClass || entity.javaClass.isLocalClass) {
                viaAccessor = null
                viaAccessorDiscovered = true
                return
            }
            viaAccessor = KspAccessorLoader.load(entity.javaClass, KspAccessorLoader.VIA_ACCESSOR_SUFFIX)
            viaAccessorDiscovered = true
        }
    }

    /**
     * Returns the cached [LirpViaAccessor] for this registry's entity type, or `null` when no
     * generated accessor exists. Intended as a test-state inspection seam — production callers
     * should rely on the per-instance discovery wired into entity registration paths and the
     * companion [viaAccessorFor] cross-class cache for planner lookups.
     */
    internal fun viaAccessorOrNull(): LirpViaAccessor<T>? = viaAccessor

    /**
     * Checks whether [entityClass] has any `${'$'}delegate` backing fields whose type is assignable
     * from [delegateType]. If so, throws [IllegalStateException] indicating that the KSP-generated
     * accessor class was not found.
     *
     * LIRP annotations use [AnnotationRetention.BINARY] which is invisible to runtime reflection.
     * Instead, the check inspects the JVM backing fields: Kotlin stores `by aggregate { ... }`
     * delegate properties as `<propName>${'$'}delegate` fields of type [AggregateRefDelegate]. If such
     * fields exist but no [LirpRefAccessor] was generated, the entity was not processed by KSP.
     *
     * This check uses reflection exactly once per entity type (guarded by the double-checked locking
     * in [discoverRefs] and [discoverIndexes]) and only executes on the error path (ClassNotFoundException).
     * It is not on the hot path for entity operations.
     *
     * @param entityClass the entity class whose declared fields should be inspected
     * @param delegateType the delegate type to look for (e.g., [AggregateRefDelegate])
     * @param accessorSuffix the suffix of the expected KSP-generated accessor class (e.g., "LirpRefAccessor")
     */
    private fun failFastIfDelegatePresent(entityClass: Class<*>, delegateType: Class<*>, accessorSuffix: String) {
        var clazz: Class<*>? = entityClass
        while (clazz != null) {
            val hasDelegateField =
                clazz.declaredFields.any { field ->
                    field.name.endsWith("\$delegate") && delegateType.isAssignableFrom(field.type)
                }
            check(!hasDelegateField) {
                "Entity ${entityClass.simpleName} has ${delegateType.simpleName} delegate properties " +
                    "but no KSP-generated $accessorSuffix was found. Ensure the lirp-ksp processor is applied."
            }
            clazz = clazz.superclass
        }
    }

    /**
     * Binds each [AggregateRefDelegate] on [entity] to the [Registry] that holds its referenced entity type,
     * using the [RefEntry] descriptors discovered via [discoverRefs].
     *
     * Also binds each collection reference delegate ([AggregateCollectionRef] implementation)
     * discovered via [CollectionRefEntry] descriptors.
     *
     * The unchecked casts consolidate type erasure at one call site. They are safe because
     * [RefEntry.referencedClass] and the delegate's K type are consistent — the KSP processor
     * generates both from the same referenced entity class declaration.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun bindEntityRefs(entity: T) {
        val entries = refEntries ?: return
        for (entry in entries) {
            val registry = context.registryFor(entry.referencedClass) ?: continue
            val typed = entry.delegateGetter(entity) as AggregateRefDelegate<Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
            typed.bindRegistry(registry as Registry<Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, context)
        }
        bindCollectionRefs(entity)
        bindFxScalarDelegates(entity)
        (entity as? net.transgressoft.lirp.entity.ReactiveEntityBase<*, *>)?.bindPolymorphicArms(context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindCollectionRefs(entity: T) {
        val collEntries = collectionRefEntries ?: return
        for (entry in collEntries) {
            val registry = context.registryFor(entry.referencedClass) ?: continue
            val delegate = entry.delegateGetter(entity)
            val inner = unwrapCollectionDelegate(delegate)
            if (inner != null) {
                (inner as AbstractAggregateCollectionRefDelegate<Comparable<Any>, IdentifiableEntity<Comparable<Any>>>)
                    .bindRegistry(registry as Registry<Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, context)
            }
            val mutableInner = unwrapMutableDelegate(delegate)
            if (mutableInner != null && entity is ReactiveEntityBase<*, *>) {
                mutableInner.bindCollectionEmissionCallback { event ->
                    entity.emitCollectionChangeEvent(entry.refName, event)
                }
            }
            if (delegate is FxObservableCollection<*, *>) {
                delegate.syncLocalCache()
            }
        }
    }

    private fun bindFxScalarDelegates(entity: T) {
        if (entity !is ReactiveEntityBase<*, *>) return
        for ((propName, delegate) in entity.delegateRegistry) {
            if (delegate is FxScalarPropertyDelegate) {
                delegate.bindMutationCallback { oldValue, newValue, mutationBlock ->
                    entity.emitFxScalarPropertyChanged(propName, oldValue, newValue, mutationBlock)
                }
            }
        }
    }

    private fun unwrapCollectionDelegate(delegate: Any?): AbstractAggregateCollectionRefDelegate<*, *>? =
        when (delegate) {
            is MutableAggregateList<*, *> -> delegate.innerDelegate
            is MutableAggregateSet<*, *> -> delegate.innerDelegate
            is AggregateListProxy<*, *> -> delegate.innerDelegate
            is AggregateSetProxy<*, *> -> delegate.innerDelegate
            is AbstractAggregateCollectionRefDelegate<*, *> -> delegate
            is FxObservableCollection<*, *> -> unwrapCollectionDelegate(delegate.innerMutableProxy)
            else -> null
        }

    private fun unwrapMutableDelegate(delegate: Any?): AbstractMutableAggregateCollectionRefDelegate<*, *>? =
        when (delegate) {
            is MutableAggregateList<*, *> -> delegate.innerDelegate
            is MutableAggregateSet<*, *> -> delegate.innerDelegate
            is AbstractMutableAggregateCollectionRefDelegate<*, *> -> delegate
            is FxObservableCollection<*, *> -> unwrapMutableDelegate(delegate.innerMutableProxy)
            else -> null
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
            entry.delegateGetter(entity).wireBubbleUp(entity as ReactiveEntity<*, *>, entry.refName)
        }
    }

    /**
     * Executes cascade actions for all aggregate references declared on [entity], including both
     * single-entity references and collection-typed references.
     *
     * Called by [VolatileRepository] during [net.transgressoft.lirp.persistence.VolatileRepository.remove]
     * and [net.transgressoft.lirp.persistence.VolatileRepository.clear]. Each reference delegate's
     * [AggregateRefDelegate.executeCascade] (for single refs) or collection delegate's
     * `executeCascade` (for collection refs) is invoked with its configured [net.transgressoft.lirp.entity.CascadeAction].
     *
     * Uses a [ThreadLocal] visited set to detect and reject cyclic cascade graphs. If [entity] is
     * already being cascaded on the current thread, an [IllegalStateException] is thrown immediately.
     * The set is cleared after the top-level cascade entry point returns.
     */
    protected fun executeCascadeForEntity(entity: T) {
        val entries = refEntries ?: return
        val visited = context.cascadeVisited.get()
        val isTopLevel = visited.isEmpty()
        val key = cascadeKey(entity.javaClass, entity.id)
        try {
            check(visited.add(key)) {
                "Cascade cycle detected: entity '${entity.uniqueId}' is already being cascaded on this thread"
            }
            for (entry in entries) {
                entry.delegateGetter(entity).executeCascade(entry.cascadeAction, entity)
            }
            val collEntries = collectionRefEntries
            if (collEntries != null) {
                for (entry in collEntries) {
                    val delegate = entry.delegateGetter(entity)
                    val inner = unwrapCollectionDelegate(delegate)
                    inner?.executeCascade(entry.cascadeAction, entity)
                }
            }
        } finally {
            if (isTopLevel) visited.clear()
        }
    }

    override fun findByIndex(indexName: String, value: Any): Set<T> {
        val sortedBucket = sortedIndexes[indexName]
        if (sortedBucket != null) {
            return sortedBucket[requireComparableKey(indexName, value)]?.toSet() ?: emptySet()
        }
        val hashBucket =
            hashIndexes[indexName]
                ?: throw IllegalArgumentException("No index declared for property '$indexName'")
        return hashBucket[value]?.toSet() ?: emptySet()
    }

    /**
     * Returns the live index bucket for [value] under [indexName] without copying it.
     *
     * Both bucket types — sorted navigable-set and hash `ConcurrentHashMap.newKeySet()` — are
     * weakly-consistent concurrent sets safe to iterate without a defensive copy. Callers must
     * only read (intersect/flatMap), never mutate the returned collection.
     *
     * The public [findByIndex] contract (defensive `.toSet()` snapshot) is preserved for external
     * callers. This internal variant exists solely for the query planner hot path where the
     * per-lookup allocation matters.
     */
    internal fun findByIndexNoCopy(indexName: String, value: Any): Collection<T> {
        val sortedBucket = sortedIndexes[indexName]
        if (sortedBucket != null) {
            return sortedBucket[requireComparableKey(indexName, value)] ?: emptySet()
        }
        val hashBucket =
            hashIndexes[indexName]
                ?: throw IllegalArgumentException("No index declared for property '$indexName'")
        return hashBucket[value] ?: emptySet()
    }

    override fun findFirstByIndex(indexName: String, value: Any): Optional<out T> {
        val sortedBucket = sortedIndexes[indexName]
        if (sortedBucket != null) {
            return Optional.ofNullable(sortedBucket[requireComparableKey(indexName, value)]?.firstOrNull())
        }
        val hashBucket =
            hashIndexes[indexName]
                ?: throw IllegalArgumentException("No index declared for property '$indexName'")
        return Optional.ofNullable(hashBucket[value]?.firstOrNull())
    }

    // Sorted-index buckets are NavigableMaps keyed by Comparable<Any>; an unchecked cast on a
    // non-Comparable caller value would throw a low-context ClassCastException deep inside the
    // map. Validate up-front and report which index and runtime type were involved.
    @Suppress("UNCHECKED_CAST")
    private fun requireComparableKey(indexName: String, value: Any): Comparable<Any> =
        value as? Comparable<Any>
            ?: throw IllegalArgumentException(
                "Sorted index '$indexName' expects a Comparable key, " +
                    "got ${value::class.qualifiedName ?: value::class.java.name}"
            )

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
         * Registers a [RegistryBase] instance for the given entity class in the default [LirpContext].
         *
         * Intended for delegation-based repositories that do not extend [RegistryBase] directly
         * but wrap one via Kotlin's `by` delegation. The caller passes the delegate [RegistryBase]
         * instance, which becomes the registry discoverable via `LirpContext.default.registries()`.
         *
         * @param entityClass the entity class to register under
         * @param registry the delegate [RegistryBase] to register (must be a [RegistryBase] instance)
         * @throws IllegalArgumentException if [registry] is not a [RegistryBase] instance or was created
         *   outside [LirpContext.default]
         * @throws IllegalStateException if a different repository is already registered for [entityClass]
         */
        @JvmStatic
        fun registerRepository(entityClass: Class<*>, registry: Repository<*, *>) {
            require(registry is RegistryBase<*, *>) {
                "Only RegistryBase instances can be registered via registerRepository(). Got: ${registry::class.qualifiedName}"
            }
            val context = LirpContext.default
            require(registry.context === context) {
                "registerRepository() only supports RegistryBase instances created in LirpContext.default."
            }
            val registered = context.register(entityClass, registry)
            check(registered || context.registryFor(entityClass) === registry) {
                "A repository for ${entityClass.simpleName} is already registered. Only one @LirpRepository per entity type is allowed."
            }
            if (registered) {
                rebindReferencesTo(entityClass, context)
            }
        }

        /**
         * Rebinds aggregate-ref delegates that target [referencedClass] for every entity already
         * present in any registry of [context]. Called from [registerRepository] right after a new
         * registry is registered, so that entities whose [bindEntityRefs] ran before this point
         * (when the [referencedClass] registry was still unregistered) get their previously-skipped
         * delegates wired up against the freshly-registered registry.
         *
         * Without this pass, delegates that observed a missing registry at load time remain
         * unbound forever and `resolveAll()` returns an empty set even after registration.
         *
         * For each rebound entity, [wireRefBubbleUp] is also re-run so that scalar refs declared
         * with `bubbleUp = true` get their parent->child subscription created against the freshly
         * resolvable referenced entity. Without this second pass, mutations on the newly registered
         * children would never propagate to parent subscribers until a `resolve()` call lazily
         * triggered the rewire.
         *
         * Only registries whose entity type declares a ref (scalar or collection) to
         * [referencedClass] are visited; everything else is skipped via the cached
         * [LirpRefAccessor]. Both [bindEntityRefs] and [wireRefBubbleUp] are idempotent —
         * already-bound delegates simply have their registry/subscription reference re-set.
         */
        private fun rebindReferencesTo(referencedClass: Class<*>, context: LirpContext) {
            for ((_, otherRegistry) in context.registriesSnapshot()) {
                if (otherRegistry !is RegistryBase<*, *>) continue
                @Suppress("UNCHECKED_CAST")
                val typed = otherRegistry as RegistryBase<Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
                for (entity in typed) {
                    // Look up the accessor by the entity's concrete runtime class — refAccessorFor
                    // resolves "${concreteClass.name}_LirpRefAccessor", and that class is generated
                    // per concrete aggregate-annotated entity, not per registered interface.
                    val accessor = refAccessorFor(entity.javaClass) ?: continue
                    val refsThisClass =
                        accessor.entries.any { it.referencedClass == referencedClass } ||
                            accessor.collectionEntries.any { it.referencedClass == referencedClass }
                    if (!refsThisClass) continue
                    typed.bindEntityRefs(entity)
                    typed.wireRefBubbleUp(entity)
                }
            }
        }

        /**
         * Deregisters the repository for the given entity class from the default [LirpContext].
         *
         * Intended for delegation-based repositories that need to cleanly remove their
         * registration on shutdown or close. Only removes the mapping -- does not close
         * the repository or its publisher. Callers manage [close] separately.
         *
         * Calling this method for an entity class that has no registered repository
         * completes without error (idempotent no-op). Thread-safe: backed by atomic
         * [ConcurrentHashMap.remove][java.util.concurrent.ConcurrentHashMap.remove].
         *
         * @param entityClass the entity class to deregister
         */
        @JvmStatic
        fun deregisterRepository(entityClass: Class<*>) {
            LirpContext.default.deregisterByClass(entityClass)
        }

        /**
         * Computes the cascade key for an entity: `"${entityClass.name}:${entityId}"`.
         * This format allows cycle detection by class and ID without requiring a live registry lookup.
         */
        @JvmStatic
        internal fun cascadeKey(entityClass: Class<*>, entityId: Any): String = "${entityClass.name}:$entityId"

        /**
         * Returns the [LirpRefAccessor] for [entityClass], loading it via [KspAccessorLoader] on
         * first call and caching the result. Returns `null` if no KSP-generated accessor exists
         * for the class.
         */
        @JvmStatic
        internal fun refAccessorFor(entityClass: Class<*>): LirpRefAccessor<Any>? {
            if (entityClass.isAnonymousClass || entityClass.isLocalClass)
                return null
            return KspAccessorLoader.load(entityClass, KspAccessorLoader.REF_ACCESSOR_SUFFIX)
        }

        /**
         * Returns the [LirpViaAccessor] for [entityClass], loading it via [KspAccessorLoader] on
         * first call and caching the result. Returns `null` if no KSP-generated accessor exists
         * for the class (entity has no `@ToOneAggregate` / `@ToManyAggregates` properties) or when [entityClass] is anonymous
         * or local (no stable binary name).
         *
         * Consumed by the cross-aggregate Query DSL planner to resolve `via(prop)` references to
         * the descriptor that names the child entity class.
         */
        @JvmStatic
        internal fun viaAccessorFor(entityClass: Class<*>): LirpViaAccessor<Any>? {
            if (entityClass.isAnonymousClass || entityClass.isLocalClass)
                return null
            return KspAccessorLoader.load(entityClass, KspAccessorLoader.VIA_ACCESSOR_SUFFIX)
        }

        /**
         * Returns the [LirpRawInitializer] for [entityClass], loading it via [KspAccessorLoader]
         * on first call and caching the result.
         *
         * Throws a clear `configure KSP` error when [entityClass] is a persisted entity but no
         * generated `<entityClass>_LirpRawInitializer` exists — KSP is mandatory for persisted
         * entities, and silently falling back to reflection is no longer supported.
         *
         * @throws IllegalStateException when the entity has no generated raw initializer
         */
        @JvmStatic
        internal fun rawInitializerFor(entityClass: Class<*>): LirpRawInitializer<Any> =
            KspAccessorLoader.load(entityClass, KspAccessorLoader.RAW_INITIALIZER_SUFFIX)
                ?: error(
                    "Entity ${entityClass.simpleName} has no generated LirpRawInitializer — apply the net.transgressoft.lirp.sql Gradle plugin or add lirp-ksp to your build.gradle dependencies block to configure KSP."
                )

        /**
         * Public cross-module entry point for [rawInitializerFor].
         *
         * `lirp-sql` (a separate Gradle / Kotlin module) needs to resolve raw initializers from
         * `SqlRepository.loadFromStore`. Kotlin `internal` visibility is module-scoped, so the
         * cross-module call site goes through this thin public wrapper. The actual cache and
         * accessor lookup live in [rawInitializerFor] via [KspAccessorLoader].
         *
         * @throws IllegalStateException when the entity has no generated raw initializer
         */
        @JvmStatic
        fun publicRawInitializerFor(entityClass: Class<*>): LirpRawInitializer<Any> =
            rawInitializerFor(entityClass)

        /**
         * Public cross-module entry point for [refAccessorFor].
         *
         * `lirp-sql` (a separate Gradle / Kotlin module) uses this to enumerate cascade targets
         * for a given entity class when validating that all reachable cascade repositories share
         * the same DataSource before committing a transaction. Returns `null` when the entity has
         * no KSP-generated ref accessor (i.e., no `@ToOneAggregate` / `@ToManyAggregates` references).
         */
        @JvmStatic
        fun publicRefAccessorFor(entityClass: Class<*>): LirpRefAccessor<Any>? =
            refAccessorFor(entityClass)

        /**
         * Returns the [LirpRawConstructor] for [entityClass], loading it via [KspAccessorLoader] on
         * first call and caching the result, or `null` when none exists.
         *
         * Unlike [rawInitializerFor], absence is not an error: most entities are constructed through
         * `SqlTableDef.fromRow` and have no constructor SPI. A `_LirpRawConstructor` exists only for
         * entities whose table descriptor opts into construction delegation (a
         * `RawConstructibleTableDef`); the SQL load path enforces presence at its own call site when
         * that opt-in is declared.
         */
        @JvmStatic
        internal fun rawConstructorFor(entityClass: Class<*>): LirpRawConstructor<Any>? =
            KspAccessorLoader.load(entityClass, KspAccessorLoader.RAW_CONSTRUCTOR_SUFFIX)

        /**
         * Public cross-module entry point for [rawConstructorFor].
         *
         * `lirp-sql` (a separate Gradle / Kotlin module) needs to resolve raw constructors from
         * `SqlRepository.loadFromStore`. Kotlin `internal` visibility is module-scoped, so the
         * cross-module call site goes through this thin public wrapper. The actual cache and accessor
         * lookup live in [rawConstructorFor] via [KspAccessorLoader].
         *
         * @return the resolved constructor, or `null` when the entity has no `_LirpRawConstructor`.
         */
        @JvmStatic
        fun publicRawConstructorFor(entityClass: Class<*>): LirpRawConstructor<Any>? =
            rawConstructorFor(entityClass)
    }
}
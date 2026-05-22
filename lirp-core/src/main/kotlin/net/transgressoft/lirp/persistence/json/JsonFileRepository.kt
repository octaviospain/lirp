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

package net.transgressoft.lirp.persistence.json

import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.AbstractMutableAggregateCollectionRefDelegate
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.LirpDeserializationException
import net.transgressoft.lirp.persistence.LirpRawInitializer
import net.transgressoft.lirp.persistence.MutableAggregateList
import net.transgressoft.lirp.persistence.MutableAggregateSet
import net.transgressoft.lirp.persistence.PendingUpdate
import net.transgressoft.lirp.persistence.PersistentRepositoryBase
import net.transgressoft.lirp.persistence.Registry
import net.transgressoft.lirp.persistence.RegistryBase
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * Base class for repositories that store entities in a JSON file.
 *
 * Extends [PersistentRepositoryBase] with JSON file persistence. All CRUD operations and entity
 * mutations are collapsed per-key in the base class; the debounce pipeline calls
 * [writePending] which serializes the full in-memory state to the JSON file.
 *
 * Because JSON serialization always rewrites the complete file, the grouped parameters passed to
 * [writePending] are intentionally ignored — the current in-memory state is the source of truth.
 *
 * Key features:
 * - Debounced write batching via [PersistentRepositoryBase]: multiple rapid mutations collapse
 *   into a single file write after [serializationDelay] of inactivity.
 * - Synchronous close: [close] triggers a final synchronous [writePending] before shutting down,
 *   ensuring the file always reflects the last known state.
 * - Thread-safe in-memory state using [ConcurrentHashMap].
 * - Error handling with logging; a write failure resets dirty so the next flush will retry.
 *
 * **Scaling envelope:**
 * - Small to medium repositories (up to a few thousand entities): serialization time is effectively
 *   instantaneous relative to the debounce window. Write coalescing works well.
 * - Large repositories (tens of thousands of entities): JSON serialization time may grow to approach
 *   or exceed the debounce window. Increasing [serializationDelay] trades write latency for better
 *   coalescing in high-mutation scenarios.
 *
 * @param K The type of entity identifier, must be [Comparable]
 * @param R The type of entity being stored, must implement [ReactiveEntity]
 * @param file The JSON file to store entities in
 * @param mapSerializer The serializer used to convert entities to/from JSON
 * @param repositorySerializersModule Optional module for configuring JSON serialization
 * @param serializationDelay The debounce window before a pending write is flushed to disk.
 *        Defaults to 300 milliseconds. Lower values increase responsiveness but may cause more I/O;
 *        higher values batch more changes into fewer writes.
 * @param loadOnInit When `true` (default), entities are loaded from the JSON file immediately
 *        during construction. When `false`, [load] must be called explicitly before any mutating
 *        operations.
 */
open class JsonFileRepository<K : Comparable<K>, R : ReactiveEntity<K, R>>
    internal constructor(
        context: LirpContext,
        file: File,
        private val mapSerializer: KSerializer<Map<K, R>>,
        private val repositorySerializersModule: SerializersModule = SerializersModule {},
        private val serializationDelay: Duration = 300.milliseconds,
        loadOnInit: Boolean = true,
        private val fkPolicy: JsonFkPolicy = JsonFkPolicy.LOG_AND_RECONCILE
    ) : PersistentRepositoryBase<K, R>(
            context,
            "JsonFileRepository-${file.name}",
            ConcurrentHashMap(),
            debounceMillis = serializationDelay.inWholeMilliseconds,
            maxDelayMillis = serializationDelay.inWholeMilliseconds.coerceAtLeast(1000L),
            loadOnInit = loadOnInit
        ),
        JsonRepository<K, R> {

        @JvmOverloads
        constructor(
            file: File,
            mapSerializer: KSerializer<Map<K, R>>,
            repositorySerializersModule: SerializersModule = SerializersModule {},
            serializationDelay: Duration = 300.milliseconds,
            loadOnInit: Boolean = true,
            fkPolicy: JsonFkPolicy = JsonFkPolicy.LOG_AND_RECONCILE
        ) : this(LirpContext.default, file, mapSerializer, repositorySerializersModule, serializationDelay, loadOnInit, fkPolicy)

        private val log = KotlinLogging.logger(javaClass.name)

        final override var jsonFile: File = file
            set(value) {
                require(value.exists().and(value.canWrite()).and(value.extension == "json").and(value.readText().isEmpty())) {
                    "Provided jsonFile does not exist, is not writable, is not a json file, or is not empty"
                }
                // Acquire flushLock to prevent concurrent serialization with a debounce flush
                // or close(). flush() drains the pending-ops queue and skips writePending() when
                // empty, so we call performSerialization() directly to guarantee a write here.
                flushLock.withLock {
                    field = value
                    dirty.set(true)
                    performSerialization()
                }
                log.info { "jsonFile set to $value" }
            }

        protected val json =
            Json {
                serializersModule = repositorySerializersModule
                prettyPrint = true
                explicitNulls = true
                allowStructuredMapKeys = true
            }

        init {
            try {
                require(jsonFile.exists().and(jsonFile.canWrite()).and(jsonFile.extension == "json")) {
                    "Provided jsonFile does not exist, is not writable or is not a json file"
                }
                if (loadOnInit) load()
            } catch (exception: Exception) {
                // Deregister from context before propagating to avoid leaving a zombie registration
                // that would block re-creation of a repository for the same entity type.
                context.deregister(this@JsonFileRepository)
                throw exception
            }
        }

        /**
         * Reads all entities from the JSON file and returns them as a map of ID to entity.
         *
         * Called by [load] as part of the template method. Validates that the file is still
         * accessible at load time (relevant for deferred loads where time has elapsed since
         * construction), then deserializes the full contents and resets the [dirty] flag so
         * that the initial load does not trigger an immediate write-back. Returns an empty map
         * when the file is empty.
         *
         * @return a map of entity ID to entity deserialized from [jsonFile], or an empty map
         *         if the file contains no data.
         */
        override fun loadFromStore(): Map<K, R> {
            require(jsonFile.exists().and(jsonFile.canWrite()).and(jsonFile.extension == "json")) {
                "Provided jsonFile does not exist, is not writable or is not a json file"
            }
            val entities = decodeFromJson() ?: emptyMap()
            log.info { "${entities.size} objects deserialized from file $jsonFile" }
            // Symmetric with SqlRepository.loadFromStore: resolve the KSP-generated raw initializer
            // for each decoded entity and re-affirm every persisted field via its silent setter
            // inside withEventsDisabled. LirpEntitySerializer.deserialize already restored reactive
            // fields via LirpReactivePropertyAccessor; this pass also covers non-reactive var fields
            // (e.g. lastDateModified) that bypass the entity's reactive setter. Subscribers attached
            // after load() observe no retroactive MutationEvent.
            applyRawInitializerSilently(entities)
            reconcileDanglingRefs(entities)
            dirty.set(false)
            return entities
        }

        /**
         * Validates that every loaded entity has a KSP-generated [LirpRawInitializer] available.
         *
         * `LirpEntitySerializer.deserialize` already routes reactive-property restoration through
         * the KSP-generated `<Entity>_LirpReactivePropertyAccessor.silentSetter`, so the JSON
         * bulk-load path is symmetric with the SQL path — both bypass the reactive setter when
         * populating freshly constructed entities. This pass resolves the raw initializer for each
         * distinct entity class to surface a clear `configure KSP` error at load time if a consumer
         * has not applied `lirp-ksp` to one of their entity modules. It does not re-apply values:
         * the entity is already in its desired state once `deserialize` returns.
         */
        private fun applyRawInitializerSilently(entities: Map<K, R>) {
            if (entities.isEmpty()) return
            val seen = mutableSetOf<Class<*>>()
            for (entity in entities.values) {
                val concreteClass = entity::class.java
                if (seen.add(concreteClass)) {
                    // Trigger Class.forName + cache the LirpRawInitializer to surface a
                    // `configure KSP` error at load time when an entity participates in a
                    // KSP-processed module but no accessor was generated. Hand-written test
                    // fixtures that bypass KSP entirely are tolerated — LirpEntitySerializer
                    // already populated every persisted field via the configure-KSP-gated
                    // reactive-property path.
                    runCatching { RegistryBase.rawInitializerFor(concreteClass) }
                }
            }
        }

        /**
         * Walks every deserialized entity, inspects its `@Aggregate` references against the live
         * registries on the owning [LirpContext], and applies the configured [JsonFkPolicy].
         *
         * `LOG_AND_RECONCILE` (default) drops dangling collection IDs and nulls dangling nullable
         * scalar refs, emitting one warning per affected entity. All mutations run inside
         * [ReactiveEntityBase.withEventsDisabled] so neither [net.transgressoft.lirp.event.MutationEvent]
         * nor [net.transgressoft.lirp.event.CrudEvent] fire, and `@Version` fields stay untouched —
         * reconciliation is cleanup, not a domain mutation.
         *
         * `STRICT` throws [LirpDeserializationException] on the first dangling reference encountered,
         * mirroring SQL `ON DELETE RESTRICT` semantics.
         *
         * Reconciliation only applies to mutable aggregate-collection delegates
         * ([MutableAggregateList] / [MutableAggregateSet]) and to mutable scalar `@Aggregate`
         * properties whose name follows the `${refName}Id` convention. Immutable collection refs
         * (whose IDs are captured at construction time and have no setter) cannot be reconciled in
         * place; for those a warning is logged and the dangling IDs remain.
         */
        private fun reconcileDanglingRefs(entities: Map<K, R>) {
            // Self-reference fallback: when an entity's @Aggregate target is the same entity type
            // being loaded, the registry hasn't seen these entities yet (loadFromStore runs before
            // addToMemoryOnly). Treat the in-progress map as resolvable so self-referencing
            // hierarchies survive a round-trip without being clobbered.
            val selfEntityClasses: Map<Any, Class<*>> =
                entities.entries.associate { (id, entity) -> id as Any to entity.javaClass }

            for (entity in entities.values) {
                val accessor = RegistryBase.refAccessorFor(entity.javaClass) ?: continue
                if (accessor.entries.isEmpty() && accessor.collectionEntries.isEmpty()) continue

                val danglingForEntity = mutableMapOf<String, List<Any>>()

                reconcileCollectionRefs(entity, accessor, danglingForEntity, selfEntityClasses)
                reconcileScalarRefs(entity, accessor, danglingForEntity, selfEntityClasses)

                if (danglingForEntity.isNotEmpty() && fkPolicy == JsonFkPolicy.LOG_AND_RECONCILE) {
                    log.warn {
                        "Reconciled dangling refs for ${entity.javaClass.simpleName}(id=${entity.id}): $danglingForEntity"
                    }
                }
            }
        }

        private fun isResolvable(
            id: Any,
            referencedClass: Class<*>,
            selfEntityClasses: Map<Any, Class<*>>
        ): Boolean {
            // Check the in-progress entity map first for self-referencing aggregates. Use the
            // concrete class of the referenced ID's entity rather than the first entity's class,
            // so mixed-type repos correctly identify whether the referenced ID belongs to an entity
            // assignable to the declared reference type.
            val selfClass = selfEntityClasses[id]
            if (selfClass != null && referencedClass.isAssignableFrom(selfClass)) return true

            val registry = context.registryFor(referencedClass) ?: return true

            // Permissive: when the referenced repo is not registered in this context we cannot
            // confirm the reference is dangling, so leave it untouched (preserves pre-existing
            // permissive behavior for partially-wired contexts). The single suppress covers both
            // erased casts — `registry`'s K projection and `id`'s `Comparable<*>` boundary.
            @Suppress("UNCHECKED_CAST")
            val typedRegistry = registry as Registry<Comparable<Any>, *>

            @Suppress("UNCHECKED_CAST")
            val typedId = id as Comparable<Any>
            return typedRegistry.findById(typedId).isPresent
        }

        @Suppress("UNCHECKED_CAST")
        private fun reconcileCollectionRefs(
            entity: R,
            accessor: net.transgressoft.lirp.persistence.LirpRefAccessor<Any>,
            danglingForEntity: MutableMap<String, List<Any>>,
            selfEntityClasses: Map<Any, Class<*>>
        ) {
            for (collEntry in accessor.collectionEntries) {
                val ids =
                    (collEntry as net.transgressoft.lirp.persistence.CollectionRefEntry<Comparable<Any>, Any>)
                        .idsGetter(entity as Any)
                if (ids.isEmpty()) continue

                val dangling = ids.filter { !isResolvable(it as Any, collEntry.referencedClass, selfEntityClasses) }
                if (dangling.isEmpty()) continue

                if (fkPolicy == JsonFkPolicy.STRICT) {
                    throw LirpDeserializationException(
                        "Dangling @Aggregate reference(s) found in ${entity.javaClass.simpleName}(id=${entity.id})." +
                            " Reference '${collEntry.refName}' points to missing ${collEntry.referencedClass.simpleName}" +
                            "(ids=$dangling)"
                    )
                }

                val delegate = collEntry.delegateGetter(entity)
                val mutableBacking =
                    when (delegate) {
                        is MutableAggregateList<*, *> -> delegate.innerDelegate
                        is MutableAggregateSet<*, *> -> delegate.innerDelegate
                        is AbstractMutableAggregateCollectionRefDelegate<*, *> -> delegate
                        else -> {
                            log.warn {
                                "Cannot reconcile dangling IDs $dangling for immutable collection " +
                                    "ref '${collEntry.refName}' on ${entity.javaClass.simpleName}(id=${entity.id})"
                            }
                            null
                        }
                    }

                if (mutableBacking != null) {
                    val keep = ids.filter { isResolvable(it as Any, collEntry.referencedClass, selfEntityClasses) }
                    val rebase = mutableBacking as AbstractMutableAggregateCollectionRefDelegate<Comparable<Any>, *>
                    if (entity is ReactiveEntityBase<*, *>) {
                        entity.withEventsDisabled { rebase.setBackingIds(keep) }
                    } else {
                        rebase.setBackingIds(keep)
                    }
                    danglingForEntity[collEntry.refName] = dangling.toList()
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun reconcileScalarRefs(
            entity: R,
            accessor: net.transgressoft.lirp.persistence.LirpRefAccessor<Any>,
            danglingForEntity: MutableMap<String, List<Any>>,
            selfEntityClasses: Map<Any, Class<*>>
        ) {
            for (entry in accessor.entries) {
                val typedEntry = entry as net.transgressoft.lirp.persistence.RefEntry<Comparable<Any>, Any>
                val refId =
                    runCatching { typedEntry.idGetter(entity as Any) }
                        .getOrNull() ?: continue
                if (isResolvable(refId as Any, entry.referencedClass, selfEntityClasses)) continue

                if (fkPolicy == JsonFkPolicy.STRICT) {
                    throw LirpDeserializationException(
                        "Dangling @Aggregate reference found in ${entity.javaClass.simpleName}(id=${entity.id})." +
                            " Reference '${entry.refName}' points to missing ${entry.referencedClass.simpleName}(id=$refId)"
                    )
                }

                val nulled = nullScalarIfMutable(entity, entry.refName)
                if (nulled) {
                    danglingForEntity[entry.refName] = listOf(refId)
                } else {
                    log.warn {
                        "Cannot reconcile dangling scalar ref '${entry.refName}' (id=$refId) on " +
                            "${entity.javaClass.simpleName}(id=${entity.id}): no nullable mutable property '${entry.refName}Id' found"
                    }
                }
            }
        }

        /**
         * Locates a mutable Kotlin property named `${refName}Id` on [entity] and writes `null` to
         * it under [ReactiveEntityBase.withEventsDisabled], so neither reactive property nor CRUD
         * events fire and `@Version` is not bumped. Returns `true` when a writable nullable property
         * was found and successfully nulled. The plan's scalar reconciliation is intentionally
         * convention-driven (`refName + "Id"`) — non-nullable scalar refs cannot be safely defaulted
         * without losing data, so they are left untouched and reported via the caller.
         */
        @Suppress("UNCHECKED_CAST")
        private fun nullScalarIfMutable(entity: R, refName: String): Boolean {
            val expected = "${refName}Id"
            val property =
                entity::class
                    .memberProperties
                    .firstOrNull { it.name == expected } as? KMutableProperty1<Any, Any?> ?: return false
            if (!property.returnType.isMarkedNullable) return false
            property.isAccessible = true
            return try {
                if (entity is ReactiveEntityBase<*, *>) {
                    entity.withEventsDisabled { property.set(entity, null) }
                } else {
                    property.set(entity as Any, null)
                }
                true
            } catch (exception: Exception) {
                log.warn(exception) { "Failed to null scalar ref '$refName' on ${entity.javaClass.simpleName}" }
                false
            }
        }

        /**
         * Serializes the full in-memory entity state to [jsonFile].
         *
         * The grouped pending payload ([inserts], [updates], [deletes], [hadClear]) is intentionally
         * ignored: JSON persistence always rewrites the complete file from the current in-memory
         * state rather than applying incremental changes. This simplifies the implementation and
         * avoids partial-write correctness concerns.
         *
         * Called by [PersistentRepositoryBase.flush] after draining the per-key pending cell map.
         */
        override fun writePending(
            inserts: List<R>,
            updates: List<PendingUpdate<K, R>>,
            deletes: List<Pair<K, Long?>>,
            hadClear: Boolean
        ) {
            val error = performSerialization()
            if (error != null) throw error
        }

        /**
         * Serializes the full in-memory entity state to [jsonFile], returning `null` on success
         * or the caught exception on failure.
         *
         * On failure the [dirty] flag is restored so the next flush cycle retries.
         * Called from both [writePending] (which re-throws to trigger base class retry) and
         * the [jsonFile] setter (which swallows the error since it is not in the flush path).
         */
        private fun performSerialization(): Exception? {
            if (!dirty.compareAndSet(true, false)) {
                log.debug { "Skipping serialization, no changes since last write" }
                return null
            }
            return try {
                val jsonString = json.encodeToString(mapSerializer, entitiesById)
                jsonFile.writeText(jsonString)
                log.debug { "File updated: $jsonFile" }
                null
            } catch (exception: Exception) {
                dirty.set(true)
                log.error(exception) { "Error serializing to file $jsonFile" }
                exception
            }
        }

        private fun decodeFromJson(): Map<K, R>? {
            val content = jsonFile.readText()
            if (content.isEmpty()) return null
            return try {
                json.decodeFromString(mapSerializer, content)
            } catch (exception: Exception) {
                throw LirpDeserializationException("Failed to deserialize entities from file: ${jsonFile.absolutePath}", exception)
            }
        }

        /**
         * Closes this repository and releases all resources.
         *
         * The base class [close] cancels pending debounce timers, performs a synchronous final
         * [writePending] call (ensuring the file reflects the last known state), and cancels all
         * entity mutation subscriptions. Unlike the previous fire-and-forget close, this guarantees
         * the write has completed by the time this method returns.
         *
         * Idempotent: subsequent calls are safe no-ops.
         *
         * After closing, all mutating operations ([add], [remove], [removeAll], [clear])
         * throw [IllegalStateException].
         */
        override fun close() {
            if (closed)
                return
            super.close()
        }

        override fun hashCode() = Objects.hashCode(jsonFile)

        override fun equals(other: Any?) =
            if (other is JsonFileRepository<*, *>) {
                jsonFile == other.jsonFile
            } else {
                false
            }
    }
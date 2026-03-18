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
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.LirpDeserializationException
import net.transgressoft.lirp.persistence.VolatileRepository
import mu.KotlinLogging
import java.io.File
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * Base class for repositories that store entities in a JSON file.
 *
 * This class handles the serialization and deserialization of entities to/from a JSON file,
 * providing persistent storage with asynchronous write operations. It extends [VolatileRepository]
 * with file I/O capabilities, ensuring that repository operations are automatically persisted.
 *
 * Key features:
 * - Asynchronous JSON serialization using debouncing to optimize I/O operations
 * - Automatic persistence of all repository operations
 * - Thread-safe operations using ConcurrentHashMap by the upstream [net.transgressoft.lirp.persistence.Repository]
 * - Error handling with logging
 * - Subscription management for entity lifecycle
 *
 * ## Performance Characteristics
 *
 * **Single-threaded IO model:** All file writes are serialized through a single-threaded IO dispatcher
 * (`Dispatchers.IO.limitedParallelism(1)` via [ReactiveScope.ioScope]). This prevents concurrent writes
 * from corrupting the file and provides deterministic write ordering. The sequential constraint is by design.
 *
 * **Debounced write batching:** Multiple rapid mutations are collapsed into a single write after the
 * configurable [serializationDelay] (default 300ms). This means a burst of 1000 mutations within a
 * 300ms window produces exactly one file write, not 1000.
 *
 * **Scaling envelope:**
 * - Small to medium repositories (up to a few thousand entities): serialization time is effectively
 *   instantaneous relative to the debounce window. Write coalescing works well.
 * - Large repositories (tens of thousands of entities): JSON serialization time may grow to approach
 *   or exceed the debounce window. In this range, individual writes take longer, and the effective
 *   coalescing benefit diminishes as each write covers fewer mutations.
 * - Write throughput is bounded by single-thread JSON serialization time plus file write latency.
 *   Increasing [serializationDelay] trades write latency for better coalescing in high-mutation scenarios.
 *
 * @param K The type of entity identifier, must be [Comparable]
 * @param R The type of entity being stored, must implement [ReactiveEntity]
 * @param file The JSON file to store entities in
 * @param mapSerializer The serializer used to convert entities to/from JSON
 * @param repositorySerializersModule Optional module for configuring JSON serialization
 * @param serializationDelay The delay between the last repository change and the JSON file write.
 *        Defaults to 300 milliseconds. Lower values increase responsiveness but may cause more I/O;
 *        higher values batch more changes into fewer writes.
 */
open class JsonFileRepository<K : Comparable<K>, R : ReactiveEntity<K, R>>
    @JvmOverloads
    constructor(
        file: File,
        private val mapSerializer: KSerializer<Map<K, R>>,
        private val repositorySerializersModule: SerializersModule = SerializersModule {},
        private val serializationDelay: Duration = 300.milliseconds
    ) : VolatileRepository<K, R>("JsonFileRepository-${file.name}"), JsonRepository<K, R> {
        private val log = KotlinLogging.logger(javaClass.name)

        final override var jsonFile: File = file
            set(value) {
                require(value.exists().and(value.canWrite()).and(value.extension == "json").and(value.readText().isEmpty())) {
                    "Provided jsonFile does not exist, is not writable, is not a json file, or is not empty"
                }
                field = value
                markDirtyAndTrigger()
                log.info { "jsonFile set to $value" }
            }

        protected val json =
            Json {
                serializersModule = repositorySerializersModule
                prettyPrint = true
                explicitNulls = true
                allowStructuredMapKeys = true
            }

        /**
         * The coroutine scope used for file I/O operations. Defaults to a scope with
         * limitedParallelism(1) on the IO dispatcher to ensure sequential file access and thread safety.
         * For testing, provide a scope with a test dispatcher.
         * @see [ReactiveScope]
         */
        private val ioScope: CoroutineScope = ReactiveScope.ioScope

        /**
         * This coroutine scope is used to handle all emissions to the
         * JSON serialization job for a fire and forget approach
         */
        private val flowScope: CoroutineScope = ReactiveScope.flowScope

        private val serializationEventChannel = Channel<Unit>(Channel.CONFLATED)

        /**
         * Shared flow used to trigger serialization of the repository state. Debounced to avoid
         * excessive serialization operations when multiple changes occur in a short period.
         */
        private val serializationTrigger = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /**
         * Subscriptions map for each entity in the repository are needed to unsubscribe
         * from their changes once they are removed.
         */
        private val subscriptionsMap: MutableMap<K, LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>> = ConcurrentHashMap()

        private val dirty = AtomicBoolean(false)

        @Volatile
        private var closed = false

        init {
            require(jsonFile.exists().and(jsonFile.canWrite()).and(jsonFile.extension == "json")) {
                "Provided jsonFile does not exist, is not writable or is not a json file"
            }

            flowScope.launch {
                for (event in serializationEventChannel) {
                    try {
                        serializationTrigger.emit(event)
                    } catch (exception: Exception) {
                        log.error(exception) { "Unexpected error during serialization" }
                    }
                }
            }

            disableEvents(CREATE, UPDATE)

            // Load entities from the JSON file on initialization and create subscriptions
            decodeFromJson()?.let { loadedEntities ->
                log.info { "${loadedEntities.size} objects deserialized from file $jsonFile" }

                // Bypass this class's override to avoid marking dirty during disk load.
                // super.addOrReplaceAll bypasses VolatileRepository's index hooks, so indexes are rebuilt manually.
                super.addOrReplaceAll(loadedEntities.values.toSet())

                loadedEntities.values.forEach { entity ->
                    discoverIndexes(entity)
                    indexEntity(entity)
                    discoverRefs(entity)
                    bindEntityRefs(entity)
                    wireRefBubbleUp(entity)
                }

                flowScope.launch {
                    forEach { entity -> subscribeEntity(entity) }
                }
            }

            activateEvents(CREATE, UPDATE)
        }

        private fun subscribeEntity(entity: R) {
            val subscription = entity.subscribe { markDirtyAndTrigger() }
            subscriptionsMap[entity.id] = subscription
        }

        private fun markDirtyAndTrigger() {
            check(!closed) { "JsonFileRepository is closed" }
            dirty.set(true)
            serializationEventChannel.trySend(Unit)
        }

        @OptIn(FlowPreview::class)
        private val serializationJob =
            ioScope.launch {
                serializationTrigger
                    .debounce(serializationDelay)
                    .collect {
                        performSerialization()
                    }
            }

        private suspend fun performSerialization() {
            if (!dirty.compareAndSet(true, false)) {
                log.debug { "Skipping serialization, no changes since last write" }
                return
            }
            try {
                val jsonString = json.encodeToString(mapSerializer, entitiesById)

                withContext(ioScope.coroutineContext) {
                    jsonFile.writeText(jsonString)
                }
                log.debug { "File updated: $jsonFile" }
            } catch (exception: Exception) {
                dirty.set(true)
                log.error(exception) { "Error serializing to file $jsonFile" }
            }
        }

        private fun decodeFromJson(): Map<K, R>? {
            val content = jsonFile.readText()
            if (content.isEmpty()) return null
            return try {
                json.decodeFromString(mapSerializer, content)
            } catch (exception: Exception) {
                throw LirpDeserializationException(
                    "Failed to deserialize entities from file: ${jsonFile.absolutePath}",
                    exception
                )
            }
        }

        /**
         * Closes this repository and releases all resources.
         *
         * Non-blocking and fire-and-forget: closes the serialization channel, launches a final
         * [performSerialization] in the I/O scope without waiting for it to complete, then cancels
         * the debounced serialization job and closes the underlying event publisher.
         *
         * Any pending dirty state is flushed asynchronously — callers must not rely on the
         * write having completed by the time this method returns.
         *
         * Idempotent: subsequent calls are safe no-ops.
         *
         * After closing, all mutating operations ([add], [addOrReplace], [addOrReplaceAll],
         * [remove], [removeAll], [clear]) throw [IllegalStateException].
         */
        override fun close() {
            if (closed) return
            closed = true
            serializationEventChannel.close()
            // Fire-and-forget: flush any pending dirty state without blocking the caller
            ioScope.launch {
                performSerialization()
            }
            serializationJob.cancel()
            super.close()
        }

        override fun add(entity: R): Boolean {
            check(!closed) { "JsonFileRepository is closed" }
            return super.add(entity).also { added ->
                if (added) {
                    markDirtyAndTrigger()
                    subscribeEntity(entity)
                }
            }
        }

        override fun addOrReplace(entity: R): Boolean {
            check(!closed) { "JsonFileRepository is closed" }
            return super.addOrReplace(entity).also { added ->
                if (added) {
                    markDirtyAndTrigger()
                    subscribeEntity(entity)
                }
            }
        }

        override fun addOrReplaceAll(entities: Set<R>): Boolean {
            check(!closed) { "JsonFileRepository is closed" }
            return super.addOrReplaceAll(entities).also { added ->
                if (added) {
                    markDirtyAndTrigger()
                    entities.forEach { entity -> subscribeEntity(entity) }
                }
            }
        }

        override fun remove(entity: R): Boolean {
            check(!closed) { "JsonFileRepository is closed" }
            return super.remove(entity).also { removed ->
                if (removed) {
                    markDirtyAndTrigger()
                    val subscription =
                        subscriptionsMap.remove(entity.id)
                            ?: error("Repository should contain a subscription for $entity")
                    subscription.cancel()
                }
            }
        }

        override fun removeAll(entities: Collection<R>): Boolean {
            check(!closed) { "JsonFileRepository is closed" }
            return super.removeAll(entities).also { removed ->
                if (removed) {
                    markDirtyAndTrigger()
                    entities.forEach {
                        val subscription =
                            subscriptionsMap.remove(it.id)
                                ?: error("Repository should contain a subscription for $it")
                        subscription.cancel()
                    }
                }
            }
        }

        override fun clear() {
            check(!closed) { "JsonFileRepository is closed" }
            super.clear()
            markDirtyAndTrigger()
            subscriptionsMap.forEach { (_, subscription) ->
                subscription.cancel()
            }
            subscriptionsMap.clear()
        }

        override fun hashCode() = Objects.hashCode(jsonFile)

        override fun equals(other: Any?) =
            if (other is JsonFileRepository<*, *>) {
                jsonFile == other.jsonFile
            } else {
                false
            }
    }
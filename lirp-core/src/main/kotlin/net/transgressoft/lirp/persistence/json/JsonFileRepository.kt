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
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.LirpDeserializationException
import net.transgressoft.lirp.persistence.PendingOp
import net.transgressoft.lirp.persistence.PersistentRepositoryBase
import mu.KotlinLogging
import java.io.File
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * Base class for repositories that store entities in a JSON file.
 *
 * Extends [PersistentRepositoryBase] with JSON file persistence. All CRUD operations and entity
 * mutations enqueue [PendingOp] entries in the base class; the debounce pipeline calls
 * [writePending] which serializes the full in-memory state to the JSON file.
 *
 * Because JSON serialization always rewrites the complete file, the ops list passed to
 * [writePending] is intentionally ignored — the current in-memory state is the source of truth.
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
        loadOnInit: Boolean = true
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
            loadOnInit: Boolean = true
        ) : this(LirpContext.default, file, mapSerializer, repositorySerializersModule, serializationDelay, loadOnInit)

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
            dirty.set(false)
            return entities
        }

        /**
         * Serializes the full in-memory entity state to [jsonFile].
         *
         * The [ops] list is intentionally ignored: JSON persistence always rewrites the complete
         * file from the current in-memory state rather than applying incremental changes. This
         * simplifies the implementation and avoids partial-write correctness concerns.
         *
         * Called by [PersistentRepositoryBase.flush] after collapsing the pending-ops queue.
         */
        override fun writePending(ops: List<PendingOp<K, R>>) {
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
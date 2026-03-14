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

import net.transgressoft.lirp.persistence.ReactivePrimitive
import net.transgressoft.lirp.persistence.json.primitives.ReactiveBoolean
import net.transgressoft.lirp.persistence.json.primitives.ReactiveInt
import net.transgressoft.lirp.persistence.json.primitives.ReactiveString
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A specialized JSON repository for storing primitive values (strings, integers, booleans) in a reactive way.
 *
 * This repository provides a convenient way to store configuration values or settings
 * that need to be persisted and may change during application execution. It uses reactive
 * primitives to enable subscription to value changes.
 *
 * The class handles serialization of primitive values to a simple flat JSON structure,
 * where keys are the IDs and values are the primitive values themselves. Changes to the values
 * are automatically persisted with debouncing to prevent excessive file I/O operations when
 * multiple values change in rapid succession.
 *
 * Example usage:
 * ```
 * val config = FlexibleJsonFileRepository(File("config.json"))
 *
 * // Get or create reactive primitives (uses existing values from file if present)
 * val serverName = config.getReactiveString("server.name", "Main Server")
 * val port = config.getReactiveInt("server.port", 8080)
 * val debugMode = config.getReactiveBoolean("debug.mode", false)
 *
 * // Later, change a value (automatically persisted)
 * port.value = 9090
 * ```
 *
 * @param jsonFile The JSON file to store primitive values in
 * @param serializationDelay The delay between the last change and JSON file write. Defaults to 300 milliseconds.
 * @param ioScope The coroutine scope used for file I/O operations. By default, uses a scope with
 *        limitedParallelism(1) on the IO dispatcher to ensure thread-safe file access. For testing,
 *        provide a scope created with a test dispatcher to control virtual time execution.
 */
@Suppress("UNCHECKED_CAST")
open class FlexibleJsonFileRepository
    @JvmOverloads
    constructor(
        jsonFile: File,
        serializationDelay: Duration = 300.milliseconds
    ) :
    JsonFileRepository<String, ReactivePrimitive<Any>>(
            jsonFile,
            ReactiveValueMapSerializer,
            serializationDelay = serializationDelay
        ) {

        /**
         * Gets an existing reactive string or creates a new one if it doesn't exist.
         *
         * This method implements createOrGet semantics: if a reactive string with the given ID
         * already exists in the repository (e.g., loaded from the JSON file), it returns the
         * existing instance with its current value. Otherwise, it creates a new reactive string
         * with the provided default value and adds it to the repository.
         *
         * @param id The unique identifier for the reactive string
         * @param value The default value to use if creating a new reactive string.
         *
         * @return The existing [ReactiveString] if found, or a newly created one with the default value
         */
        @JvmOverloads
        fun getReactiveString(id: String, value: String? = null): ReactiveString {
            val existing = findById(id)
            return if (existing.isPresent) {
                existing.get() as ReactiveString
            } else {
                ReactiveString(id, value).also { add(it as ReactivePrimitive<Any>) }
            }
        }

        /**
         * Gets an existing reactive boolean or creates a new one if it doesn't exist.
         *
         * This method implements createOrGet semantics: if a reactive boolean with the given ID
         * already exists in the repository (e.g., loaded from the JSON file), it returns the
         * existing instance with its current value. Otherwise, it creates a new reactive boolean
         * with the provided default value and adds it to the repository.
         *
         * @param id The unique identifier for the reactive boolean
         * @param value The default value to use if creating a new reactive boolean.
         *
         * @return The existing [ReactiveBoolean] if found, or a newly created one with the default value
         */
        @JvmOverloads
        fun getReactiveBoolean(id: String, value: Boolean? = null): ReactiveBoolean {
            val existing = findById(id)
            return if (existing.isPresent) {
                existing.get() as ReactiveBoolean
            } else {
                ReactiveBoolean(id, value).also { add(it as ReactivePrimitive<Any>) }
            }
        }

        /**
         * Gets an existing reactive integer or creates a new one if it doesn't exist.
         *
         * This method implements createOrGet semantics: if a reactive integer with the given ID
         * already exists in the repository (e.g., loaded from the JSON file), it returns the
         * existing instance with its current value. Otherwise, it creates a new reactive integer
         * with the provided default value and adds it to the repository.
         *
         * @param id The unique identifier for the reactive integer
         * @param value The default value to use if creating a new reactive integer.
         *
         * @return The existing [ReactiveInt] if found, or a newly created one with the default value
         */
        @JvmOverloads
        fun getReactiveInt(id: String, value: Int? = null): ReactiveInt {
            val existing = findById(id)
            return if (existing.isPresent) {
                existing.get() as ReactiveInt
            } else {
                ReactiveInt(id, value).also { add(it as ReactivePrimitive<Any>) }
            }
        }
    }

object ReactiveValueMapSerializer : KSerializer<Map<String, ReactivePrimitive<Any>>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Map<String, ReactivePrimitive<Any>>")

    override fun serialize(encoder: Encoder, value: Map<String, ReactivePrimitive<Any>>) {
        val jsonEncoder = encoder as? JsonEncoder ?: throw SerializationException("This serializer can be used only with Json format")

        val jsonObject =
            buildJsonObject {
                value.forEach { (key, reactiveValue) ->
                    when (val primitiveValue = reactiveValue.value) {
                        is String -> put(key, primitiveValue)
                        is Boolean -> put(key, primitiveValue)
                        is Int -> put(key, primitiveValue)
                        else -> throw SerializationException("Unsupported ReactiveValue type: ${reactiveValue::class}")
                    }
                }
            }

        jsonEncoder.encodeJsonElement(jsonObject)
    }

    @Suppress("UNCHECKED_CAST")
    override fun deserialize(decoder: Decoder): Map<String, ReactivePrimitive<Any>> {
        val jsonDecoder = decoder as? JsonDecoder ?: throw SerializationException("This serializer can be used only with Json format")
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        return jsonObject.mapValues { (key, element) ->
            when {
                element is JsonPrimitive && element.isString -> ReactiveString(key, element.content) as ReactivePrimitive<Any>
                element is JsonPrimitive && element.booleanOrNull != null -> ReactiveBoolean(key, element.boolean) as ReactivePrimitive<Any>
                element is JsonPrimitive && element.intOrNull != null -> ReactiveInt(key, element.int) as ReactivePrimitive<Any>
                else -> throw SerializationException("Unsupported JSON element type for key: $key")
            }
        }
    }
}
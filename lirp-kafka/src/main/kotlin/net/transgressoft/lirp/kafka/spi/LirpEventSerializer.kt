/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.lirp.kafka.spi

/**
 * Symmetric serialization SPI for converting [LirpEventEnvelope] instances to and from wire format.
 *
 * The relay calls [serialize] before publishing each outbox row to Kafka, and consumer-side
 * infrastructure calls [deserialize] to reconstruct the envelope from the received record.
 * Implementing this interface allows consumers to substitute alternative wire encodings
 * (Avro, Protobuf, etc.) without modifying any LIRP class.
 *
 * The default implementation is [CloudEventsBinarySerializer], which encodes the envelope as
 * CloudEvents v1.0 binary content mode — `ce_*` Kafka record headers carrying CloudEvents
 * attributes and the neutral JSON field snapshot as the raw record value.
 *
 * Because this interface declares two abstract methods, it is a regular interface and not a
 * `fun interface`. Callers that need a lambda-friendly single-method abstraction should use
 * [TopicResolver] instead.
 */
interface LirpEventSerializer {

    /**
     * Serializes [envelope] to the wire representation.
     *
     * @param envelope The event envelope to serialize.
     * @return A [SerializedEvent] containing the Kafka record value bytes and the associated
     *   header map (header name to UTF-8 encoded bytes).
     */
    fun serialize(envelope: LirpEventEnvelope): SerializedEvent

    /**
     * Deserializes a Kafka record back to a [LirpEventEnvelope].
     *
     * Implementations must use `error(...)` for any missing required header rather than returning
     * a partial envelope — missing headers indicate a serialization bug and must surface
     * immediately rather than silently producing corrupt data.
     *
     * @param value Raw Kafka record value bytes.
     * @param headers Raw header map from the Kafka record (header name to raw bytes).
     * @return The reconstructed [LirpEventEnvelope].
     */
    fun deserialize(value: ByteArray, headers: Map<String, ByteArray>): LirpEventEnvelope
}

/**
 * Wire representation of a serialized [LirpEventEnvelope].
 *
 * @property value Raw Kafka record value bytes (typically a JSON payload).
 * @property headers Kafka record headers as a map from header name to raw bytes.
 *   All header values are expected to be UTF-8 encoded strings.
 */
data class SerializedEvent(val value: ByteArray, val headers: Map<String, ByteArray>) {
    override fun equals(other: Any?): Boolean =
        other is SerializedEvent && value.contentEquals(other.value) && headers == other.headers

    override fun hashCode(): Int = 31 * value.contentHashCode() + headers.hashCode()
}
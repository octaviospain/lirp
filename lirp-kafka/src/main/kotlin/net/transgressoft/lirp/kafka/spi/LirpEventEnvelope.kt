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

import net.transgressoft.lirp.kafka.outbox.OutboxEvent
import kotlinx.serialization.Serializable

/**
 * Public wire envelope that the [LirpEventSerializer] SPI serializes to and deserializes from.
 *
 * Each instance represents a single domain event as captured by the transactional outbox.
 * The [payload] field carries a serializer-neutral JSON field snapshot produced by the SQL
 * persistence layer at capture time. Consumer serializer plugins (Avro, Protobuf, etc.) receive
 * a structured [LirpEventEnvelope] rather than an opaque byte array, so they can map from a
 * well-defined public contract.
 *
 * The [createdAt] field is stored as an ISO-8601 UTC string rather than a timestamp type so
 * the envelope remains directly serializable with kotlinx-serialization without a custom
 * serializer.
 *
 * @property eventId Unique identifier of the outbox row (UUID string). Used as the CloudEvents
 *   `ce_id` header and as the idempotency key for consumer-side deduplication. Must be non-blank.
 * @property aggregateType Logical aggregate type name (e.g. table name or class name) used to
 *   route events to the appropriate Kafka topic via [TopicResolver] and to derive the CloudEvents
 *   `ce_source` and `ce_type` headers.
 * @property aggregateId String identifier of the specific aggregate instance; used as the Kafka
 *   record key for per-aggregate ordering and as the CloudEvents `ce_subject` header. Must be
 *   non-blank to preserve per-aggregate delivery order and prevent empty record keys.
 * @property eventTypeCode Numeric event-type code mapping directly to
 *   [net.transgressoft.lirp.event.EventType.code].
 * @property payload Serializer-neutral JSON field snapshot of the entity at capture time.
 * @property createdAt ISO-8601 UTC timestamp of when the outbox row was captured. Used as the
 *   CloudEvents `ce_time` header.
 */
@Serializable
data class LirpEventEnvelope(
    val eventId: String,
    val aggregateType: String,
    val aggregateId: String,
    val eventTypeCode: Int,
    val payload: String,
    val createdAt: String
) {
    init {
        require(eventId.isNotBlank()) { "eventId must not be blank; a non-blank idempotency key is required" }
        require(aggregateId.isNotBlank()) { "aggregateId must not be blank; a non-blank aggregate identifier is required for per-aggregate ordering" }
    }

    companion object {
        /**
         * Builds a [LirpEventEnvelope] from an internal outbox row.
         *
         * @param row The outbox row to convert.
         */
        internal fun from(row: OutboxEvent): LirpEventEnvelope =
            LirpEventEnvelope(
                eventId = row.id.toString(),
                aggregateType = row.aggregateType,
                aggregateId = row.aggregateId,
                eventTypeCode = row.eventTypeCode,
                payload = row.payload,
                createdAt = row.createdAt.toString()
            )
    }
}
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
 * Default [LirpEventSerializer] that encodes events using the CloudEvents v1.0 Kafka binary
 * content mode specification.
 *
 * In binary content mode, CloudEvents attributes are carried as Kafka record headers with the
 * `ce_` prefix, and the event payload is transmitted as the raw record value. This allows
 * non-LIRP consumers to read the payload as plain JSON without a CloudEvents SDK, while
 * retaining full CloudEvents envelope metadata in the headers.
 *
 * Headers produced on [serialize]:
 * - `ce_specversion`: CloudEvents specification version, always `"1.0"`.
 * - `ce_id`: The outbox row UUID, used as the idempotency key for consumer-side deduplication.
 * - `ce_source`: URI-reference identifying the event origin as `"lirp/{aggregateType}"`.
 * - `ce_type`: Event type encoded as `"{aggregateType}.{eventTypeCode}"`.
 * - `ce_subject`: The aggregate instance identifier (`aggregateId`).
 * - `ce_time`: ISO-8601 UTC capture timestamp (`createdAt`).
 * - `content-type`: Always `"application/json"` — the record value is a JSON string.
 *
 * All header values are encoded as UTF-8 bytes. [deserialize] fails fast on any record that does
 * not honour this contract: a missing required header, a blank `ce_id` or `ce_subject` (an empty
 * `ByteArray` is non-null but decodes to an empty string — still rejected), an unsupported
 * `ce_specversion` or `content-type`, a `ce_time` that is not a valid ISO-8601 UTC timestamp,
 * a `ce_type` prefix that does not match the `ce_source` aggregate type, or a `ce_source` that
 * is not in the `lirp/{aggregateType}` form all raise an exception rather than yield a bogus
 * envelope (bug detector — do not downgrade to logging).
 *
 * No external CloudEvents SDK is required. Hand-rolled with kotlinx-serialization only.
 */
class CloudEventsBinarySerializer : LirpEventSerializer {

    override fun serialize(envelope: LirpEventEnvelope): SerializedEvent {
        val headers = mutableMapOf<String, ByteArray>()
        headers["ce_specversion"] = "1.0".toByteArray(Charsets.UTF_8)
        headers["ce_id"] = envelope.eventId.toByteArray(Charsets.UTF_8)
        headers["ce_source"] = "lirp/${envelope.aggregateType}".toByteArray(Charsets.UTF_8)
        headers["ce_type"] = "${envelope.aggregateType}.${envelope.eventTypeCode}".toByteArray(Charsets.UTF_8)
        headers["ce_subject"] = envelope.aggregateId.toByteArray(Charsets.UTF_8)
        headers["ce_time"] = envelope.createdAt.toByteArray(Charsets.UTF_8)
        headers["content-type"] = "application/json".toByteArray(Charsets.UTF_8)
        val value = envelope.payload.toByteArray(Charsets.UTF_8)
        return SerializedEvent(value, headers)
    }

    override fun deserialize(value: ByteArray, headers: Map<String, ByteArray>): LirpEventEnvelope {
        val payload = value.toString(Charsets.UTF_8)
        val specVersion = headers["ce_specversion"]?.toString(Charsets.UTF_8) ?: error("missing required ce_specversion header")
        require(specVersion == "1.0") { "unsupported ce_specversion '$specVersion'" }
        val eventId =
            headers["ce_id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                ?: error("ce_id header is missing or blank; a non-blank idempotency key is required")
        val source = headers["ce_source"]?.toString(Charsets.UTF_8) ?: error("missing required ce_source header")
        require(source.startsWith("lirp/")) { "ce_source '$source' does not use the expected lirp/{aggregateType} format" }
        val aggregateType = source.removePrefix("lirp/")
        require(aggregateType.isNotBlank()) { "ce_source '$source' has a blank aggregate type; expected lirp/{aggregateType}" }
        val ceType = headers["ce_type"]?.toString(Charsets.UTF_8) ?: error("missing required ce_type header")
        val eventTypeCode =
            ceType.substringAfterLast('.').toIntOrNull()
                ?: error("ce_type '$ceType' does not end in a numeric event-type code")
        val ceTypePrefix = ceType.substringBeforeLast('.')
        require(ceTypePrefix == aggregateType) {
            "ce_type prefix '$ceTypePrefix' does not match ce_source aggregate type '$aggregateType'; headers may be corrupted"
        }
        val aggregateId =
            headers["ce_subject"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                ?: error("ce_subject header is missing or blank; a non-blank aggregate identifier is required")
        val createdAt = headers["ce_time"]?.toString(Charsets.UTF_8) ?: error("missing required ce_time header")
        try {
            kotlin.time.Instant.parse(createdAt)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "ce_time '$createdAt' is not a valid ISO-8601 instant; expected an RFC 3339 timestamp, e.g. 2026-07-01T10:30:00Z",
                e
            )
        }
        val contentType = headers["content-type"]?.toString(Charsets.UTF_8) ?: error("missing required content-type header")
        require(contentType == "application/json") { "unsupported content-type '$contentType'" }
        return LirpEventEnvelope(eventId, aggregateType, aggregateId, eventTypeCode, payload, createdAt)
    }
}
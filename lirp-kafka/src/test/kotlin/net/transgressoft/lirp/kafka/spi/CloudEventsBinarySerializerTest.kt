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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [CloudEventsBinarySerializer] header and record-value production.
 */
@DisplayName("CloudEventsBinarySerializerTest")
internal class CloudEventsBinarySerializerTest : StringSpec() {

    val envelope =
        LirpEventEnvelope(
            eventId = "550e8400-e29b-41d4-a716-446655440000",
            aggregateType = "audio_items",
            aggregateId = "42",
            eventTypeCode = 100,
            payload = """{"title":"Bohemian Rhapsody"}""",
            createdAt = "2026-07-01T10:30:00Z"
        )

    val serializer = CloudEventsBinarySerializer()
    val serialized = serializer.serialize(envelope)

    init {
        "CloudEventsBinarySerializerTest ce_specversion header decodes to 1.0" {
            serialized.headers["ce_specversion"]!!.toString(Charsets.UTF_8) shouldBe "1.0"
        }

        "CloudEventsBinarySerializerTest ce_id header equals the envelope eventId" {
            serialized.headers["ce_id"]!!.toString(Charsets.UTF_8) shouldBe envelope.eventId
        }

        "CloudEventsBinarySerializerTest ce_source header encodes the aggregateType" {
            serialized.headers["ce_source"]!!.toString(Charsets.UTF_8) shouldBe "lirp/${envelope.aggregateType}"
        }

        "CloudEventsBinarySerializerTest ce_type header encodes aggregateType and eventTypeCode" {
            serialized.headers["ce_type"]!!.toString(Charsets.UTF_8) shouldBe "${envelope.aggregateType}.${envelope.eventTypeCode}"
        }

        "CloudEventsBinarySerializerTest ce_subject header equals the envelope aggregateId" {
            serialized.headers["ce_subject"]!!.toString(Charsets.UTF_8) shouldBe envelope.aggregateId
        }

        "CloudEventsBinarySerializerTest ce_time header equals the envelope createdAt" {
            serialized.headers["ce_time"]!!.toString(Charsets.UTF_8) shouldBe envelope.createdAt
        }

        "CloudEventsBinarySerializerTest content-type header is application/json" {
            serialized.headers["content-type"]!!.toString(Charsets.UTF_8) shouldBe "application/json"
        }

        "CloudEventsBinarySerializerTest record value UTF-8-decodes to the envelope payload" {
            serialized.value.toString(Charsets.UTF_8) shouldBe envelope.payload
        }

        "CloudEventsBinarySerializerTest deserialize round-trips a serialized envelope without loss" {
            serializer.deserialize(serialized.value, serialized.headers) shouldBe envelope
        }

        "CloudEventsBinarySerializerTest deserialize rejects a missing ce_specversion header" {
            shouldThrow<IllegalStateException> {
                serializer.deserialize(serialized.value, serialized.headers - "ce_specversion")
            }
        }

        "CloudEventsBinarySerializerTest deserialize rejects an unsupported ce_specversion" {
            val headers = serialized.headers + ("ce_specversion" to "0.3".toByteArray(Charsets.UTF_8))
            shouldThrow<IllegalArgumentException> { serializer.deserialize(serialized.value, headers) }
        }

        "CloudEventsBinarySerializerTest deserialize rejects a ce_source not in lirp format" {
            val headers = serialized.headers + ("ce_source" to "other/audio_items".toByteArray(Charsets.UTF_8))
            shouldThrow<IllegalArgumentException> { serializer.deserialize(serialized.value, headers) }
        }

        "CloudEventsBinarySerializerTest deserialize rejects a missing content-type header" {
            shouldThrow<IllegalStateException> {
                serializer.deserialize(serialized.value, serialized.headers - "content-type")
            }
        }

        "CloudEventsBinarySerializerTest deserialize rejects an unsupported content-type" {
            val headers = serialized.headers + ("content-type" to "application/xml".toByteArray(Charsets.UTF_8))
            shouldThrow<IllegalArgumentException> { serializer.deserialize(serialized.value, headers) }
        }
    }
}
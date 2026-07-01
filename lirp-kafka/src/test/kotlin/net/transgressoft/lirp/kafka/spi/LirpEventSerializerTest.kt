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

import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the [LirpEventSerializer] round-trip contract.
 */
@DisplayName("LirpEventSerializerTest")
internal class LirpEventSerializerTest : StringSpec() {

    val envelope =
        LirpEventEnvelope(
            eventId = "550e8400-e29b-41d4-a716-446655440000",
            aggregateType = "audio_items",
            aggregateId = "42",
            eventTypeCode = 100,
            payload = """{"title":"Bohemian Rhapsody","album":"A Night at the Opera"}""",
            createdAt = "2026-07-01T10:30:00Z"
        )

    init {
        "LirpEventSerializerTest serialize then deserialize round-trips LirpEventEnvelope without data loss" {
            val serializer = CloudEventsBinarySerializer()
            val serialized = serializer.serialize(envelope)
            val restored = serializer.deserialize(serialized.value, serialized.headers)
            restored shouldBe envelope
        }

        "LirpEventSerializerTest a no-op stub LirpEventSerializer can be constructed and used without modifying any LIRP class" {
            // Proves PUBLISH-02: a consumer can supply an alternative LirpEventSerializer
            val fixedEvent = SerializedEvent("test".toByteArray(Charsets.UTF_8), emptyMap())
            val stub =
                object : LirpEventSerializer {
                    override fun serialize(envelope: LirpEventEnvelope): SerializedEvent = fixedEvent

                    override fun deserialize(value: ByteArray, headers: Map<String, ByteArray>): LirpEventEnvelope = envelope
                }
            stub.serialize(envelope) shouldBe fixedEvent
            stub.deserialize(fixedEvent.value, fixedEvent.headers) shouldBe envelope
        }
    }
}
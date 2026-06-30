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

package net.transgressoft.lirp.kafka.outbox

import java.time.Instant
import java.util.UUID

/**
 * Represents a single pending outbox record awaiting relay to Kafka.
 *
 * Instances are created during a committed entity transaction and consumed by the relay
 * process. All scalar fields are immutable after creation. The [payload] field is a
 * [ByteArray]: callers must not mutate the array after passing it to the constructor,
 * and [copy] shares the same array reference — treat the bytes as read-only once the
 * record is created. Equality and hashing are based solely on [id] to avoid issues with
 * [ByteArray] structural equality.
 *
 * Use [OutboxEvent.of] to construct instances with an automatic defensive copy of the
 * payload bytes.
 */
internal data class OutboxEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: String,
    val eventTypeCode: String,
    val payload: ByteArray,
    val createdAt: Instant,
    val sentAt: Instant? = null
) {
    companion object {
        /** Creates an [OutboxEvent] with a defensive copy of [payload]. */
        fun of(
            id: UUID,
            aggregateType: String,
            aggregateId: String,
            eventTypeCode: String,
            payload: ByteArray,
            createdAt: Instant,
            sentAt: Instant? = null
        ) = OutboxEvent(id, aggregateType, aggregateId, eventTypeCode, payload.copyOf(), createdAt, sentAt)
    }

    override fun equals(other: Any?): Boolean =
        other is OutboxEvent && id == other.id

    override fun hashCode(): Int = id.hashCode()
}
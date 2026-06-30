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

package net.transgressoft.lirp.kafka.outbox

import java.util.UUID
import kotlin.time.Instant

/**
 * Represents a single pending outbox record awaiting relay to Kafka.
 *
 * Each instance captures one entity change — create, update, or delete — together with a
 * serializer-neutral JSON field snapshot of the entity's persisted column values at the time the
 * change was committed. The relay process reads these records via [OutboxStore] and encodes
 * [payload] into the wire format before publishing to Kafka.
 *
 * The [payload] is a JSON object whose keys are SQL column names and whose values are the
 * entity's field values at capture time, matching exactly what the SQL persistence layer wrote.
 * Consumers that need field-level encryption or transformation should apply it at the persistence
 * mapping level so that both the SQL row and the outbox payload remain consistent.
 *
 * The [eventTypeCode] maps directly to [net.transgressoft.lirp.event.EventType.code], covering
 * standard CRUD codes (100 = Create, 300 = Update, 400 = Delete), mutation codes
 * (302 = PropertyChanged, 303 = BatchChanged), and any consumer-defined custom codes.
 *
 * Equality and hashing are based solely on [id] to guarantee stable set membership regardless
 * of relay-managed fields such as [retryCount] and [lastError].
 */
internal data class OutboxEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: String,
    val eventTypeCode: Int,
    val payload: String,
    val createdAt: Instant,
    val sentAt: Instant? = null,
    val retryCount: Int = 0,
    val lastError: String? = null
) {
    override fun equals(other: Any?): Boolean =
        other is OutboxEvent && id == other.id

    override fun hashCode(): Int = id.hashCode()
}
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
 * Represents a single dead-letter record that the relay could not deliver to Kafka.
 *
 * Instances are created when the relay moves a row from the outbox to the dead-letter table —
 * either because it exceeded the maximum retry count or because it encountered a non-retriable
 * error. The [id] is preserved from the original outbox row for end-to-end traceability.
 *
 * The [payload] is the same serializer-neutral JSON field snapshot that was captured at commit
 * time. Consumers inspecting the dead-letter table can use it to replay or diagnose the failed
 * delivery without referring back to the entity table.
 *
 * Equality and hashing are based solely on [id], matching the identity semantics of the
 * original [OutboxEvent].
 */
internal data class DeadLetterEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: String,
    val eventTypeCode: Int,
    val payload: String,
    val createdAt: Instant,
    val failedAt: Instant,
    val attemptCount: Int,
    val lastError: String
) {
    override fun equals(other: Any?): Boolean =
        other is DeadLetterEvent && id == other.id

    override fun hashCode(): Int = id.hashCode()
}
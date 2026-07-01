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

import java.util.UUID
import kotlin.time.Instant

/**
 * Abstraction over the outbox persistence layer.
 *
 * Implementations must write outbox rows **inside the same open database transaction** that
 * is flushing the entity mutations — specifically inside the `transaction { }` block in
 * `SqlRepository.commitTransactionBuffer`. Writing in a separate transaction breaks the
 * atomicity guarantee: either both the entity mutation and the outbox row are committed, or
 * neither is.
 *
 * The SQL-backed implementation is the only supported store. JSON-file and volatile
 * repositories cannot participate in the transactional outbox.
 */
internal interface OutboxStore {
    /**
     * Returns up to [limit] outbox events that have not yet been sent to Kafka,
     * ordered by creation time ascending.
     */
    fun findUnsent(limit: Int): List<OutboxEvent>

    /**
     * Marks the event identified by [id] as sent.
     */
    fun markSent(id: UUID)

    /**
     * Returns up to [limit] outbox rows whose [OutboxEvent.sentAt] is null and whose
     * [OutboxEvent.nextRetryAt] is null or is not later than [now], using dialect-appropriate
     * row locking (FOR UPDATE SKIP LOCKED on PostgreSQL/MySQL/MariaDB; plain SELECT on
     * SQLite/H2). Rows are ordered by creation time ascending so older events are delivered
     * first. Must be called inside an active Exposed transaction.
     */
    fun findUnsentForRelay(limit: Int, now: Instant): List<OutboxEvent>

    /**
     * Increments [OutboxEvent.retryCount] and sets [OutboxEvent.nextRetryAt] and
     * [OutboxEvent.lastError] for the row identified by [id]. Used by the relay after a
     * retriable delivery failure to schedule the next attempt according to the backoff policy.
     * Must be called inside an active Exposed transaction.
     */
    fun scheduleRetry(id: UUID, nextRetryAt: Instant, errorMessage: String)

    /**
     * Copies the row identified by [event.id] into the dead-letter table and deletes it from
     * the outbox in the same transaction. Used when the row has exhausted its retries or
     * encountered a non-retriable error. Must be called inside an active Exposed transaction.
     */
    fun moveToDeadLetter(event: OutboxEvent, failedAt: Instant, errorMessage: String)
}
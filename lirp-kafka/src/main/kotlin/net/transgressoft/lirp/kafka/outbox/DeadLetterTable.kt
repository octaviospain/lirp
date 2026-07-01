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

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * Exposed table definition for the `lirp_kafka_dead_letter` table.
 *
 * Rows are moved here from the outbox when the relay determines that an event cannot be
 * delivered — either because it has exceeded the maximum retry count or because it encountered
 * a non-retriable error. The original outbox row is deleted atomically in the same transaction
 * that inserts the dead-letter row.
 *
 * Column layout:
 * - `id` — the original outbox UUID; preserved verbatim for traceability and idempotency.
 * - `aggregate_type` — entity class name or table name identifying the aggregate type.
 * - `aggregate_id` — string representation of the entity's primary key.
 * - `event_type_code` — integer code identifying the event family and type.
 * - `payload` — the original neutral JSON field snapshot captured at commit time.
 * - `created_at` — timestamp when the original outbox row was inserted.
 * - `failed_at` — timestamp when the relay moved the row to this table.
 * - `attempt_count` — total number of relay delivery attempts including the final one.
 * - `last_error` — error message from the final failed delivery attempt.
 *
 * All failure-metadata columns (`failed_at`, `attempt_count`, `last_error`) are non-nullable
 * because rows are only inserted here after a terminal failure has been recorded.
 */
internal object DeadLetterTable : Table("lirp_kafka_dead_letter") {

    val id = uuid("id")
    val aggregateType = varchar("aggregate_type", 255)
    val aggregateId = varchar("aggregate_id", 255)
    val eventTypeCode = integer("event_type_code")
    val payload = text("payload")
    val createdAt = timestamp("created_at")
    val failedAt = timestamp("failed_at")
    val attemptCount = integer("attempt_count")
    val lastError = text("last_error")

    override val primaryKey = PrimaryKey(id)
}
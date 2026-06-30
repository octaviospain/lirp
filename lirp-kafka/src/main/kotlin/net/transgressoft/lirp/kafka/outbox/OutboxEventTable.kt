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
 * Exposed table definition for the `lirp_kafka_outbox` table.
 *
 * Each row represents a single pending outbox record capturing one entity change — a create,
 * update, or delete — together with a serializer-neutral JSON field snapshot of the persisted
 * column values at the time of capture. Rows are written atomically in the same JDBC commit as
 * the corresponding entity rows; the relay process reads them via [OutboxStore] and publishes
 * them to Kafka.
 *
 * Column layout:
 * - `id` — UUID idempotency key; primary key.
 * - `aggregate_type` — entity class name or table name identifying the aggregate type.
 * - `aggregate_id` — string representation of the entity's primary key.
 * - `event_type_code` — integer code identifying the event family and type (e.g. 100 = Create,
 *   300 = Update, 400 = Delete, 302 = PropertyChanged).
 * - `payload` — neutral JSON field snapshot of the entity's persisted column values at capture time.
 * - `created_at` — timestamp when the outbox row was inserted.
 * - `sent_at` — timestamp set by the relay when the row is successfully published; `null` until then.
 * - `retry_count` — number of relay attempts; defaults to 0 and is managed exclusively by the relay.
 * - `last_error` — last relay error message; `null` until a relay attempt fails; managed by the relay.
 *
 * `retry_count` and `last_error` are created by this DDL but written only by the relay process.
 * The capture path leaves them at their defaults.
 *
 * **Varchar(255) limit:** `aggregate_type` and `aggregate_id` are bounded to 255 characters.
 * A violation surfaces as a database error inside the enclosing transaction, rolling back both
 * entity rows and outbox rows atomically — no partial state is written.
 */
internal object OutboxEventTable : Table("lirp_kafka_outbox") {

    val id = uuid("id")
    val aggregateType = varchar("aggregate_type", 255)
    val aggregateId = varchar("aggregate_id", 255)
    val eventTypeCode = integer("event_type_code")
    val payload = text("payload")
    val createdAt = timestamp("created_at")
    val sentAt = timestamp("sent_at").nullable()
    val retryCount = integer("retry_count").default(0)
    val lastError = text("last_error").nullable()

    override val primaryKey = PrimaryKey(id)
}
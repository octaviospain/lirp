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
import org.jetbrains.exposed.v1.core.isNull
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
 * - `next_retry_at` — relay-owned retry-schedule timestamp; `null` means the row is eligible
 *   for the next poll immediately. Set by the relay after each failed attempt using an exponential
 *   backoff; cleared implicitly when the row is successfully published or moved to the dead-letter
 *   table.
 *
 * `retry_count`, `last_error`, and `next_retry_at` are created by this DDL but written only by
 * the relay process. The capture path leaves them at their defaults.
 *
 * **Varchar(255) limit:** `aggregate_type` and `aggregate_id` are bounded to 255 characters.
 * A violation surfaces as a database error inside the enclosing transaction, rolling back both
 * entity rows and outbox rows atomically — no partial state is written.
 *
 * **Relay poll index:** `idx_lirp_kafka_outbox_relay` is a composite index over
 * `(sent_at, next_retry_at, created_at)`. On PostgreSQL and SQLite it is created as a partial
 * index with `WHERE sent_at IS NULL`, covering only unsent rows. On MySQL and MariaDB the
 * `filterCondition` predicate is not supported and is silently dropped by Exposed, creating a
 * plain composite index instead; the relay's WHERE clause still applies the same filter at
 * query time.
 *
 * **Single-relay constraint for SQLite and H2:** SQLite and H2 do not support
 * `FOR UPDATE SKIP LOCKED`, so the relay issues a plain `SELECT … LIMIT` without row-level
 * locking. Running more than one relay instance against the same SQLite or H2 database will
 * result in duplicate Kafka records. These dialects are intended for single-instance
 * deployments and local testing only; use PostgreSQL or MySQL/MariaDB for production
 * environments where multiple relay instances may run concurrently.
 *
 * **MySQL/MariaDB `FOR UPDATE SKIP LOCKED`:** MySQL 8.0+ and MariaDB 10.6+ support
 * `FOR UPDATE SKIP LOCKED` via [SqlOutboxStore.findUnsentForRelay], so concurrent relay
 * instances are safe on those dialects even though the index is a plain composite rather
 * than a partial index.
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
    val nextRetryAt = timestamp("next_retry_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(
            customIndexName = "idx_lirp_kafka_outbox_relay",
            isUnique = false,
            columns = arrayOf(sentAt, nextRetryAt, createdAt),
            filterCondition = { sentAt.isNull() }
        )
    }
}
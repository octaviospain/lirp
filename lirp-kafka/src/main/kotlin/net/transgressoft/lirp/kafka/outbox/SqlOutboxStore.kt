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

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Exposed-backed implementation of [OutboxStore].
 *
 * All relay-side methods ([findUnsentForRelay], [scheduleRetry], [moveToDeadLetter]) require an
 * active Exposed transaction opened by the caller — typically [OutboxRelay.pollAndRelay]. Holding
 * the transaction open across the Kafka publish call means a crash between publish and
 * [markSent] leaves the row unsent; the rolled-back transaction is the redelivery guarantee.
 *
 * **Dialect-aware locking:** [findUnsentForRelay] applies `FOR UPDATE SKIP LOCKED` on
 * PostgreSQL and supported MySQL/MariaDB versions (MySQL 8.0+, MariaDB 10.6+) so concurrent relay
 * instances skip rows already claimed by a peer. On older MySQL/MariaDB versions (MySQL < 8.0,
 * MariaDB < 10.6), plain `FOR UPDATE` is used to preserve concurrency safety without requiring
 * the unsupported `SKIP LOCKED` syntax. On H2 and SQLite (test and single-instance deployments)
 * the lock clause is omitted; for those dialects a single-relay deployment is the only
 * supported configuration.
 *
 * **Dead-letter idempotency:** [moveToDeadLetter] uses `INSERT IGNORE` (or equivalent) so that
 * a replayed dead-letter insert for the same outbox id silently skips the duplicate rather than
 * throwing a unique-constraint violation. The outbox row is still deleted regardless.
 */
internal class SqlOutboxStore(private val db: Database) : OutboxStore {

    override fun findUnsent(limit: Int): List<OutboxEvent> =
        OutboxEventTable.selectAll()
            .where { OutboxEventTable.sentAt.isNull() }
            .orderBy(OutboxEventTable.createdAt to SortOrder.ASC)
            .limit(limit)
            .map(::toOutboxEvent)

    override fun markSent(id: UUID) {
        OutboxEventTable.update({ OutboxEventTable.id eq id.toKotlinUuid() }) {
            it[sentAt] = Clock.System.now()
        }
    }

    /**
     * Polls up to [limit] outbox rows eligible for delivery: [OutboxEvent.sentAt] is null and
     * [OutboxEvent.nextRetryAt] is null or not later than [now]. Rows are ordered by creation
     * time ascending so older events are delivered first.
     *
     * On PostgreSQL and MySQL/MariaDB a `FOR UPDATE SKIP LOCKED` clause prevents concurrent relay
     * instances from claiming the same row. The lock is held for the duration of the enclosing
     * transaction — that same transaction will either commit [markSent] or roll back, leaving the
     * row available for the next poll cycle.
     */
    override fun findUnsentForRelay(limit: Int, now: Instant): List<OutboxEvent> {
        val lockOption: ForUpdateOption? =
            when (currentDialect) {
                is PostgreSQLDialect ->
                    ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED)
                is MysqlDialect ->
                    // MysqlDialect is the parent of MariaDBDialect — this branch covers both.
                    ForUpdateOption.MySQL.ForUpdate(ForUpdateOption.MySQL.MODE.SKIP_LOCKED)
                else -> null // H2, SQLite — plain SELECT; single-relay constraint is documented
            }

        val query =
            OutboxEventTable.selectAll()
                .where {
                    OutboxEventTable.sentAt.isNull() and
                        (
                            OutboxEventTable.nextRetryAt.isNull() or
                                (OutboxEventTable.nextRetryAt lessEq now)
                        )
                }
                .orderBy(OutboxEventTable.createdAt to SortOrder.ASC)
                .limit(limit)

        lockOption?.let { query.forUpdate(it) }

        return query.map(::toOutboxEvent)
    }

    override fun scheduleRetry(id: UUID, nextRetryAt: Instant, errorMessage: String) {
        OutboxEventTable.update({ OutboxEventTable.id eq id.toKotlinUuid() }) {
            it[retryCount] = OutboxEventTable.retryCount + 1
            it[this.nextRetryAt] = nextRetryAt
            it[lastError] = errorMessage
        }
    }

    override fun insert(event: OutboxEvent) {
        OutboxEventTable.insert {
            it[OutboxEventTable.id] = event.id.toKotlinUuid()
            it[OutboxEventTable.aggregateType] = event.aggregateType
            it[OutboxEventTable.aggregateId] = event.aggregateId
            it[OutboxEventTable.eventTypeCode] = event.eventTypeCode
            it[OutboxEventTable.payload] = event.payload
            it[OutboxEventTable.createdAt] = event.createdAt
        }
    }

    override fun moveToDeadLetter(event: OutboxEvent, failedAt: Instant, errorMessage: String) {
        // Use insertIgnore so a duplicate PK (same outbox id already dead-lettered by a concurrent
        // relay or replay tool) silently skips the re-insert rather than throwing a constraint
        // violation that would leave the outbox row stuck in the queue forever.
        DeadLetterTable.insertIgnore {
            it[id] = event.id.toKotlinUuid()
            it[aggregateType] = event.aggregateType
            it[aggregateId] = event.aggregateId
            it[eventTypeCode] = event.eventTypeCode
            it[payload] = event.payload
            it[createdAt] = event.createdAt
            it[DeadLetterTable.failedAt] = failedAt
            it[attemptCount] = event.retryCount + 1
            it[lastError] = errorMessage
        }
        OutboxEventTable.deleteWhere { OutboxEventTable.id eq event.id.toKotlinUuid() }
    }

    private fun toOutboxEvent(row: ResultRow): OutboxEvent =
        OutboxEvent(
            id = row[OutboxEventTable.id].toJavaUuid(),
            aggregateType = row[OutboxEventTable.aggregateType],
            aggregateId = row[OutboxEventTable.aggregateId],
            eventTypeCode = row[OutboxEventTable.eventTypeCode],
            payload = row[OutboxEventTable.payload],
            createdAt = row[OutboxEventTable.createdAt],
            sentAt = row[OutboxEventTable.sentAt],
            retryCount = row[OutboxEventTable.retryCount],
            lastError = row[OutboxEventTable.lastError],
            nextRetryAt = row[OutboxEventTable.nextRetryAt]
        )
}
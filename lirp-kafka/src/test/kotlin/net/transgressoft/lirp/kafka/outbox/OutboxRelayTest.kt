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

import net.transgressoft.lirp.event.LirpErrorContext
import net.transgressoft.lirp.event.LirpErrorHandler
import net.transgressoft.lirp.event.LirpOperation
import net.transgressoft.lirp.kafka.KafkaEventPublisher
import net.transgressoft.lirp.kafka.KafkaOutboxConfig
import net.transgressoft.lirp.kafka.KafkaOutboxSqlRepository
import net.transgressoft.lirp.kafka.spi.LirpEventEnvelope
import net.transgressoft.lirp.kafka.spi.LirpEventSerializer
import net.transgressoft.lirp.kafka.spi.SerializedEvent
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.sql.AudioItemSqlTableDef
import net.transgressoft.lirp.persistence.sql.H2ContainerSupport
import net.transgressoft.lirp.persistence.transaction
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.common.errors.NetworkException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Version
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.toKotlinUuid

/**
 * H2 unit tests for the outbox relay machinery.
 *
 * Each test uses a fresh H2 DataSource for isolation. The relay is driven synchronously via
 * [OutboxRelay.pollAndRelay] rather than the background loop so tests are deterministic and
 * do not need to wait for the poll interval. Datasource/schema setup and teardown are handled by
 * [withOutboxDb]/[withOutboxRepo]; outbox and dead-letter assertions read through the [Database]
 * count/row helpers.
 */
@DisplayName("OutboxRelayTest (H2)")
internal class OutboxRelayTest : StringSpec() {

    val fastConfig =
        KafkaOutboxConfig(
            pollIntervalMs = 100L,
            batchSize = 10,
            maxRetries = 3,
            retryBaseDelayMs = 100L,
            retryMaxDelayMs = 1_000L
        )

    init {
        afterEach {
            RegistryBase.deregisterRepository(AudioItem::class.java)
        }

        "drains the outbox by publishing rows and flipping sent_at" {
            withOutboxRepo { db, repo ->
                val publisher = mockk<KafkaEventPublisher<*, *>>()
                justRun { publisher.publish(any(), any(), any(), any()) }

                transaction(repo) { r ->
                    r.add(MutableAudioItem(1, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem)
                    r.add(MutableAudioItem(2, "Killer Queen", "Sheer Heart Attack") as AudioItem)
                }

                OutboxRelay(db, publisher, fastConfig).pollAndRelay()

                // All rows should have sent_at set after one cycle.
                db.unsentOutboxCount() shouldBe 0L
                verify(exactly = 2) { publisher.publish(any(), any(), any(), any()) }
            }
        }

        "marks sent_at only after publish succeeds and uses aggregate id as record key" {
            withOutboxRepo { db, repo ->
                val capturedKeys = mutableListOf<String>()
                val publisher = mockk<KafkaEventPublisher<*, *>>()
                every { publisher.publish(any(), capture(capturedKeys), any(), any()) } returns Unit

                transaction(repo) { r ->
                    r.add(MutableAudioItem(42, "We Will Rock You", "News of the World") as AudioItem)
                }

                OutboxRelay(db, publisher, fastConfig).pollAndRelay()

                db.singleOutboxRow()[OutboxEventTable.sentAt].shouldNotBeNull()
                // Record key must equal the aggregate id.
                capturedKeys.single() shouldBe "42"
            }
        }

        "retriable error leaves sent_at null, increments retry_count, sets next_retry_at" {
            withOutboxRepo { db, repo ->
                val publisher = mockk<KafkaEventPublisher<*, *>>()
                every { publisher.publish(any(), any(), any(), any()) } throws NetworkException("broker unavailable")

                transaction(repo) { r ->
                    r.add(MutableAudioItem(10, "Radio Ga Ga", "The Works") as AudioItem)
                }

                OutboxRelay(db, publisher, fastConfig).pollAndRelay()

                val row = db.singleOutboxRow()
                row[OutboxEventTable.sentAt].shouldBeNull()
                row[OutboxEventTable.retryCount] shouldBe 1
                row[OutboxEventTable.nextRetryAt].shouldNotBeNull()
                // Dead-letter table stays empty.
                db.deadLetterCount() shouldBe 0L
            }
        }

        "non-retriable error moves row to dead-letter table and relay continues" {
            withOutboxRepo { db, repo ->
                val publisher = mockk<KafkaEventPublisher<*, *>>()
                // First call throws non-retriable; second call succeeds so the relay continues.
                every { publisher.publish(any(), "10", any(), any()) } throws RecordTooLargeException("record too large")
                every { publisher.publish(any(), "20", any(), any()) } returns Unit

                transaction(repo) { r ->
                    r.add(MutableAudioItem(10, "Somebody to Love", "A Day at the Races") as AudioItem)
                    r.add(MutableAudioItem(20, "We Are the Champions", "News of the World") as AudioItem)
                }

                OutboxRelay(db, publisher, fastConfig).pollAndRelay()

                // Row 10 moved to dead-letter; row 20 marked sent.
                db.outboxCount() shouldBe 1L
                db.deadLetterCount() shouldBe 1L
                val sentRow = db.singleOutboxRow()
                sentRow[OutboxEventTable.aggregateId] shouldBe "20"
                sentRow[OutboxEventTable.sentAt].shouldNotBeNull()
            }
        }

        "exhausted retries move row to dead-letter table after a failed delivery attempt" {
            withOutboxDb { _, db ->
                val publisher = mockk<KafkaEventPublisher<*, *>>()
                every { publisher.publish(any(), any(), any(), any()) } throws NetworkException("broker unavailable")

                // Seed a row whose retry budget is already exhausted.
                db.seedExhaustedRow("99", fastConfig.maxRetries)

                OutboxRelay(db, publisher, fastConfig).pollAndRelay()

                // A retriable failure on a row with no retry budget left moves it to the dead-letter table.
                db.outboxCount() shouldBe 0L
                db.deadLetterCount() shouldBe 1L
                // Delivery is attempted exactly once before dead-lettering — never skipped.
                verify(exactly = 1) { publisher.publish(any(), any(), any(), any()) }
            }
        }

        "computeNextRetryAt grows exponentially within retryBaseDelayMs and retryMaxDelayMs" {
            val config =
                KafkaOutboxConfig(
                    retryBaseDelayMs = 1_000L,
                    retryMaxDelayMs = 30_000L
                )
            val now = Clock.System.now()

            // Each delay must fit within the [base * 0.8, max * 1.2] range to account for ±20% jitter.
            val delay0 = OutboxRelay.computeNextRetryAt(0, config)
            val delay1 = OutboxRelay.computeNextRetryAt(1, config)
            val delay2 = OutboxRelay.computeNextRetryAt(2, config)

            // Delay grows: delay0 < delay1 < delay2 (in expectation; upper bound enforced).
            delay0.shouldBeLessThan(delay1)
            delay1.shouldBeLessThan(delay2)

            // None should exceed now + maxDelayMs * 1.2 (jitter headroom).
            val cap = now + 36_000L.milliseconds // 30s * 1.2
            delay0.shouldBeLessThan(cap)
            delay1.shouldBeLessThan(cap)
            delay2.shouldBeLessThan(cap)
        }

        "dead-lettered row invokes onDeadLetter callback with non-null Throwable and LirpOperation.EMIT" {
            withOutboxRepo { db, repo ->
                val publisher = mockk<KafkaEventPublisher<*, *>>()
                every { publisher.publish(any(), any(), any(), any()) } throws RecordTooLargeException("too large")

                var capturedThrowable: Throwable? = null
                var capturedContext: LirpErrorContext? = null
                val callback =
                    LirpErrorHandler { t, ctx ->
                        capturedThrowable = t
                        capturedContext = ctx
                    }

                transaction(repo) { r ->
                    r.add(MutableAudioItem(5, "Don't Stop Me Now", "Jazz") as AudioItem)
                }

                OutboxRelay(db, publisher, fastConfig, onDeadLetter = callback).pollAndRelay()

                capturedThrowable.shouldNotBeNull()
                capturedContext.shouldNotBeNull().operation shouldBe LirpOperation.EMIT
            }
        }

        "exhausted retries also invoke onDeadLetter callback" {
            withOutboxDb { _, db ->
                val publisher = mockk<KafkaEventPublisher<*, *>>()
                every { publisher.publish(any(), any(), any(), any()) } throws NetworkException("broker unavailable")

                var callbackInvoked = false
                val callback =
                    LirpErrorHandler { _, ctx ->
                        callbackInvoked = true
                        ctx.operation shouldBe LirpOperation.EMIT
                    }

                db.seedExhaustedRow("77", fastConfig.maxRetries)

                OutboxRelay(db, publisher, fastConfig, onDeadLetter = callback).pollAndRelay()

                callbackInvoked shouldBe true
            }
        }

        "calling start when relay is already running throws IllegalStateException" {
            withOutboxDb { _, db ->
                val publisher = mockk<KafkaEventPublisher<*, *>>(relaxed = true)
                val relay = OutboxRelay(db, publisher, fastConfig)
                try {
                    relay.start()
                    shouldThrow<IllegalStateException> { relay.start() }
                } finally {
                    relay.stop()
                }
            }
        }

        // #332 — PM-13: scheduleRetry DB-write failure must not produce a no-backoff busy re-publish
        "OutboxRelay advances backoff or dead-letters row when scheduleRetry DB write fails" {
            withOutboxRepo { db, repo ->
                val publisher = mockk<KafkaEventPublisher<*, *>>()
                every { publisher.publish(any(), any(), any(), any()) } throws NetworkException("broker unavailable")

                transaction(repo) { r ->
                    r.add(MutableAudioItem(11, "Killer Queen", "Sheer Heart Attack") as AudioItem)
                }

                // Fault-inject a store whose scheduleRetry always throws to simulate a DB failure.
                val faultStore = FaultingOutboxStore(db, scheduleRetryThrows = true)
                val relay = OutboxRelay(db, publisher, fastConfig, store = faultStore)

                // Drive two poll cycles.
                relay.pollAndRelay()
                relay.pollAndRelay()

                // The row must NOT be busy-re-published with retry_count=0 on every cycle.
                // Acceptable outcomes: row dead-lettered (moved out of outbox), OR retry_count advanced.
                // Forbidden: publish called twice with retry_count still 0 in outbox.
                val row = transaction(db) { OutboxEventTable.selectAll().firstOrNull() }
                if (row != null) {
                    // Row still in outbox: retry_count must be > 0 (backoff was advanced despite DB failure)
                    // OR the row was not re-published (publish was only called once total).
                    val retryCount = row[OutboxEventTable.retryCount]
                    // If retry_count is still 0, verify publish was called at most once — no busy loop.
                    if (retryCount == 0) {
                        verify(atMost = 1) { publisher.publish(any(), any(), any(), any()) }
                    }
                } else {
                    // Row resolved: it must be in the dead-letter table.
                    db.deadLetterCount() shouldBe 1L
                }
            }
        }

        // #333 — PM-14: duplicate dead-letter PK must not wedge a poison row that re-fails every cycle
        "OutboxRelay duplicate dead-letter id does not leave a poison row re-fetched every cycle" {
            withOutboxDb { _, db ->
                val publisher = mockk<KafkaEventPublisher<*, *>>()
                every { publisher.publish(any(), any(), any(), any()) } throws RecordTooLargeException("too large")

                // Seed the outbox row with a known UUID.
                val knownId = java.util.UUID.randomUUID()
                db.seedRowWithId(knownId, "55")

                // Pre-seed the dead-letter table with the same id to trigger a PK collision.
                db.seedDeadLetterRow(knownId)

                // Drive two poll cycles.
                OutboxRelay(db, publisher, fastConfig).pollAndRelay()
                OutboxRelay(db, publisher, fastConfig).pollAndRelay()

                // After two cycles the outbox row must be gone — not re-fetched and re-failing forever.
                db.outboxCount() shouldBe 0L
            }
        }

        // #335 — PM-16: MariaDB<10.6 and MySQL5.7 must not receive FOR UPDATE SKIP LOCKED
        "SqlOutboxStore selectLockOption returns plain FOR UPDATE for MariaDB<10.6 and MySQL<8.0" {
            // These version guard assertions run at the SQL-generation level rather than via a
            // Testcontainers MariaDB 10.5 container. The Testcontainers matrix only includes
            // MariaDB 11 and MySQL 8.0+ (both of which support SKIP LOCKED), so a MariaDB 10.5
            // container would require a new CI image. A unit test on the lock-option selection
            // logic provides equivalent coverage of the guard without adding a new container dependency.
            val mariaDb = org.jetbrains.exposed.v1.core.vendors.MariaDBDialect()
            val mysql = org.jetbrains.exposed.v1.core.vendors.MysqlDialect()

            val version105 = Version.from("10.5")
            val version106 = Version.from("10.6")
            val version57 = Version.from("5.7")
            val version80 = Version.from("8.0")

            // MariaDB < 10.6: SKIP LOCKED is NOT supported — must return plain FOR UPDATE or null
            val option105 = SqlOutboxStore.selectLockOption(mariaDb, version105)
            // Must NOT be a SKIP LOCKED variant
            option105.toQuerySuffix().contains("SKIP LOCKED") shouldBe false

            // MariaDB 10.6+: SKIP LOCKED IS supported
            val option106 = SqlOutboxStore.selectLockOption(mariaDb, version106)
            option106.toQuerySuffix().contains("SKIP LOCKED") shouldBe true

            // MySQL < 8.0: SKIP LOCKED is NOT supported
            val option57 = SqlOutboxStore.selectLockOption(mysql, version57)
            option57.toQuerySuffix().contains("SKIP LOCKED") shouldBe false

            // MySQL 8.0+: SKIP LOCKED IS supported
            val option80 = SqlOutboxStore.selectLockOption(mysql, version80)
            option80.toQuerySuffix().contains("SKIP LOCKED") shouldBe true
        }

        // #334 — PM-15: serialize Error/OOM must not kill the relay; RecordTooLargeException handled distinctly
        "OutboxRelay keeps polling after a serialize Error and does not dead-letter other rows" {
            withOutboxRepo { db, repo ->
                // First row will trigger an OOM/Error in the serializer; second should still be processed.
                transaction(repo) { r ->
                    r.add(MutableAudioItem(71, "Flash", "Flash Gordon") as AudioItem)
                    r.add(MutableAudioItem(72, "We Will Rock You", "News of the World") as AudioItem)
                }

                val publisher = mockk<KafkaEventPublisher<*, *>>()
                justRun { publisher.publish(any(), any(), any(), any()) }

                // Serializer that throws OutOfMemoryError for the first row, succeeds for the rest.
                val oomSerializer = OomOnFirstCallSerializer()

                // If the relay does NOT catch Throwable, the relay coroutine dies on the first row
                // and the second row is never published. After the fix, both rows should be resolved.
                OutboxRelay(db, publisher, fastConfig, serializer = oomSerializer).pollAndRelay()
                OutboxRelay(db, publisher, fastConfig, serializer = oomSerializer).pollAndRelay()

                // The second row (aggregate 72) must have been published and marked sent.
                val sentRows =
                    transaction(db) {
                        OutboxEventTable.selectAll()
                            .where { OutboxEventTable.aggregateId eq "72" }
                            .map { it[OutboxEventTable.sentAt] }
                    }
                sentRows.any { it != null } shouldBe true
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Fixtures & helpers
// -------------------------------------------------------------------------------------------------

/**
 * Runs [block] with a fresh H2 [Database] whose outbox and dead-letter tables already exist, closing
 * the datasource afterwards. Suits tests that seed rows directly rather than through a repository.
 */
private inline fun withOutboxDb(block: (HikariDataSource, Database) -> Unit) {
    val dataSource = H2ContainerSupport.buildH2DataSource()
    try {
        val db = Database.connect(dataSource)
        transaction(db) {
            SchemaUtils.create(OutboxEventTable)
            SchemaUtils.create(DeadLetterTable)
        }
        block(dataSource, db)
    } finally {
        dataSource.close()
    }
}

/**
 * Runs [block] with a fresh H2 [Database] plus a [KafkaOutboxSqlRepository] over [AudioItem], closing
 * the repository and datasource afterwards. Suits tests that seed rows through the transactional
 * flush hook.
 */
private inline fun withOutboxRepo(block: (Database, KafkaOutboxSqlRepository<Int, AudioItem>) -> Unit) {
    withOutboxDb { dataSource, db ->
        val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
        try {
            block(db, repo)
        } finally {
            repo.close()
        }
    }
}

/** Counts outbox rows whose `sent_at` is null (pending delivery). */
private fun Database.unsentOutboxCount(): Long =
    transaction(this) {
        OutboxEventTable.selectAll().where { OutboxEventTable.sentAt.isNull() }.count()
    }

/** Counts all outbox rows, sent or pending. */
private fun Database.outboxCount(): Long = transaction(this) { OutboxEventTable.selectAll().count() }

/** Counts all rows in the dead-letter table. */
private fun Database.deadLetterCount(): Long = transaction(this) { DeadLetterTable.selectAll().count() }

/** Returns the single outbox row, failing if there is not exactly one. */
private fun Database.singleOutboxRow(): ResultRow = transaction(this) { OutboxEventTable.selectAll().single() }

/**
 * Seeds one outbox row for [aggregateId] with the given [retryCount] via bare Exposed DSL, bypassing
 * the flush hook. Used to exercise the exhausted-retry path where the persisted attempt count already
 * equals the configured maximum.
 */
private fun Database.seedExhaustedRow(aggregateId: String, retryCount: Int) {
    transaction(this) {
        OutboxEventTable.insert {
            it[OutboxEventTable.id] = java.util.UUID.randomUUID().toKotlinUuid()
            it[OutboxEventTable.aggregateType] = "audio_items"
            it[OutboxEventTable.aggregateId] = aggregateId
            it[OutboxEventTable.eventTypeCode] = 100
            it[OutboxEventTable.payload] = "{}"
            it[OutboxEventTable.createdAt] = Clock.System.now()
            it[OutboxEventTable.retryCount] = retryCount
        }
    }
}

/** Seeds an outbox row with a specific [id] to enable PK-collision testing. */
private fun Database.seedRowWithId(id: java.util.UUID, aggregateId: String) {
    transaction(this) {
        OutboxEventTable.insert {
            it[OutboxEventTable.id] = id.toKotlinUuid()
            it[OutboxEventTable.aggregateType] = "audio_items"
            it[OutboxEventTable.aggregateId] = aggregateId
            it[OutboxEventTable.eventTypeCode] = 100
            it[OutboxEventTable.payload] = "{}"
            it[OutboxEventTable.createdAt] = Clock.System.now()
        }
    }
}

/** Seeds a dead-letter row with [id] to pre-occupy the PK and trigger a collision on the next dead-letter insert. */
private fun Database.seedDeadLetterRow(id: java.util.UUID) {
    transaction(this) {
        DeadLetterTable.insert {
            it[DeadLetterTable.id] = id.toKotlinUuid()
            it[DeadLetterTable.aggregateType] = "audio_items"
            it[DeadLetterTable.aggregateId] = "pre-existing"
            it[DeadLetterTable.eventTypeCode] = 100
            it[DeadLetterTable.payload] = "{}"
            it[DeadLetterTable.createdAt] = Clock.System.now()
            it[DeadLetterTable.failedAt] = Clock.System.now()
            it[DeadLetterTable.attemptCount] = 1
            it[DeadLetterTable.lastError] = "pre-existing"
        }
    }
}

/**
 * An [OutboxStore] wrapper that delegates all operations to the real [SqlOutboxStore] but injects
 * a controllable fault into [scheduleRetry]. Used to simulate a DB failure on the retry-write
 * path without mocking the entire store interface.
 */
private class FaultingOutboxStore(
    db: Database,
    val scheduleRetryThrows: Boolean = false
) : OutboxStore {
    val delegate = SqlOutboxStore(db)

    override fun findUnsent(limit: Int) = delegate.findUnsent(limit)

    override fun markSent(id: java.util.UUID) = delegate.markSent(id)

    override fun findUnsentForRelay(limit: Int, now: kotlin.time.Instant) = delegate.findUnsentForRelay(limit, now)

    override fun scheduleRetry(id: java.util.UUID, nextRetryAt: kotlin.time.Instant, errorMessage: String) {
        if (scheduleRetryThrows) error("simulated scheduleRetry DB failure")
        delegate.scheduleRetry(id, nextRetryAt, errorMessage)
    }

    override fun moveToDeadLetter(event: OutboxEvent, failedAt: kotlin.time.Instant, errorMessage: String) =
        delegate.moveToDeadLetter(event, failedAt, errorMessage)

    override fun insert(event: OutboxEvent) = delegate.insert(event)
}

/** Returns the query suffix string of this [ForUpdateOption] or empty string if null. */
private fun ForUpdateOption?.toQuerySuffix(): String = this?.querySuffix ?: ""

/**
 * A [LirpEventSerializer] that throws [OutOfMemoryError] on the first call, then delegates to
 * [net.transgressoft.lirp.kafka.spi.CloudEventsBinarySerializer] for subsequent calls.
 * Used to simulate an OOM during serialization to verify the relay keeps polling after the error.
 */
private class OomOnFirstCallSerializer : LirpEventSerializer {
    val real = net.transgressoft.lirp.kafka.spi.CloudEventsBinarySerializer()
    var callCount = 0

    override fun serialize(envelope: LirpEventEnvelope): SerializedEvent {
        callCount++
        if (callCount == 1) throw OutOfMemoryError("simulated OOM during serialize")
        return real.serialize(envelope)
    }

    override fun deserialize(value: ByteArray, headers: Map<String, ByteArray>): LirpEventEnvelope =
        real.deserialize(value, headers)
}
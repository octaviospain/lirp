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
import org.jetbrains.exposed.v1.core.isNull
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
                relay.start()
                shouldThrow<IllegalStateException> { relay.start() }
                relay.stop()
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
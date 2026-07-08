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

import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.kafka.KafkaEventPublisher
import net.transgressoft.lirp.kafka.spi.OutboxRoutableEvent
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.sql.H2ContainerSupport
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * H2 unit tests for [KafkaEventPublisher.emitAsync] transaction enlistment behaviour.
 *
 * Covers: emitAsync inside an active transaction writes the outbox row atomically
 * on the same connection (row visible before commit); emitAsync outside any transaction opens
 * its own single-row transaction; and — the cross-Database topology used by
 * [net.transgressoft.lirp.kafka.LirpKafkaConfig.startRelay] — emitAsync joins an ambient
 * transaction opened on a *different* Exposed [Database] instance that wraps the same
 * [javax.sql.DataSource], so rollback removes the outbox row atomically.
 */
@DisplayName("OutboxEnlistmentTest")
internal class OutboxEnlistmentTest : StringSpec() {

    /** A consumer-defined custom event type with a code outside the flush-managed set. */
    enum class PlaybackEventType(override val code: Int) : EventType {
        STARTED(998)
    }

    data class PlaybackEvent(
        override val type: PlaybackEventType,
        val trackId: Int
    ) : OutboxRoutableEvent<PlaybackEventType> {
        override val aggregateId: String get() = trackId.toString()
        override val payload: String get() = """{"trackId":$trackId}"""
    }

    init {
        afterEach {
            RegistryBase.deregisterRepository(AudioItem::class.java)
        }

        "OutboxEnlistmentTest emitAsync inside active transaction writes row visible before commit on same connection" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val publisher =
                KafkaEventPublisher<PlaybackEventType, PlaybackEvent>("enlistment-in-tx", "localhost:9092", db)
            publisher.activateEvents(PlaybackEventType.STARTED)

            try {
                var rowCountInTx = 0L
                transaction(db) {
                    publisher.emitAsync(PlaybackEvent(PlaybackEventType.STARTED, trackId = 10))
                    rowCountInTx = OutboxEventTable.selectAll().count()
                }
                rowCountInTx shouldBe 1L
                // Committed row is also visible after transaction commit
                val rowCountAfter = transaction(db) { OutboxEventTable.selectAll().count() }
                rowCountAfter shouldBe 1L
            } finally {
                publisher.close()
                dataSource.close()
            }
        }

        "OutboxEnlistmentTest emitAsync without active transaction opens own single-row transaction and commits one row" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val publisher =
                KafkaEventPublisher<PlaybackEventType, PlaybackEvent>("enlistment-no-tx", "localhost:9092", db)
            publisher.activateEvents(PlaybackEventType.STARTED)

            try {
                publisher.emitAsync(PlaybackEvent(PlaybackEventType.STARTED, trackId = 20))
                val rowCount = transaction(db) { OutboxEventTable.selectAll().count() }
                rowCount shouldBe 1L
            } finally {
                publisher.close()
                dataSource.close()
            }
        }

        "OutboxEnlistmentTest emitAsync joins app transaction across different Database instances over one DataSource" {
            // Reproduces the real startRelay topology: the publisher's Database and the app's
            // Database are different Exposed instances wrapping the same underlying connection pool.
            // Before the fix, the gate `currentTransaction.db == publisherDb` was always false in
            // this topology, so emitAsync opened its own transaction — the outbox INSERT committed
            // independently and a rollback of appDb left a phantom outbox row.
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val appDb = Database.connect(dataSource)
            val publisherDb = Database.connect(dataSource)
            transaction(appDb) { SchemaUtils.create(OutboxEventTable) }

            // Build the publisher via the internal constructor, supplying dataSource so the
            // db→dataSource mapping is registered and the cross-Database join gate activates.
            val publisher =
                KafkaEventPublisher<PlaybackEventType, PlaybackEvent>(
                    "enlistment-cross-db", "localhost:9092", publisherDb,
                    SqlOutboxStore(publisherDb), emptyMap(), dataSource = dataSource
                )
            publisher.activateEvents(PlaybackEventType.STARTED)

            try {
                // Case 1: commit — outbox row must survive.
                transaction(appDb) {
                    publisher.emitAsync(PlaybackEvent(PlaybackEventType.STARTED, trackId = 30))
                }
                val rowCountAfterCommit = transaction(appDb) { OutboxEventTable.selectAll().count() }
                rowCountAfterCommit shouldBe 1L

                // Reset outbox for Case 2.
                transaction(appDb) { OutboxEventTable.deleteAll() }

                // Case 2: rollback — outbox row must be removed atomically with the app transaction.
                // Trigger rollback by throwing inside the transaction block.
                runCatching {
                    transaction(appDb) {
                        publisher.emitAsync(PlaybackEvent(PlaybackEventType.STARTED, trackId = 31))
                        error("injected rollback — outbox row must vanish with the app transaction")
                    }
                }
                val rowCountAfterRollback = transaction(appDb) { OutboxEventTable.selectAll().count() }
                rowCountAfterRollback shouldBe 0L
            } finally {
                publisher.close()
                dataSource.close()
            }
        }
    }
}
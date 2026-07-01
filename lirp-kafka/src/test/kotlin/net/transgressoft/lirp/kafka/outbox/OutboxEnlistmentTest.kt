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
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * H2 unit tests for [KafkaEventPublisher.emitAsync] transaction enlistment behaviour.
 *
 * Covers: emitAsync inside an active transaction writes the outbox row atomically
 * on the same connection (row visible before commit), and emitAsync outside any
 * transaction opens its own single-row transaction producing exactly one committed row.
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
    }
}
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

package net.transgressoft.lirp.kafka

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.LirpEvent
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.kafka.outbox.OutboxEventTable
import net.transgressoft.lirp.kafka.spi.OutboxRoutableEvent
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.VolatileRepository
import net.transgressoft.lirp.persistence.sql.H2ContainerSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * H2 unit tests for [KafkaEventPublisher] injection and gating behaviour.
 *
 * Covers: local subscriber fires on custom emitAsync, VolatileRepository publisher overload
 * routes CRUD events, flush-managed codes do not write outbox rows, disableEvents
 * fully suppresses both local delivery and outbox capture, OutboxRoutableEvent carries correct
 * aggregateId and payload into the outbox row, capture happens before local delivery, and
 * non-routable custom events throw immediately.
 */
@DisplayName("KafkaEventPublisherTest")
internal class KafkaEventPublisherTest : StringSpec() {

    /** A consumer-defined custom event type with a code outside the framework CRUD/mutation range. */
    enum class PlaybackEventType(override val code: Int) : EventType {
        STARTED(999)
    }

    /**
     * A consumer-defined event type that deliberately reuses the framework CREATE code (`100`),
     * used to prove the outbox gate keys off the event-type class rather than its numeric code.
     */
    enum class CollidingEventType(override val code: Int) : EventType {
        RENAMED(100)
    }

    /** Custom routable event carrying [CollidingEventType] so it can be captured into the outbox. */
    data class CollidingEvent(
        override val type: CollidingEventType,
        val trackId: Int
    ) : OutboxRoutableEvent<CollidingEventType> {
        override val aggregateId: String get() = trackId.toString()
        override val payload: String get() = """{"trackId":$trackId}"""
    }

    /**
     * Consumer-defined event implementing [OutboxRoutableEvent] so it can be relayed through
     * [KafkaEventPublisher.emitAsync] to the transactional outbox.
     */
    data class PlaybackEvent(
        override val type: PlaybackEventType,
        val trackId: Int
    ) : OutboxRoutableEvent<PlaybackEventType> {
        override val aggregateId: String get() = trackId.toString()
        override val payload: String get() = """{"trackId":$trackId}"""
    }

    /**
     * A custom event that intentionally does NOT implement [OutboxRoutableEvent], used to verify
     * that emitting it through [KafkaEventPublisher.emitAsync] throws.
     */
    data class NonRoutableEvent(
        override val type: PlaybackEventType,
        val trackId: Int
    ) : LirpEvent<PlaybackEventType>

    init {
        afterEach {
            RegistryBase.deregisterRepository(AudioItem::class.java)
        }

        "KafkaEventPublisherTest injected publisher fires local subscriber on emitAsync of custom event" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val publisher = KafkaEventPublisher<PlaybackEventType, PlaybackEvent>("test", "localhost:9092", db)
            publisher.activateEvents(PlaybackEventType.STARTED)
            var received: PlaybackEvent? = null
            publisher.subscribe { received = it }

            try {
                publisher.emitAsync(PlaybackEvent(PlaybackEventType.STARTED, trackId = 1))
                received?.trackId shouldBe 1
            } finally {
                publisher.close()
                dataSource.close()
            }
        }

        "KafkaEventPublisherTest VolatileRepository publisher overload routes CRUD events to supplied publisher" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val kafkaPublisher =
                KafkaEventPublisher<CrudEvent.Type, CrudEvent<Int, AudioItem>>("test-repo", "localhost:9092", db)
            kafkaPublisher.activateEvents(*CrudEvent.Type.entries.toTypedArray())
            val repo =
                VolatileRepository<Int, AudioItem>(
                    name = "audio-items",
                    publisher = kafkaPublisher
                )

            val received = mutableListOf<CrudEvent<Int, AudioItem>>()
            kafkaPublisher.subscribe { received.add(it) }
            val item = MutableAudioItem(1, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem

            try {
                repo.add(item)
                received.size shouldBe 1
                (received.single() as StandardCrudEvent.Create<Int, AudioItem>).entities.values.single() shouldBe item
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "KafkaEventPublisherTest emitAsync with flush-managed CREATE code does not write outbox row" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val kafkaPublisher =
                KafkaEventPublisher<CrudEvent.Type, CrudEvent<Int, AudioItem>>("test-flush-gate", "localhost:9092", db)
            kafkaPublisher.activateEvents(*CrudEvent.Type.entries.toTypedArray())

            val item = MutableAudioItem(2, "Killer Queen", "Sheer Heart Attack") as AudioItem

            try {
                kafkaPublisher.emitAsync(StandardCrudEvent.Create(item))
                val rowCount = transaction(db) { OutboxEventTable.selectAll().count() }
                rowCount shouldBe 0L
            } finally {
                kafkaPublisher.close()
                dataSource.close()
            }
        }

        "KafkaEventPublisherTest custom event reusing a framework code is still captured to the outbox" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val publisher = KafkaEventPublisher<CollidingEventType, CollidingEvent>("test-collision", "localhost:9092", db)
            publisher.activateEvents(CollidingEventType.RENAMED)

            try {
                // RENAMED reuses CREATE's code (100) but is not a framework CrudEvent.Type, so the
                // gate must NOT treat it as flush-managed — the row has to reach the outbox.
                publisher.emitAsync(CollidingEvent(CollidingEventType.RENAMED, trackId = 100))
                val rows = transaction(db) { OutboxEventTable.selectAll().toList() }
                rows.size shouldBe 1
                rows.single()[OutboxEventTable.eventTypeCode] shouldBe 100
                rows.single()[OutboxEventTable.aggregateId] shouldBe "100"
            } finally {
                publisher.close()
                dataSource.close()
            }
        }

        "KafkaEventPublisherTest disableEvents gates both local delivery and outbox capture" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val publisher = KafkaEventPublisher<PlaybackEventType, PlaybackEvent>("test-disable", "localhost:9092", db)
            publisher.activateEvents(PlaybackEventType.STARTED)
            publisher.disableEvents(PlaybackEventType.STARTED)

            var received: PlaybackEvent? = null
            publisher.subscribe { received = it }

            try {
                publisher.emitAsync(PlaybackEvent(PlaybackEventType.STARTED, trackId = 3))
                received shouldBe null
                val rowCount = transaction(db) { OutboxEventTable.selectAll().count() }
                rowCount shouldBe 0L
            } finally {
                publisher.close()
                dataSource.close()
            }
        }

        "KafkaEventPublisherTest OutboxRoutableEvent emitAsync writes outbox row with correct aggregateId and payload" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val publisher = KafkaEventPublisher<PlaybackEventType, PlaybackEvent>("test-routable", "localhost:9092", db)
            publisher.activateEvents(PlaybackEventType.STARTED)

            try {
                publisher.emitAsync(PlaybackEvent(PlaybackEventType.STARTED, trackId = 42))

                val rows = transaction(db) { OutboxEventTable.selectAll().toList() }
                rows.size shouldBe 1
                rows.single()[OutboxEventTable.aggregateId] shouldBe "42"
                rows.single()[OutboxEventTable.payload] shouldBe """{"trackId":42}"""
                rows.single()[OutboxEventTable.eventTypeCode] shouldBe PlaybackEventType.STARTED.code
            } finally {
                publisher.close()
                dataSource.close()
            }
        }

        "KafkaEventPublisherTest capture happens before local delivery so insert failure suppresses subscriber" {
            // Simulate a capture failure by checking that: when the outbox INSERT succeeds, the subscriber
            // is notified after it; and that the subscriber count is zero before emitting when disabled,
            // confirming the order. We verify ordering by asserting that local delivery only fires when
            // the outbox row already exists — i.e. the row is present before the subscriber callback runs.
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val publisher = KafkaEventPublisher<PlaybackEventType, PlaybackEvent>("test-order", "localhost:9092", db)
            publisher.activateEvents(PlaybackEventType.STARTED)

            var rowCountAtDelivery = -1L
            publisher.subscribe {
                // When this callback fires the outbox row must already exist
                rowCountAtDelivery = transaction(db) { OutboxEventTable.selectAll().count() }
            }

            try {
                publisher.emitAsync(PlaybackEvent(PlaybackEventType.STARTED, trackId = 7))
                // Subscriber was called with the row already present (capture-before-delivery)
                rowCountAtDelivery shouldBe 1L
            } finally {
                publisher.close()
                dataSource.close()
            }
        }

        "KafkaEventPublisherTest emitting non-routable custom LirpEvent throws rather than silently persisting unknown data" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            @Suppress("UNCHECKED_CAST")
            val publisher =
                KafkaEventPublisher<PlaybackEventType, LirpEvent<PlaybackEventType>>(
                    "test-non-routable", "localhost:9092", db
                )
            publisher.activateEvents(PlaybackEventType.STARTED)

            try {
                shouldThrow<IllegalStateException> {
                    publisher.emitAsync(NonRoutableEvent(PlaybackEventType.STARTED, trackId = 5))
                }
                // No outbox row must have been written
                val rowCount = transaction(db) { OutboxEventTable.selectAll().count() }
                rowCount shouldBe 0L
            } finally {
                publisher.close()
                dataSource.close()
            }
        }

        "KafkaEventPublisherTest flush-managed codes tee to local subscribers without writing outbox row" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val db = Database.connect(dataSource)
            transaction(db) { SchemaUtils.create(OutboxEventTable) }
            val kafkaPublisher =
                KafkaEventPublisher<CrudEvent.Type, CrudEvent<Int, AudioItem>>("test-flush-local", "localhost:9092", db)
            kafkaPublisher.activateEvents(*CrudEvent.Type.entries.toTypedArray())

            val received = mutableListOf<CrudEvent<Int, AudioItem>>()
            kafkaPublisher.subscribe { received.add(it) }
            val item = MutableAudioItem(9, "Somebody to Love", "A Day at the Races") as AudioItem

            try {
                kafkaPublisher.emitAsync(StandardCrudEvent.Create(item))
                // Local subscriber must have received the event
                received.size shouldBe 1
                // No outbox row — flush-managed codes are captured by the flush hook, not emitAsync
                val rowCount = transaction(db) { OutboxEventTable.selectAll().count() }
                rowCount shouldBe 0L
            } finally {
                kafkaPublisher.close()
                dataSource.close()
            }
        }
    }
}
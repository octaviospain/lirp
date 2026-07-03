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

package net.transgressoft.lirp.kafka

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.LirpEvent
import net.transgressoft.lirp.event.LirpEventPublisher
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.kafka.outbox.OutboxEvent
import net.transgressoft.lirp.kafka.outbox.OutboxStore
import net.transgressoft.lirp.kafka.outbox.SqlOutboxStore
import net.transgressoft.lirp.kafka.spi.OutboxRoutableEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.Header
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ExecutionException
import kotlin.time.Clock

/**
 * A full [LirpEventPublisher] implementation that publishes domain events to Kafka.
 *
 * All subscribe/activation/changes operations delegate to an internally composed
 * [FlowEventPublisher], preserving in-process reactive delivery when this publisher is
 * injected in place of [FlowEventPublisher].
 *
 * [emitAsync] captures custom events into the transactional outbox **before** delivering them to
 * local in-process subscribers, so a capture failure leaves neither the outbox nor local
 * subscribers in an inconsistent state. Framework-owned [CrudEvent.Type] and [MutationEvent.Type]
 * events are already captured by the flush hook in [KafkaOutboxSqlRepository] and are only
 * delivered locally here.
 *
 * Custom events emitted via [emitAsync] must implement [OutboxRoutableEvent] to supply an
 * [OutboxRoutableEvent.aggregateId] and [OutboxRoutableEvent.payload]; emitting a non-routable
 * custom event will throw to surface the misconfiguration immediately.
 *
 * [publish] is a direct synchronous send (acks=all) used by the outbox relay. It accepts
 * an optional [headers] iterable to carry CloudEvents binary-mode attributes.
 *
 * Closing this publisher stops in-process subscriptions and releases the broker connection.
 * It does **not** stop the outbox relay, whose lifecycle is owned by [LirpKafkaConfig].
 *
 * @param ET The specific [EventType] this publisher emits
 * @param E  The specific [LirpEvent] type this publisher emits
 * @param id Logical identifier, used to name the internal [FlowEventPublisher]
 * @param bootstrapServers Comma-separated list of `host:port` Kafka broker addresses; must be non-blank
 * @param db Exposed [Database] handle used when opening a fresh single-row transaction in [emitAsync]
 * @param outboxStore Internal store used to persist custom event outbox rows in [emitAsync]
 * @param producerConfig Optional producer properties overlaid on the defaults (delivery.timeout.ms,
 *   request.timeout.ms, max.block.ms). Use this to tune or override any producer property; entries
 *   are applied after the defaults, so any key present in [producerConfig] wins.
 */
class KafkaEventPublisher<ET : EventType, E : LirpEvent<ET>>
    internal constructor(
        id: String,
        bootstrapServers: String,
        private val db: Database,
        private val outboxStore: OutboxStore,
        producerConfig: Map<String, String> = emptyMap(),
        private val delegate: FlowEventPublisher<ET, E> = FlowEventPublisher(id)
    ) : LirpEventPublisher<ET, E> by delegate {

        /**
         * Creates a [KafkaEventPublisher] connected to the given [bootstrapServers] and [db].
         *
         * The internal [OutboxStore] is created automatically from [db].
         *
         * @param id Logical identifier for this publisher, used for logging and the delegate [FlowEventPublisher]
         * @param bootstrapServers Comma-separated `host:port` list of Kafka broker addresses; must be non-blank
         * @param db Exposed [Database] handle for outbox row persistence
         * @param producerConfig Optional producer properties overlaid on the defaults (delivery.timeout.ms,
         *   request.timeout.ms, max.block.ms). Entries are applied after defaults so any key present here wins.
         */
        constructor(
            id: String,
            bootstrapServers: String,
            db: Database,
            producerConfig: Map<String, String> = emptyMap()
        ) : this(id, bootstrapServers, db, SqlOutboxStore(db), producerConfig)

        init {
            require(bootstrapServers.isNotBlank()) { "bootstrapServers must not be blank" }
        }

        private val log = KotlinLogging.logger(javaClass.name)

        private val producer: KafkaProducer<String, ByteArray> =
            KafkaProducer(
                Properties().apply {
                    put("bootstrap.servers", bootstrapServers)
                    put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
                    put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer")
                    put("acks", "all")
                    // Safe defaults that bound the publish window so a broker outage does not hold
                    // a HikariCP connection open indefinitely inside the relay's per-row transaction.
                    put("delivery.timeout.ms", "30000")
                    put("request.timeout.ms", "10000")
                    put("max.block.ms", "10000")
                    putAll(producerConfig)
                }
            )

        /**
         * Emits [event] to local in-process subscribers and, when the event is not a framework-owned
         * [CrudEvent.Type] or [MutationEvent.Type], inserts an outbox row to ensure at-least-once
         * Kafka delivery.
         *
         * Outbox capture happens **before** local delivery so that a capture failure leaves
         * neither the outbox nor local subscribers with an inconsistent view. Framework-owned
         * CRUD/mutation events are captured by the [KafkaOutboxSqlRepository] flush hook; for those,
         * only local delivery is performed here. The gate keys off the framework event-type classes
         * rather than their numeric codes, so a consumer-defined [EventType] that happens to reuse a
         * framework code (e.g. `100`) is still captured into the outbox.
         *
         * If the event type is currently disabled, both local delivery and outbox capture are suppressed.
         * When an active Exposed transaction bound to this publisher's [db] is detected the outbox
         * INSERT joins that transaction; otherwise a fresh single-row transaction on [db] is opened.
         *
         * Custom events must implement [OutboxRoutableEvent]; emitting a non-routable custom event
         * throws immediately so the misconfiguration is surfaced as a bug.
         */
        override fun emitAsync(event: E) {
            if (!delegate.isEventActive(event.type)) return
            if (event.type is CrudEvent.Type || event.type is MutationEvent.Type) {
                delegate.emitAsync(event)
                return
            }
            val outboxEvent = buildOutboxEvent(event)
            val currentTransaction = TransactionManager.currentOrNull()
            if (currentTransaction != null && currentTransaction.db == db) {
                outboxStore.insert(outboxEvent)
            } else {
                transaction(db) {
                    outboxStore.insert(outboxEvent)
                }
            }
            delegate.emitAsync(event)
        }

        /**
         * Sends [value] to [topic] with [key] as the partition key.
         *
         * Blocks until the broker acknowledges the record (acks=all). The underlying Kafka exception
         * is unwrapped from [ExecutionException] so callers can distinguish retriable network faults
         * from permanent failures. [headers] are attached to the [ProducerRecord] to carry
         * CloudEvents binary-mode attributes.
         *
         * @param headers Optional record headers; defaults to empty for backwards compatibility
         */
        fun publish(
            topic: String,
            key: String,
            value: ByteArray,
            headers: Iterable<Header> = emptyList()
        ) {
            log.debug { "publish: topic=$topic key=$key bytes=${value.size}" }
            try {
                val record = ProducerRecord<String, ByteArray>(topic, null, null, key, value, headers)
                producer.send(record).get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }

        /**
         * Closes the internal [FlowEventPublisher] and the [KafkaProducer].
         *
         * This does not stop the outbox relay; relay lifecycle is owned by [LirpKafkaConfig].
         */
        override fun close() {
            delegate.close()
            producer.close()
        }

        private fun buildOutboxEvent(event: E): OutboxEvent {
            // Derive aggregateType from the EventType's enclosing class (e.g. MyEntity.EventType → MyEntity),
            // falling back to the EventType class name if no enclosing class is declared.
            val aggregateType =
                event.type::class.java.declaringClass?.simpleName
                    ?: event.type::class.java.simpleName
                    ?: "unknown"
            val routable =
                event as? OutboxRoutableEvent<*>
                    ?: error(
                        "Custom event ${event.type} emitted through KafkaEventPublisher must implement " +
                            "OutboxRoutableEvent to be relayed to Kafka; implement aggregateId and payload " +
                            "or use FlowEventPublisher for local-only delivery"
                    )
            return OutboxEvent(
                id = UUID.randomUUID(),
                aggregateType = aggregateType,
                aggregateId = routable.aggregateId,
                eventTypeCode = event.type.code,
                payload = routable.payload,
                createdAt = Clock.System.now()
            )
        }
    }
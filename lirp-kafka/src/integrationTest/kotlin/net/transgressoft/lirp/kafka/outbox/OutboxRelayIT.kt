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
import net.transgressoft.lirp.kafka.KafkaContainerSupport
import net.transgressoft.lirp.kafka.KafkaEventPublisher
import net.transgressoft.lirp.kafka.KafkaOutboxConfig
import net.transgressoft.lirp.kafka.KafkaOutboxSqlRepository
import net.transgressoft.lirp.kafka.LirpKafkaConfig
import net.transgressoft.lirp.kafka.spi.CloudEventsBinarySerializer
import net.transgressoft.lirp.kafka.spi.OutboxRoutableEvent
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.sql.AudioItemSqlTableDef
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport
import net.transgressoft.lirp.persistence.sql.PostgresContainerSupport
import net.transgressoft.lirp.persistence.transaction
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewPartitions
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.seconds

/**
 * Testcontainers integration tests for the outbox relay end-to-end path.
 *
 * Verifies the relay drains unsent rows against a real SQL dialect and a real Kafka broker: rows
 * are published as Kafka records and their `sent_at` timestamp is set atomically in the same
 * transaction. Scenarios covered:
 *
 * 1. **Normal drain**: relay publishes all unsent rows to Kafka and sets `sent_at`.
 * 2. **Crash/redelivery**: a row whose `sent_at` was never committed (simulating a mid-transaction
 *    crash) is redelivered on the next relay start — proving at-least-once delivery.
 * 3. **Concurrent no-double-publish**: two relay instances against PostgreSQL (which enforces
 *    `FOR UPDATE SKIP LOCKED`) publish each row exactly once.
 * 4. **Per-aggregate ordering**: rapid sequential mutations to the same aggregate id arrive at the
 *    consumer in creation order, proving that `aggregateId` as record key + a multi-partition topic
 *    keeps per-aggregate ordering.
 * 5. **CloudEvents headers**: every relayed record carries `ce_id` equal to the outbox row UUID and
 *    a record key/`ce_subject` equal to the aggregate id.
 * 6. **Custom-event round-trip**: an [OutboxRoutableEvent] emitted via `emitAsync` inside a
 *    transaction is captured, relayed, and deserialized back without data loss.
 * 7. **Dead-letter routing**: a row whose topic name is Kafka-invalid is moved atomically to the
 *    dead-letter table and the relay continues processing sibling rows.
 *
 * Each test resets the outbox/dead-letter tables for isolation so the relay's [SchemaUtils.create]
 * call recreates the schema fresh. The shared `try/finally` resource choreography (repo, publisher,
 * relay config, consumer) is handled by [withResources]; the repeated poll-collect-assert loop is
 * handled by [pollUntil].
 */
@DisplayName("OutboxRelayIT")
internal class OutboxRelayIT : FunSpec({

    afterEach {
        RegistryBase.deregisterRepository(AudioItem::class.java)
    }

    test("normal relay path drains all unsent rows") {
        DatabaseTestSupport.withDatabaseTest(postgres, AudioItemSqlTableDef) { dataSource ->
            withResources {
                resetOutboxSchema(dataSource)
                val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef).managed()
                transaction(repo) { r ->
                    r.add(MutableAudioItem(1, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem)
                    r.add(MutableAudioItem(2, "Killer Queen", "Sheer Heart Attack") as AudioItem)
                    r.add(MutableAudioItem(3, "We Will Rock You", "News of the World") as AudioItem)
                }

                createMultiPartitionTopic(audioItemsTopic, 1)
                val consumer = subscribedConsumer("relay-normal-drain", audioItemsTopic)
                lirpKafkaConfig().startRelay(dataSource, fastRelayConfig())

                consumer.pollUntil { records ->
                    records.map { it.key() } shouldContainExactlyInAnyOrder listOf("1", "2", "3")
                }
                eventually(10.seconds) {
                    countUnsentOutboxRows(dataSource) shouldBe 0L
                }
            }
        }
    }

    test("crash between publish and mark-sent causes redelivery") {
        DatabaseTestSupport.withDatabaseTest(postgres, AudioItemSqlTableDef) { dataSource ->
            withResources {
                resetOutboxSchema(dataSource)
                val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef).managed()
                transaction(repo) { r ->
                    r.add(MutableAudioItem(10, "Radio Ga Ga", "The Works") as AudioItem)
                }

                // First relay run: publish the record to Kafka and set sent_at. Pre-create the topic
                // so this run does not depend on broker auto-topic creation, matching the later runs.
                createMultiPartitionTopic(audioItemsTopic, 1)
                LirpKafkaConfig.create(KafkaContainerSupport.bootstrapServers).use { firstRelay ->
                    firstRelay.startRelay(dataSource, fastRelayConfig())
                    eventually(15.seconds) {
                        countUnsentOutboxRows(dataSource) shouldBe 0L
                    }
                }

                // Simulate crash: reset sent_at back to NULL — the relay published to Kafka but the
                // transaction carrying the sent_at UPDATE rolled back before committing (e.g. a JVM
                // crash or JDBC connection failure mid-transaction).
                resetSentAt(dataSource, "10")
                countUnsentOutboxRows(dataSource) shouldBe 1L

                // Second relay run (crash recovery): subscribe the consumer FIRST (latest offset),
                // then start the relay so the redelivered record for key "10" must appear.
                createMultiPartitionTopic(audioItemsTopic, 1)
                val consumer = subscribedConsumer("relay-crash-recovery", audioItemsTopic)
                lirpKafkaConfig().startRelay(dataSource, fastRelayConfig())

                consumer.pollUntil { records ->
                    records.map { it.key() } shouldContain "10"
                }
                eventually(10.seconds) {
                    countUnsentOutboxRows(dataSource) shouldBe 0L
                }
            }
        }
    }

    test("concurrent relay instances do not double-publish") {
        // FOR UPDATE SKIP LOCKED is only enforced on PostgreSQL/MySQL/MariaDB; H2 gives a false green
        // because its SELECT does not honour the SKIP LOCKED clause.
        withResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef).managed()
            val n = 6
            transaction(repo) { r ->
                repeat(n) { i -> r.add(MutableAudioItem(i + 100, "Track ${i + 1}", "Album A") as AudioItem) }
            }
            val expectedKeys = (100 until 100 + n).map { it.toString() }

            createMultiPartitionTopic(audioItemsTopic, 1)
            val consumer = subscribedConsumer("relay-concurrent", audioItemsTopic)
            lirpKafkaConfig().startRelay(dataSource, fastRelayConfig())
            lirpKafkaConfig().startRelay(dataSource, fastRelayConfig())

            val received =
                consumer.pollUntil(30.seconds) { records ->
                    countUnsentOutboxRows(dataSource) shouldBe 0L
                    records.map { it.key() } shouldContainAll expectedKeys
                }.toMutableList()

            // Keep draining briefly after the outbox is empty: a duplicate published slightly later by
            // the peer relay would otherwise be missed, false-passing the guarantee.
            consumer.drainFor(2.seconds) { received.add(it) }

            // Exactly-once: multiset equality against the unique expected keys fails if any key was
            // published more than once, so SKIP LOCKED prevented double-publishing.
            received.map { it.key() } shouldContainExactlyInAnyOrder expectedKeys
        }
    }

    test("multi-partition topic preserves per-aggregate ordering") {
        DatabaseTestSupport.withDatabaseTest(postgres, AudioItemSqlTableDef) { dataSource ->
            withResources {
                resetOutboxSchema(dataSource)
                val aggregateType = AudioItemSqlTableDef.tableName
                val topic = "$aggregateType.events"
                // Pre-create the topic with 3 partitions. The record key (aggregate id) routes all
                // mutations for a single aggregate to the same partition, preserving order.
                createMultiPartitionTopic(topic, 3)

                val aggregateId = "42"
                val eventCount = 5

                // Use the repository to ensure the outbox table exists, then insert additional raw rows
                // for the same aggregate id to simulate rapid sequential mutations.
                KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef).managed()
                insertRawOutboxRows(dataSource, aggregateType, aggregateId, eventCount)

                val consumer = subscribedConsumer("relay-ordering", topic)
                lirpKafkaConfig().startRelay(dataSource, fastRelayConfig())

                val records =
                    consumer.pollUntil { records ->
                        records.count { it.key() == aggregateId } shouldBe eventCount
                    }

                // Records for the same key land on one partition; asserting the decoded payload
                // sequence (not just increasing offsets) proves the relay published oldest-first.
                records.filter { it.key() == aggregateId }.map { it.payloadText() } shouldBe
                    (0 until eventCount).map { """{"seq":$it}""" }
            }
        }
    }

    test("relayed record carries ce_id header equal to outbox row UUID") {
        DatabaseTestSupport.withDatabaseTest(postgres, AudioItemSqlTableDef) { dataSource ->
            withResources {
                resetOutboxSchema(dataSource)
                val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef).managed()
                transaction(repo) { r ->
                    r.add(MutableAudioItem(201, "Killer Queen", "Sheer Heart Attack") as AudioItem)
                }

                // Capture the outbox row UUID before the relay starts.
                val outboxRowId = firstOutboxRowId(dataSource)

                createMultiPartitionTopic(audioItemsTopic, 1)
                val consumer = subscribedConsumer("relay-ce-id", audioItemsTopic)
                lirpKafkaConfig().startRelay(dataSource, fastRelayConfig())

                val record = consumer.pollUntil { it.shouldNotBeEmpty() }.first()
                record.header("ce_id") shouldBe outboxRowId
            }
        }
    }

    test("relayed record key equals aggregate id and ce_subject header") {
        DatabaseTestSupport.withDatabaseTest(postgres, AudioItemSqlTableDef) { dataSource ->
            withResources {
                resetOutboxSchema(dataSource)
                val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef).managed()
                transaction(repo) { r ->
                    r.add(MutableAudioItem(202, "We Will Rock You", "News of the World") as AudioItem)
                }

                createMultiPartitionTopic(audioItemsTopic, 1)
                val consumer = subscribedConsumer("relay-key-subject", audioItemsTopic)
                lirpKafkaConfig().startRelay(dataSource, fastRelayConfig())

                val record = consumer.pollUntil { it.shouldNotBeEmpty() }.first()
                // Record key equals the aggregate id, which in turn equals the ce_subject header.
                record.key() shouldBe "202"
                record.header("ce_subject") shouldBe "202"
            }
        }
    }

    test("custom event emitted via emitAsync inside transaction round-trips through relay") {
        withResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)

            // KafkaOutboxSqlRepository creates the outbox schema (idempotent).
            KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef).managed()

            val db = Database.connect(dataSource)
            val bootstrapServers = KafkaContainerSupport.bootstrapServers
            val publisher =
                KafkaEventPublisher<PlaybackEventType, PlaybackEvent>("relay-custom-event-it", bootstrapServers, db).managed()
            publisher.activateEvents(PlaybackEventType.STARTED)

            // Emit a custom event inside a transaction — the outbox row must be captured atomically.
            transaction(db) {
                publisher.emitAsync(PlaybackEvent(PlaybackEventType.STARTED, trackId = 77))
            }
            countOutboxRows(dataSource) shouldBe 1L
            val rowId = firstOutboxRowId(dataSource)

            val topic = "$playbackAggregateType.events"
            createMultiPartitionTopic(topic, 1)
            val consumer = subscribedConsumer("relay-custom-rt", topic)
            lirpKafkaConfig().startRelay(dataSource, fastRelayConfig())

            val record = consumer.pollUntil { it.shouldNotBeEmpty() }.first()
            val restoredEnvelope = CloudEventsBinarySerializer().deserialize(record.value(), record.headerBytes())

            restoredEnvelope.eventId shouldBe rowId
            restoredEnvelope.eventTypeCode shouldBe PlaybackEventType.STARTED.code
            restoredEnvelope.aggregateType shouldBe playbackAggregateType
            // ce_id header must equal the outbox row UUID verbatim.
            record.header("ce_id") shouldBe rowId
            // Record key, envelope aggregate id, and payload must carry the original event data
            // (trackId), not "unknown"/"{}" — the OutboxRoutableEvent capture path.
            record.key() shouldBe "77"
            restoredEnvelope.aggregateId shouldBe "77"
            restoredEnvelope.payload shouldBe """{"trackId":77}"""
        }
    }

    test("non-retriable error moves row to dead letter table and relay continues") {
        DatabaseTestSupport.withDatabaseTest(postgres, AudioItemSqlTableDef) { dataSource ->
            withResources {
                resetOutboxSchema(dataSource)

                // maxRetries=1: new rows (retryCount=0) attempt publishing once; a non-retriable Kafka
                // exception (InvalidTopicException from an invalid topic name) is caught and the row is
                // moved to the dead-letter table in the same transaction.
                val relayConfig =
                    KafkaOutboxConfig(
                        pollIntervalMs = 50L,
                        batchSize = 10,
                        maxRetries = 1,
                        retryBaseDelayMs = 50L,
                        retryMaxDelayMs = 100L
                    )

                val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef).managed()

                // Poison row: aggregate_type contains '#', producing a Kafka-invalid topic name
                // ("invalid#topic.events"). Kafka topic names must match [a-zA-Z0-9._-]+; '#' is rejected
                // with InvalidTopicException (a KafkaException subclass, not RetriableException), so the
                // relay moves this row to the dead-letter table.
                val poisonAggregateType = "invalid#topic"
                insertRawOutboxRow(dataSource, poisonAggregateType, "poison-id-1")

                // Valid sibling row the relay must still publish after the dead-letter move.
                transaction(repo) { r ->
                    r.add(MutableAudioItem(99, "Another One Bites the Dust", "The Game") as AudioItem)
                }

                createMultiPartitionTopic(audioItemsTopic, 1)
                val consumer = subscribedConsumer("relay-dead-letter", audioItemsTopic)
                lirpKafkaConfig().startRelay(dataSource, relayConfig)

                consumer.pollUntil { records ->
                    records.map { it.key() } shouldContainExactlyInAnyOrder listOf("99")
                }

                // Poison row must be in the dead-letter table with full failure metadata.
                eventually(10.seconds) {
                    countDeadLetterRows(dataSource) shouldBe 1L
                }
                val dltRow = readFirstDeadLetterRow(dataSource).shouldNotBeNull()
                dltRow["aggregate_type"] shouldBe poisonAggregateType
                (dltRow["attempt_count"] as Number).toInt() shouldBe 1
                dltRow["last_error"].shouldNotBeNull()
                dltRow["failed_at"].shouldNotBeNull()

                // Outbox must be empty — poison row removed, valid row sent.
                countUnsentOutboxRows(dataSource) shouldBe 0L
            }
        }
    }
})

// -------------------------------------------------------------------------------------------------
// Custom event types for the custom-event round-trip test
// -------------------------------------------------------------------------------------------------

/** Consumer-defined event type with a code outside the flush-managed set {100, 300, 400, ...}. */
private enum class PlaybackEventType(override val code: Int) : EventType {
    STARTED(998)
}

/**
 * Consumer-defined event implementing [OutboxRoutableEvent] so the relay can capture
 * [aggregateId] and [payload] into the outbox row rather than discarding them.
 */
private data class PlaybackEvent(
    override val type: PlaybackEventType,
    val trackId: Int
) : OutboxRoutableEvent<PlaybackEventType> {
    override val aggregateId: String get() = trackId.toString()
    override val payload: String get() = """{"trackId":$trackId}"""
}

// -------------------------------------------------------------------------------------------------
// Shared fixtures
// -------------------------------------------------------------------------------------------------

/** The single dialect these broker-level tests run against. */
private val postgres = DatabaseTestSupport.databases.first { it.name == "PostgreSQL" }

/** Default topic for `AudioItem` events, per [net.transgressoft.lirp.kafka.spi.DefaultTopicResolver]. */
private val audioItemsTopic = "${AudioItemSqlTableDef.tableName}.events"

/** Aggregate type the publisher derives by reflection for [PlaybackEventType] events. */
private val playbackAggregateType: String =
    PlaybackEventType.STARTED::class.java.declaringClass?.simpleName
        ?: PlaybackEventType.STARTED::class.java.simpleName
        ?: "unknown"

/**
 * Runs [block] with a [ResourceScope] that closes every [ResourceScope.managed] resource in reverse
 * registration order when the block completes, replacing the nested `try/finally` choreography each
 * test would otherwise repeat. Closing a [LirpKafkaConfig] stops its relay and closes its publisher,
 * so a running relay needs no explicit stop.
 */
private inline fun withResources(block: ResourceScope.() -> Unit) {
    val scope = ResourceScope()
    try {
        scope.block()
    } finally {
        scope.closeAll()
    }
}

/**
 * Tracks per-test [AutoCloseable]s and provides the two factories the relay tests always need: a
 * consumer already subscribed and assigned to a topic, and a relay config wired to the shared broker.
 */
private class ResourceScope {
    private val opened = ArrayDeque<AutoCloseable>()

    fun <T : AutoCloseable> T.managed(): T {
        opened.addFirst(this)
        return this
    }

    fun closeAll() {
        while (opened.isNotEmpty()) {
            runCatching { opened.removeFirst().close() }
        }
    }

    /**
     * Creates a managed [KafkaConsumer] with a unique group id derived from [group], subscribes it to
     * [topic], and waits for partition assignment before returning — assignment must complete before
     * the relay starts so `auto.offset.reset=latest` anchors after the current end-of-topic.
     */
    fun subscribedConsumer(group: String, topic: String): KafkaConsumer<String, ByteArray> {
        val consumer = KafkaConsumer<String, ByteArray>(consumerProps("$group-${System.currentTimeMillis()}")).managed()
        awaitConsumerAssignment(consumer, listOf(topic))
        return consumer
    }

    /** Creates a managed [LirpKafkaConfig] connected to the shared Testcontainers broker. */
    fun lirpKafkaConfig(): LirpKafkaConfig = LirpKafkaConfig.create(KafkaContainerSupport.bootstrapServers).managed()
}

// -------------------------------------------------------------------------------------------------
// Consumer helpers
// -------------------------------------------------------------------------------------------------

/**
 * Polls the consumer, accumulating every record, and re-runs [assert] against the growing list until
 * it passes or the [timeout] elapses. Returns the collected records for further assertions. Keeping
 * [assert] as the loop condition preserves Kotest's descriptive failure message on timeout.
 */
private suspend fun KafkaConsumer<String, ByteArray>.pollUntil(
    timeout: kotlin.time.Duration = 20.seconds,
    assert: (List<ConsumerRecord<String, ByteArray>>) -> Unit
): List<ConsumerRecord<String, ByteArray>> {
    val received = mutableListOf<ConsumerRecord<String, ByteArray>>()
    eventually(timeout) {
        poll(Duration.ofMillis(200)).forEach { received.add(it) }
        assert(received)
    }
    return received
}

/** Polls the consumer for [duration], passing every record to [action]. Used for quiet-period drains. */
private inline fun KafkaConsumer<String, ByteArray>.drainFor(
    duration: kotlin.time.Duration,
    action: (ConsumerRecord<String, ByteArray>) -> Unit
) {
    val deadline = System.currentTimeMillis() + duration.inWholeMilliseconds
    while (System.currentTimeMillis() < deadline) {
        poll(Duration.ofMillis(200)).forEach(action)
    }
}

/** Returns the UTF-8 string value of the last header named [name], or `null` when absent. */
private fun ConsumerRecord<String, ByteArray>.header(name: String): String? =
    headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

/** Returns every header as a name-to-bytes map, matching the [CloudEventsBinarySerializer] input shape. */
private fun ConsumerRecord<String, ByteArray>.headerBytes(): Map<String, ByteArray> =
    headers().associate { it.key() to it.value() }

/** Decodes the record value as a UTF-8 string. */
private fun ConsumerRecord<String, ByteArray>.payloadText(): String = value().toString(Charsets.UTF_8)

/** Builds consumer [Properties] connected to the shared Testcontainers Kafka broker. */
private fun consumerProps(groupId: String): Properties =
    Properties().apply {
        put("bootstrap.servers", KafkaContainerSupport.bootstrapServers)
        put("group.id", groupId)
        put("key.deserializer", StringDeserializer::class.java.name)
        put("value.deserializer", ByteArrayDeserializer::class.java.name)
        // latest: consume only records published AFTER this consumer subscribes,
        // avoiding cross-test pollution when multiple tests share the same topic name.
        put("auto.offset.reset", "latest")
        put("request.timeout.ms", "10000")
        put("default.api.timeout.ms", "10000")
    }

/**
 * Subscribes [consumer] to [topics] and waits until Kafka completes the partition assignment
 * (i.e. [KafkaConsumer.assignment] becomes non-empty). This must be done BEFORE starting the
 * relay so that `auto.offset.reset=latest` is anchored after the current end-of-topic, ensuring
 * the consumer sees only records published during this test run.
 */
private fun <K, V> awaitConsumerAssignment(consumer: KafkaConsumer<K, V>, topics: List<String>) {
    consumer.subscribe(topics)
    // Trigger the initial rebalance by polling; Kafka assigns partitions lazily on first poll.
    val deadline = System.currentTimeMillis() + 15_000L
    while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
        consumer.poll(Duration.ofMillis(200))
    }
    check(consumer.assignment().isNotEmpty()) { "Consumer was not assigned any partitions within 15s for topics $topics" }
}

/**
 * Ensures a Kafka topic named [topic] exists with at least [partitions] partitions via [AdminClient].
 *
 * When the topic already exists on the shared broker its partition count is grown to [partitions] if
 * necessary, so an existing single-partition topic cannot silently defeat a multi-partition test.
 * Any admin failure other than [TopicExistsException] is re-thrown rather than swallowed.
 */
private fun createMultiPartitionTopic(topic: String, partitions: Int) {
    val adminProps =
        Properties().apply {
            put("bootstrap.servers", KafkaContainerSupport.bootstrapServers)
        }
    AdminClient.create(adminProps).use { admin ->
        try {
            admin.createTopics(listOf(NewTopic(topic, partitions, 1.toShort()))).all().get()
        } catch (e: ExecutionException) {
            if (e.cause !is TopicExistsException) throw e
            val existing = admin.describeTopics(listOf(topic)).allTopicNames().get()[topic]
            val currentPartitions = existing?.partitions()?.size ?: 0
            if (currentPartitions < partitions) {
                admin.createPartitions(mapOf(topic to NewPartitions.increaseTo(partitions))).all().get()
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Outbox schema / row helpers (raw JDBC to avoid referencing internal Exposed tables)
// -------------------------------------------------------------------------------------------------

/** Drops the outbox and dead-letter tables so the relay's `SchemaUtils.create` recreates them fresh. */
private fun resetOutboxSchema(dataSource: HikariDataSource) {
    dropTableIfExists(dataSource, "lirp_kafka_outbox")
    dropTableIfExists(dataSource, "lirp_kafka_dead_letter")
}

/** `DROP TABLE IF EXISTS` is supported by all five target dialects. */
private fun dropTableIfExists(dataSource: HikariDataSource, table: String) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DROP TABLE IF EXISTS $table").use { it.execute() }
    }
}

/** Counts outbox rows whose `sent_at` is null (pending delivery). */
private fun countUnsentOutboxRows(dataSource: HikariDataSource): Long =
    queryLong(dataSource, "SELECT COUNT(*) FROM lirp_kafka_outbox WHERE sent_at IS NULL")

/** Counts all outbox rows, sent or pending. */
private fun countOutboxRows(dataSource: HikariDataSource): Long =
    queryLong(dataSource, "SELECT COUNT(*) FROM lirp_kafka_outbox")

/** Counts all rows in the dead-letter table. */
private fun countDeadLetterRows(dataSource: HikariDataSource): Long =
    queryLong(dataSource, "SELECT COUNT(*) FROM lirp_kafka_dead_letter")

private fun queryLong(dataSource: HikariDataSource, sql: String): Long =
    dataSource.connection.use { conn ->
        conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

/** Reads the id of the first outbox row, failing if the table is empty. */
private fun firstOutboxRowId(dataSource: HikariDataSource): String =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT id FROM lirp_kafka_outbox LIMIT 1").use { stmt ->
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "Expected an outbox row but found none" }
                rs.getString("id")
            }
        }
    }

/**
 * Reads the first dead-letter row as a name-to-value map, or `null` when the table is empty.
 */
private fun readFirstDeadLetterRow(dataSource: HikariDataSource): Map<String, Any?>? =
    dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT aggregate_type, attempt_count, last_error, failed_at FROM lirp_kafka_dead_letter LIMIT 1"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    mapOf(
                        "aggregate_type" to rs.getString("aggregate_type"),
                        "attempt_count" to rs.getInt("attempt_count"),
                        "last_error" to rs.getString("last_error"),
                        "failed_at" to rs.getTimestamp("failed_at")
                    )
                }
            }
        }
    }

/**
 * Resets `sent_at = NULL` for the outbox row with the given [aggregateId] via raw JDBC.
 *
 * This simulates a crash rollback: the relay had published the record to Kafka but the
 * transaction containing the `sent_at` UPDATE was never committed, so the row remains pending
 * and the next relay run redelivers it (at-least-once guarantee).
 */
private fun resetSentAt(dataSource: HikariDataSource, aggregateId: String) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("UPDATE lirp_kafka_outbox SET sent_at = NULL WHERE aggregate_id = ?")
            .use { stmt ->
                stmt.setString(1, aggregateId)
                stmt.executeUpdate()
            }
    }
}

/**
 * Inserts [count] raw outbox rows for [aggregateType]/[aggregateId] via raw JDBC.
 *
 * Each row gets a unique UUID, CREATE event code (100), a distinguishable `{"seq":i}` payload, and a
 * strictly increasing `created_at` (base + i ms). The distinct payloads and monotonic timestamps let
 * a consumer assert the relay published rows in creation order, rather than merely observing that
 * single-partition offsets increase (which they always do).
 */
private fun insertRawOutboxRows(dataSource: HikariDataSource, aggregateType: String, aggregateId: String, count: Int) {
    val baseCreatedAt = java.time.Instant.now()
    dataSource.connection.use { conn ->
        repeat(count) { i ->
            conn.prepareStatement(
                "INSERT INTO lirp_kafka_outbox " +
                    "(id, aggregate_type, aggregate_id, event_type_code, payload, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setObject(1, java.util.UUID.randomUUID())
                stmt.setString(2, aggregateType)
                stmt.setString(3, aggregateId)
                stmt.setInt(4, 100)
                stmt.setString(5, """{"seq":$i}""")
                stmt.setTimestamp(6, java.sql.Timestamp.from(baseCreatedAt.plusMillis(i.toLong())))
                stmt.executeUpdate()
            }
        }
    }
}

/** Inserts a single raw outbox row for [aggregateType]/[aggregateId] via raw JDBC. */
private fun insertRawOutboxRow(dataSource: HikariDataSource, aggregateType: String, aggregateId: String) {
    insertRawOutboxRows(dataSource, aggregateType, aggregateId, 1)
}

/** [KafkaOutboxConfig] tuned for fast test turnaround. */
private fun fastRelayConfig() =
    KafkaOutboxConfig(
        pollIntervalMs = 50L,
        batchSize = 100,
        maxRetries = 3,
        retryBaseDelayMs = 50L,
        retryMaxDelayMs = 200L
    )
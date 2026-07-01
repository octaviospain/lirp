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

import net.transgressoft.lirp.kafka.KafkaContainerSupport
import net.transgressoft.lirp.kafka.KafkaOutboxConfig
import net.transgressoft.lirp.kafka.KafkaOutboxSqlRepository
import net.transgressoft.lirp.kafka.LirpKafkaConfig
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

/**
 * Testcontainers integration tests for the outbox relay end-to-end path.
 *
 * Verifies the relay drains unsent rows across real SQL dialects and a real Kafka broker:
 * rows are published as Kafka records and their `sent_at` timestamp is set atomically in the
 * same transaction. Five scenarios are covered:
 *
 * 1. **Normal drain**: relay publishes all unsent rows to Kafka and sets `sent_at`.
 * 2. **Crash/redelivery**: a row whose `sent_at` was never committed (simulating a mid-transaction
 *    crash) is redelivered on the next relay start — proving at-least-once delivery.
 * 3. **Concurrent no-double-publish**: two relay instances against PostgreSQL (which enforces
 *    `FOR UPDATE SKIP LOCKED`) publish each row exactly once.
 * 4. **Per-aggregate ordering**: rapid sequential mutations to the same aggregate id arrive at
 *    the consumer in creation order, proving that `aggregateId` as record key + a multi-partition
 *    topic keeps per-aggregate ordering.
 * 5. **Dead-letter routing**: a row whose topic name is Kafka-invalid is moved atomically to the
 *    dead-letter table and the relay continues processing sibling rows.
 *
 * Each test case drops and recreates the outbox/dead-letter tables for isolation so the relay's
 * [SchemaUtils.create] call recreates the schema fresh.
 */
@DisplayName("OutboxRelayIT")
internal class OutboxRelayIT : FunSpec({

    afterEach {
        RegistryBase.deregisterRepository(AudioItem::class.java)
    }

    // -----------------------------------------------------------------------------------------
    // Test 1: Normal relay path
    // -----------------------------------------------------------------------------------------

    test("OutboxRelayIT normal relay path drains all unsent rows") {
        DatabaseTestSupport.withDatabaseTest(
            DatabaseTestSupport.databases.first { it.name == "PostgreSQL" },
            AudioItemSqlTableDef
        ) { dataSource ->
            dropOutboxTableIfExists(dataSource)
            dropDeadLetterTableIfExists(dataSource)

            val topic = "${AudioItemSqlTableDef.tableName}.events"

            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            val lirpConfig = LirpKafkaConfig.create(KafkaContainerSupport.bootstrapServers)
            try {
                transaction(repo) { r ->
                    r.add(MutableAudioItem(1, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem)
                    r.add(MutableAudioItem(2, "Killer Queen", "Sheer Heart Attack") as AudioItem)
                    r.add(MutableAudioItem(3, "We Will Rock You", "News of the World") as AudioItem)
                }

                try {
                    KafkaConsumer<String, ByteArray>(consumerProps("relay-normal-drain-${System.currentTimeMillis()}")).use { consumer ->
                        awaitConsumerAssignment(consumer, listOf(topic))
                        lirpConfig.startRelay(dataSource, fastRelayConfig())
                        try {
                            val receivedKeys = mutableListOf<String>()
                            eventually(20.seconds) {
                                consumer.poll(Duration.ofMillis(200)).forEach { receivedKeys.add(it.key()) }
                                receivedKeys.size shouldBe 3
                            }
                            receivedKeys.toSet() shouldBe setOf("1", "2", "3")
                            eventually(10.seconds) {
                                countUnsentOutboxRows(dataSource) shouldBe 0L
                            }
                        } finally {
                            lirpConfig.stopRelay()
                        }
                    }
                } finally {
                    // no-op: consumer.use handles close
                }
            } finally {
                repo.close()
                lirpConfig.close()
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Test 2: Crash between publish and mark-sent causes redelivery
    // -----------------------------------------------------------------------------------------

    test("OutboxRelayIT crash between publish and mark-sent causes redelivery") {
        DatabaseTestSupport.withDatabaseTest(
            DatabaseTestSupport.databases.first { it.name == "PostgreSQL" },
            AudioItemSqlTableDef
        ) { dataSource ->
            dropOutboxTableIfExists(dataSource)
            dropDeadLetterTableIfExists(dataSource)

            val topic = "${AudioItemSqlTableDef.tableName}.events"

            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            try {
                transaction(repo) { r ->
                    r.add(MutableAudioItem(10, "Radio Ga Ga", "The Works") as AudioItem)
                }

                // First relay run: relay publishes the record to Kafka and sets sent_at.
                val lirpConfig1 = LirpKafkaConfig.create(KafkaContainerSupport.bootstrapServers)
                lirpConfig1.startRelay(dataSource, fastRelayConfig())
                try {
                    eventually(15.seconds) {
                        countUnsentOutboxRows(dataSource) shouldBe 0L
                    }
                } finally {
                    lirpConfig1.close()
                }

                // Simulate crash: reset sent_at back to NULL. This models a scenario where the relay
                // published to Kafka but the transaction containing the sent_at UPDATE was rolled back
                // before committing (e.g. a JVM crash or JDBC connection failure mid-transaction).
                resetSentAt(dataSource, "10")
                countUnsentOutboxRows(dataSource) shouldBe 1L

                // Second relay run (crash recovery): subscribe consumer FIRST (latest offset),
                // then start the relay. The redelivered record for key "10" must appear.
                val lirpConfig2 = LirpKafkaConfig.create(KafkaContainerSupport.bootstrapServers)
                KafkaConsumer<String, ByteArray>(consumerProps("relay-crash-recovery-${System.currentTimeMillis()}")).use { consumer ->
                    awaitConsumerAssignment(consumer, listOf(topic))
                    lirpConfig2.startRelay(dataSource, fastRelayConfig())
                    try {
                        val receivedKeys = mutableListOf<String>()
                        eventually(20.seconds) {
                            consumer.poll(Duration.ofMillis(200)).forEach { receivedKeys.add(it.key()) }
                            receivedKeys.count { it == "10" } shouldNotBe 0
                        }
                        eventually(10.seconds) {
                            countUnsentOutboxRows(dataSource) shouldBe 0L
                        }
                    } finally {
                        lirpConfig2.close()
                    }
                }
            } finally {
                repo.close()
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Test 3: Concurrent relay instances do not double-publish (SKIP LOCKED, PostgreSQL only)
    // -----------------------------------------------------------------------------------------

    test("OutboxRelayIT concurrent relay instances do not double-publish") {
        // FOR UPDATE SKIP LOCKED is only enforced on PostgreSQL/MySQL/MariaDB; H2 gives a false
        // green because its SELECT does not honour the SKIP LOCKED clause.
        val pgDataSource = PostgresContainerSupport.buildDataSource()
        try {
            dropOutboxTableIfExists(pgDataSource)
            dropDeadLetterTableIfExists(pgDataSource)

            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(pgDataSource, AudioItemSqlTableDef)
            try {
                val n = 6
                transaction(repo) { r ->
                    repeat(n) { i -> r.add(MutableAudioItem(i + 100, "Track ${i + 1}", "Album A") as AudioItem) }
                }

                val topic = "${AudioItemSqlTableDef.tableName}.events"

                val lirpConfig1 = LirpKafkaConfig.create(KafkaContainerSupport.bootstrapServers)
                val lirpConfig2 = LirpKafkaConfig.create(KafkaContainerSupport.bootstrapServers)

                KafkaConsumer<String, ByteArray>(consumerProps("relay-concurrent-${System.currentTimeMillis()}")).use { consumer ->
                    awaitConsumerAssignment(consumer, listOf(topic))
                    lirpConfig1.startRelay(pgDataSource, fastRelayConfig())
                    lirpConfig2.startRelay(pgDataSource, fastRelayConfig())
                    try {
                        val receivedKeys = mutableListOf<String>()
                        eventually(30.seconds) {
                            consumer.poll(Duration.ofMillis(200)).forEach { receivedKeys.add(it.key()) }
                            receivedKeys.size shouldBe n
                        }
                        // Each aggregate id must appear exactly once — SKIP LOCKED ensures no double-publish.
                        receivedKeys.groupBy { it }.values.forEach { group ->
                            group.size shouldBe 1
                        }
                    } finally {
                        lirpConfig1.close()
                        lirpConfig2.close()
                    }
                }
            } finally {
                repo.close()
            }
        } finally {
            pgDataSource.close()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Test 4: Multi-partition topic preserves per-aggregate ordering
    // -----------------------------------------------------------------------------------------

    test("OutboxRelayIT multi-partition topic preserves per-aggregate ordering") {
        DatabaseTestSupport.withDatabaseTest(
            DatabaseTestSupport.databases.first { it.name == "PostgreSQL" },
            AudioItemSqlTableDef
        ) { dataSource ->
            dropOutboxTableIfExists(dataSource)
            dropDeadLetterTableIfExists(dataSource)

            val aggregateType = AudioItemSqlTableDef.tableName
            val topic = "$aggregateType.events"
            // Pre-create the topic with 3 partitions. The record key (aggregate id) routes all
            // mutations for a single aggregate to the same partition, preserving order.
            createMultiPartitionTopic(topic, 3)

            val aggregateId = "42"
            val eventCount = 5

            // Use the repository to ensure the outbox table exists, then insert additional raw rows
            // for the same aggregate id to simulate rapid sequential mutations.
            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            try {
                insertRawOutboxRows(dataSource, aggregateType, aggregateId, eventCount)

                val lirpConfig = LirpKafkaConfig.create(KafkaContainerSupport.bootstrapServers)
                KafkaConsumer<String, ByteArray>(consumerProps("relay-ordering-${System.currentTimeMillis()}")).use { consumer ->
                    awaitConsumerAssignment(consumer, listOf(topic))
                    lirpConfig.startRelay(dataSource, fastRelayConfig())
                    try {
                        val receivedOffsets = mutableListOf<Long>()
                        eventually(20.seconds) {
                            consumer.poll(Duration.ofMillis(200))
                                .filter { it.key() == aggregateId }
                                .forEach { receivedOffsets.add(it.offset()) }
                            receivedOffsets.size shouldBe eventCount
                        }
                        // All records for the same key land on one partition so offsets are
                        // monotonically increasing — proving per-aggregate in-order delivery.
                        receivedOffsets shouldBe receivedOffsets.sorted()
                    } finally {
                        lirpConfig.close()
                    }
                }
            } finally {
                repo.close()
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Test 5: Non-retriable error moves row to dead-letter table and relay continues
    // -----------------------------------------------------------------------------------------

    test("OutboxRelayIT non-retriable error moves row to dead letter table and relay continues") {
        DatabaseTestSupport.withDatabaseTest(
            DatabaseTestSupport.databases.first { it.name == "PostgreSQL" },
            AudioItemSqlTableDef
        ) { dataSource ->
            dropOutboxTableIfExists(dataSource)
            dropDeadLetterTableIfExists(dataSource)

            val aggregateType = AudioItemSqlTableDef.tableName
            val validTopic = "$aggregateType.events"

            // maxRetries=1: new rows (retryCount=0) attempt publishing once; a non-retriable Kafka
            // exception (e.g. InvalidTopicException from an invalid topic name) is caught and the
            // row is moved to the dead-letter table in the same transaction.
            val relayConfig =
                KafkaOutboxConfig(
                    pollIntervalMs = 50L,
                    batchSize = 10,
                    maxRetries = 1,
                    retryBaseDelayMs = 50L,
                    retryMaxDelayMs = 100L
                )

            // Use the repository to ensure the outbox table exists (its init creates the schema).
            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            try {
                // Insert a poison row: aggregate_type contains a '#' character which produces a Kafka-
                // invalid topic name ("invalid#topic.events"). Kafka's topic naming rules require names
                // to match [a-zA-Z0-9._-]+; '#' is rejected with InvalidTopicException (a KafkaException
                // subclass, not RetriableException) so the relay moves this row to the dead-letter table.
                val poisonAggregateType = "invalid#topic"
                insertRawOutboxRow(dataSource, poisonAggregateType, "poison-id-1")

                // Insert a valid sibling row that the relay must still publish after the dead-letter move.
                transaction(repo) { r ->
                    r.add(MutableAudioItem(99, "Another One Bites the Dust", "The Game") as AudioItem)
                }

                val lirpConfig = LirpKafkaConfig.create(KafkaContainerSupport.bootstrapServers)
                // Valid sibling row must be published to Kafka
                KafkaConsumer<String, ByteArray>(consumerProps("relay-dead-letter-${System.currentTimeMillis()}")).use { consumer ->
                    awaitConsumerAssignment(consumer, listOf(validTopic))
                    lirpConfig.startRelay(dataSource, relayConfig)
                    try {
                        val receivedKeys = mutableListOf<String>()
                        eventually(20.seconds) {
                            consumer.poll(Duration.ofMillis(200)).forEach { receivedKeys.add(it.key()) }
                            receivedKeys.size shouldBe 1
                        }
                        receivedKeys.first() shouldBe "99"

                        // Poison row must be in the dead-letter table with full failure metadata
                        eventually(10.seconds) {
                            countDeadLetterRows(dataSource) shouldBe 1L
                        }
                        val dltRow = readFirstDeadLetterRow(dataSource)
                        dltRow shouldNotBe null
                        dltRow!!["aggregate_type"] shouldBe poisonAggregateType
                        (dltRow["attempt_count"] as Number).toInt() shouldBe 1
                        dltRow["last_error"] shouldNotBe null
                        dltRow["failed_at"] shouldNotBe null

                        // Outbox must be empty — poison row removed, valid row sent
                        countUnsentOutboxRows(dataSource) shouldBe 0L
                    } finally {
                        lirpConfig.close()
                    }
                }
            } finally {
                repo.close()
            }
        }
    }
})

// -------------------------------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------------------------------

/** [KafkaOutboxConfig] tuned for fast test turnaround. */
private fun fastRelayConfig() =
    KafkaOutboxConfig(
        pollIntervalMs = 50L,
        batchSize = 100,
        maxRetries = 3,
        retryBaseDelayMs = 50L,
        retryMaxDelayMs = 200L
    )

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
 * Pre-creates a Kafka topic with [partitions] partitions via [AdminClient].
 * A no-op when the topic already exists.
 */
private fun createMultiPartitionTopic(topic: String, partitions: Int) {
    val adminProps =
        Properties().apply {
            put("bootstrap.servers", KafkaContainerSupport.bootstrapServers)
        }
    AdminClient.create(adminProps).use { admin ->
        try {
            admin.createTopics(listOf(NewTopic(topic, partitions, 1.toShort()))).all().get()
        } catch (_: Exception) {
            // Topic may already exist — proceed without failure
        }
    }
}

/**
 * Drops the `lirp_kafka_outbox` table if it exists.
 * `DROP TABLE IF EXISTS` is supported by all five target dialects.
 */
private fun dropOutboxTableIfExists(dataSource: HikariDataSource) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DROP TABLE IF EXISTS lirp_kafka_outbox").use { it.execute() }
    }
}

/**
 * Drops the `lirp_kafka_dead_letter` table if it exists.
 */
private fun dropDeadLetterTableIfExists(dataSource: HikariDataSource) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DROP TABLE IF EXISTS lirp_kafka_dead_letter").use { it.execute() }
    }
}

/** Counts outbox rows whose `sent_at` is null (pending delivery). */
private fun countUnsentOutboxRows(dataSource: HikariDataSource): Long =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM lirp_kafka_outbox WHERE sent_at IS NULL")
            .use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
    }

/** Counts all rows in the dead-letter table. */
private fun countDeadLetterRows(dataSource: HikariDataSource): Long =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM lirp_kafka_dead_letter")
            .use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
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
                if (!rs.next()) null
                else mapOf(
                    "aggregate_type" to rs.getString("aggregate_type"),
                    "attempt_count" to rs.getInt("attempt_count"),
                    "last_error" to rs.getString("last_error"),
                    "failed_at" to rs.getTimestamp("failed_at")
                )
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
 * Each row gets a unique UUID, CREATE event code (100), and an empty JSON payload.
 */
private fun insertRawOutboxRows(dataSource: HikariDataSource, aggregateType: String, aggregateId: String, count: Int) {
    dataSource.connection.use { conn ->
        repeat(count) {
            conn.prepareStatement(
                "INSERT INTO lirp_kafka_outbox " +
                    "(id, aggregate_type, aggregate_id, event_type_code, payload, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
            ).use { stmt ->
                stmt.setObject(1, java.util.UUID.randomUUID())
                stmt.setString(2, aggregateType)
                stmt.setString(3, aggregateId)
                stmt.setInt(4, 100)
                stmt.setString(5, "{}")
                stmt.executeUpdate()
            }
        }
    }
}

/** Inserts a single raw outbox row for [aggregateType]/[aggregateId] via raw JDBC. */
private fun insertRawOutboxRow(dataSource: HikariDataSource, aggregateType: String, aggregateId: String) {
    insertRawOutboxRows(dataSource, aggregateType, aggregateId, 1)
}
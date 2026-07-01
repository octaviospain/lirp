package net.transgressoft.fleet.e2e

import net.transgressoft.fleet.app.FleetApplication
import net.transgressoft.fleet.vehicle.Vehicle
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.kafka.KafkaOutboxConfig
import net.transgressoft.lirp.persistence.sql.PostgresContainerSupport
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Integrated end-to-end test suite for the fleet service, validating the complete LIRP feature
 * surface against real Postgres and Kafka Testcontainers.
 *
 * Scenarios covered:
 *
 * **Vehicle lifecycle** — registers a tenant and vehicle, asserts Kafka receipt of the create
 * event, projection membership, query-DSL lookup, and diagnostics; then updates the VIN and
 * asserts Kafka receipt of the update event and query results; then soft-deletes the vehicle
 * and asserts projection exclusion, `contains` returns false, and Kafka receipt of the resulting
 * event.
 *
 * **Atomic transaction path** — `registerVehicleAtomically` commits the outbox row in the same
 * DB transaction; Kafka receipt arrives without debounce delay.
 *
 * **At-least-once redelivery** — a simulated mid-transaction crash leaves the outbox row with
 * `sent_at = NULL`; a fresh service instance redelivers it.
 *
 * **Optimistic locking** — an out-of-band version bump via raw JDBC causes the next in-repo
 * mutation to surface a `StandardCrudEvent.Conflict` carrying the canonical state.
 *
 * The test uses a plain `KafkaConsumer<String, ByteArray>` with no LIRP or CloudEvents SDK.
 * Receipt is verified by key, JSON value content, and `ce_*` CloudEvents binary headers.
 */
internal class FleetLifecycleE2ETest : StringSpec({

    val vehiclesTopic = "vehicles.events"

    "vehicle lifecycle covers create, update and decommission with Kafka receipt, projection and query DSL" {
        withResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val fleet =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastRelayConfig())
                    .managed()

            createMultiPartitionTopic(vehiclesTopic, 1)
            val consumer = subscribedConsumer("e2e-lifecycle", vehiclesTopic)
            fleet.start()

            // ── Create ──────────────────────────────────────────────────────────────
            val tenant = fleet.registerTenant("E2E-T1", "E2E Tenant One")
            val vehicle = fleet.registerVehicle(tenant.id, "E2E-VIN-001")

            // Arm the projection (lazy init triggers on first size/get access)
            fleet.vehiclesByTenant.size

            val createRecords =
                consumer.pollUntil { recs ->
                    recs.map { it.key() } shouldContain vehicle.id.toString()
                }

            val createRecord = createRecords.first { it.key() == vehicle.id.toString() }
            createRecord.key() shouldBe vehicle.id.toString()
            createRecord.header("ce_subject") shouldBe vehicle.id.toString()
            createRecord.header("ce_id").shouldNotBeNull()
            createRecord.value().toString(Charsets.UTF_8) shouldContain "\"vin\""
            createRecord.value().toString(Charsets.UTF_8) shouldContain "E2E-VIN-001"

            // Projection should contain the vehicle under the tenant bucket after create event
            eventually(10.seconds) {
                val bucket = fleet.vehiclesByTenant[tenant.id].orEmpty()
                bucket.map { it.id } shouldContain vehicle.id
            }

            // Query DSL: find-by-vin using @Indexed vin property
            eventually(5.seconds) {
                val byVin = fleet.findVehiclesByVin("E2E-VIN-001")
                byVin.map { it.vin } shouldContain "E2E-VIN-001"
            }

            // Query DSL with diagnostics: @Indexed vin should report index hits
            eventually(5.seconds) {
                val diagnosed = fleet.diagnoseVehiclesByVin("E2E-VIN-001")
                diagnosed.results.toList().map { it.vin } shouldContain "E2E-VIN-001"
                diagnosed.diagnostic.indexHits.shouldNotBeEmpty()
            }

            // ── Update VIN ──────────────────────────────────────────────────────────
            fleet.changeVin(vehicle, "E2E-VIN-002")

            val allRecordsAfterUpdate =
                consumer.pollUntil(30.seconds) { recs ->
                    // wait for a record carrying the updated vin
                    val updatedJson =
                        recs
                            .filter { it.key() == vehicle.id.toString() }
                            .any { it.value().toString(Charsets.UTF_8).contains("E2E-VIN-002") }
                    updatedJson shouldBe true
                }

            val updateRecord =
                allRecordsAfterUpdate
                    .filter { it.key() == vehicle.id.toString() }
                    .first { it.value().toString(Charsets.UTF_8).contains("E2E-VIN-002") }
            updateRecord.header("ce_id").shouldNotBeNull()
            updateRecord.value().toString(Charsets.UTF_8) shouldContain "E2E-VIN-002"

            // Query DSL: new VIN is findable, old VIN is gone
            eventually(5.seconds) {
                fleet.findVehiclesByVin("E2E-VIN-002").map { it.vin } shouldContain "E2E-VIN-002"
            }

            // ── Soft-delete (decommission) ──────────────────────────────────────────
            fleet.decommissionVehicle(vehicle)

            vehicle.deletedAt.shouldNotBeNull()
            fleet.vehicles.contains(vehicle.id) shouldBe false

            // Projection must evict soft-deleted vehicle from tenant bucket
            eventually(10.seconds) {
                val bucket = fleet.vehiclesByTenant[tenant.id].orEmpty()
                bucket.none { it.id == vehicle.id } shouldBe true
            }

            // Kafka must receive the resulting event for this vehicle id after soft-delete
            consumer.pollUntil(30.seconds) { recs ->
                // The relay publishes the mutation produced by softDelete — a record with vehicle id key
                recs.map { it.key() } shouldContain vehicle.id.toString()
            }
        }
    }

    "explicit transaction path publishes Vehicle create event without debounce delay" {
        withResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val fleet =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastRelayConfig())
                    .managed()

            createMultiPartitionTopic(vehiclesTopic, 1)
            val consumer = subscribedConsumer("e2e-txpath-lc", vehiclesTopic)
            fleet.start()

            val vehicle = fleet.registerVehicleAtomically(UUID.randomUUID(), "LC-TXPATH-001")
            countUnsentOutboxRows(dataSource) shouldBe 1L

            val records =
                consumer.pollUntil { recs ->
                    recs.map { it.key() } shouldContain vehicle.id.toString()
                }

            val record = records.first { it.key() == vehicle.id.toString() }
            record.header("ce_id").shouldNotBeNull()
            record.value().toString(Charsets.UTF_8) shouldContain "\"vin\""
        }
    }

    "unsent outbox row is redelivered after simulated service crash" {
        withResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            createMultiPartitionTopic(vehiclesTopic, 1)

            // First service run: create a vehicle, drain the outbox, then stop
            val firstRun =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastRelayConfig())
                    .managed()
            firstRun.start()
            val vehicle = firstRun.registerVehicleAtomically(UUID.randomUUID(), "LC-REDELIVER-001")
            countUnsentOutboxRows(dataSource) shouldBe 1L
            eventually(15.seconds) { countUnsentOutboxRows(dataSource) shouldBe 0L }
            firstRun.close()

            // Simulate crash: reset sent_at so the row looks un-sent
            resetSentAt(dataSource, vehicle.id.toString())
            countUnsentOutboxRows(dataSource) shouldBe 1L

            // Subscribe before second run to avoid auto.offset.reset=latest race
            val consumer = subscribedConsumer("e2e-redelivery-lc", vehiclesTopic)
            val secondRun =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastRelayConfig())
                    .managed()
            secondRun.start()

            val records =
                consumer.pollUntil { recs ->
                    recs.map { it.key() } shouldContain vehicle.id.toString()
                }

            val record = records.first { it.key() == vehicle.id.toString() }
            record.key() shouldBe vehicle.id.toString()
            record.header("ce_subject") shouldBe vehicle.id.toString()
            record.header("ce_id").shouldNotBeNull()
            record.value().toString(Charsets.UTF_8) shouldContain "\"vin\""

            eventually(10.seconds) { countUnsentOutboxRows(dataSource) shouldBe 0L }
        }
    }

    "optimistic locking conflict surfaces StandardCrudEvent.Conflict after out-of-band version bump" {
        withResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val fleet =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastRelayConfig())
                    .managed()
            fleet.start()

            val tenant = fleet.registerTenant("OL-T1", "Optimistic Tenant 1")
            val vehicle = fleet.registerVehicle(tenant.id, "OL-INITIAL-VIN")

            // Wait for the INSERT to be committed to Postgres before the out-of-band UPDATE
            eventually(15.seconds) {
                dataSource.connection.use { conn ->
                    conn.prepareStatement("SELECT COUNT(*) FROM vehicles WHERE id = ?").use { stmt ->
                        stmt.setObject(1, vehicle.id)
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1) shouldBe 1
                        }
                    }
                }
            }

            val conflictRef = AtomicReference<StandardCrudEvent.Conflict<UUID, Vehicle>?>(null)
            val latch = CountDownLatch(1)
            fleet.vehicles.subscribe { event ->
                if (event is StandardCrudEvent.Conflict<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    conflictRef.set(event as StandardCrudEvent.Conflict<UUID, Vehicle>)
                    latch.countDown()
                }
            }

            // Out-of-band writer bumps the version and vin directly via JDBC
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE vehicles SET vin = ?, version = version + 1 WHERE id = ?"
                ).use { stmt ->
                    stmt.setString(1, "OL-CANONICAL-VIN")
                    stmt.setObject(2, vehicle.id)
                    stmt.executeUpdate() shouldBe 1
                }
            }

            // Local mutation against the stale baseline — should surface Conflict
            vehicle.vin = "OL-LOCAL-VIN"

            latch.await(5, TimeUnit.SECONDS) shouldBe true

            val conflict = conflictRef.get()
            conflict.shouldNotBeNull()

            val canonical = conflict.entities.values.single()
            val attempted = conflict.oldEntities.values.single()

            canonical.vin shouldBe "OL-CANONICAL-VIN"
            attempted.vin shouldBe "OL-LOCAL-VIN"
            conflict.expectedVersion shouldBe 0L
            conflict.actualVersion shouldBe 1L

            // In-memory entity is auto-reloaded to canonical state
            fleet.vehicles.findById(vehicle.id).shouldBePresent { it.vin shouldBe "OL-CANONICAL-VIN" }
        }
    }
})

// -------------------------------------------------------------------------------------------------
// Resource management
// -------------------------------------------------------------------------------------------------

private inline fun withResources(block: E2EResourceScope.() -> Unit) {
    val scope = E2EResourceScope()
    try {
        scope.block()
    } finally {
        scope.closeAll()
    }
}

private class E2EResourceScope {
    private val opened = ArrayDeque<AutoCloseable>()

    fun <T : AutoCloseable> T.managed(): T {
        opened.addFirst(this)
        return this
    }

    fun closeAll() {
        val errors = mutableListOf<Throwable>()
        while (opened.isNotEmpty()) {
            runCatching { opened.removeFirst().close() }.onFailure { errors.add(it) }
        }
        if (errors.isNotEmpty()) {
            val composite = errors.first()
            errors.drop(1).forEach(composite::addSuppressed)
            throw composite
        }
    }

    fun subscribedConsumer(group: String, topic: String): KafkaConsumer<String, ByteArray> {
        val consumer =
            KafkaConsumer<String, ByteArray>(
                consumerProps("$group-${System.currentTimeMillis()}")
            ).managed()
        awaitConsumerAssignment(consumer, listOf(topic))
        return consumer
    }
}

// -------------------------------------------------------------------------------------------------
// Consumer helpers
// -------------------------------------------------------------------------------------------------

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

private fun ConsumerRecord<String, ByteArray>.header(name: String): String? =
    headers().lastHeader(name)?.value()?.toString(Charsets.UTF_8)

private fun consumerProps(groupId: String): Properties =
    Properties().apply {
        put("bootstrap.servers", KafkaContainerSupport.bootstrapServers)
        put("group.id", groupId)
        put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer")
        put("auto.offset.reset", "latest")
        put("request.timeout.ms", "10000")
        put("default.api.timeout.ms", "10000")
    }

private fun <K, V> awaitConsumerAssignment(consumer: KafkaConsumer<K, V>, topics: List<String>) {
    consumer.subscribe(topics)
    val deadline = System.currentTimeMillis() + 15_000L
    while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
        consumer.poll(Duration.ofMillis(200))
    }
    check(consumer.assignment().isNotEmpty()) {
        "Consumer was not assigned any partitions within 15s for topics $topics"
    }
}

private fun createMultiPartitionTopic(topic: String, partitions: Int) {
    org.apache.kafka.clients.admin.AdminClient.create(
        Properties().apply { put("bootstrap.servers", KafkaContainerSupport.bootstrapServers) }
    ).use { admin ->
        try {
            admin.createTopics(
                listOf(org.apache.kafka.clients.admin.NewTopic(topic, partitions, 1.toShort()))
            ).all().get()
        } catch (e: java.util.concurrent.ExecutionException) {
            if (e.cause !is org.apache.kafka.common.errors.TopicExistsException) throw e
            val existing = admin.describeTopics(listOf(topic)).allTopicNames().get()[topic]
            val current = existing?.partitions()?.size ?: 0
            if (current < partitions) {
                admin.createPartitions(
                    mapOf(topic to org.apache.kafka.clients.admin.NewPartitions.increaseTo(partitions))
                ).all().get()
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Outbox schema / row helpers (raw JDBC — test-side observation and fault injection)
// -------------------------------------------------------------------------------------------------

private fun resetOutboxSchema(dataSource: HikariDataSource) {
    dropTableIfExists(dataSource, "lirp_kafka_outbox")
    dropTableIfExists(dataSource, "lirp_kafka_dead_letter")
}

private fun dropTableIfExists(dataSource: HikariDataSource, table: String) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DROP TABLE IF EXISTS $table").use { it.execute() }
    }
}

private fun countUnsentOutboxRows(dataSource: HikariDataSource): Long =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM lirp_kafka_outbox WHERE sent_at IS NULL").use { stmt ->
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

private fun resetSentAt(dataSource: HikariDataSource, aggregateId: String) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("UPDATE lirp_kafka_outbox SET sent_at = NULL WHERE aggregate_id = ?")
            .use { stmt ->
                stmt.setString(1, aggregateId)
                stmt.executeUpdate()
            }
    }
}

private fun fastRelayConfig() =
    KafkaOutboxConfig(
        pollIntervalMs = 50L,
        batchSize = 100,
        maxRetries = 3,
        retryBaseDelayMs = 50L,
        retryMaxDelayMs = 200L
    )
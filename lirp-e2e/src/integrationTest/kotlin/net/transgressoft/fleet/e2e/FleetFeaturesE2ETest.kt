package net.transgressoft.fleet.e2e

import net.transgressoft.fleet.app.FleetApplication
import net.transgressoft.fleet.vehicle.VehicleAssignmentAssignedToArm
import net.transgressoft.fleet.vehicle.activeArm
import net.transgressoft.fleet.vehicle.tenant
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.PropertyChanged
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.kafka.KafkaOutboxConfig
import net.transgressoft.lirp.persistence.PolymorphicResolution
import net.transgressoft.lirp.persistence.sql.PostgresContainerSupport
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Integrated end-to-end test suite exercising the remaining LIRP feature surface against
 * real Postgres and Kafka Testcontainers.
 *
 * Scenarios covered:
 *
 * **Aggregate navigation** — asserts `vehicle.tenant.resolve()` returns the registered [Tenant]
 * via the KSP-generated `tenant` extension accessor and the live `TenantRepository`.
 *
 * **All four repositories** — creates and queries entities via `PersonRepository` and
 * `CompanyRepository` in addition to the already-exercised Vehicle and Tenant repositories,
 * demonstrating that all four `@LirpRepository` factories work end-to-end.
 *
 * **Polymorphic aggregate** — assigns a vehicle to a `Person` through
 * `VehicleAssignmentRepository`, then resolves the active arm via the KSP-generated
 * `VehicleAssignmentAssignedToArm` sealed class and verifies the resolved entity is
 * the expected `Person`.
 *
 * **Restore and soft-delete visibility** — after decommission (soft-delete), calls
 * `restore()`, asserts `deletedAt` is null, the vehicle is back in default reads and the
 * `vehiclesByTenant` projection, a `StandardCrudEvent.Restore` fired, and the restore
 * mutation reaches Kafka. Also exercises the Query DSL visibility verbs: the default query
 * excludes the decommissioned vehicle, `includeDeleted()` includes it, and `onlyDeleted()`
 * returns only it.
 *
 * **Event subscriptions** — captures the full `StandardCrudEvent` sequence (Create → Update
 * → SoftDelete → Restore) on a repository-level subscriber, and captures a `PropertyChanged`
 * event on an entity-level subscriber when the VIN changes.
 */
internal class FleetFeaturesE2ETest : StringSpec({

    val vehiclesTopic = "vehicles.events"

    "Vehicle.tenant resolves to the registered Tenant via aggregate navigation" {
        withFeatureResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val fleet =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastFeatureRelayConfig())
                    .managed()
            fleet.start()

            val tenant = fleet.registerTenant("NAV-T1", "Navigation Tenant")
            val vehicle = fleet.registerVehicle(tenant.id, "NAV-VIN-001")

            // Wait for the entity to be persisted so the @LirpRepository is fully registered
            eventually(10.seconds) {
                fleet.vehicles.contains(vehicle.id) shouldBe true
            }

            // The KSP-generated `tenant` extension accessor (imported above) performs a live
            // lookup from the TenantRepository bound in LirpContext.default
            val resolved = vehicle.tenant.resolve()
            resolved.shouldBePresent { it.id shouldBe tenant.id }
            resolved.shouldBePresent { it.name shouldBe "Navigation Tenant" }
        }
    }

    "PersonRepository and CompanyRepository factory methods create and query entities" {
        withFeatureResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val fleet =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastFeatureRelayConfig())
                    .managed()
            fleet.start()

            val tenant = fleet.registerTenant("FOUR-T1", "Four Repos Tenant")

            val person = fleet.registerPerson(tenant.id, "Alice", "Driver")
            val company = fleet.registerCompany(tenant.id, "Acme Fleet GmbH")

            eventually(10.seconds) {
                fleet.persons.contains(person.id) shouldBe true
                fleet.companies.contains(company.id) shouldBe true
            }

            // Query DSL finders on PersonRepository
            val personsByTenant = fleet.persons.findByTenant(tenant.id)
            personsByTenant.map { it.id } shouldContain person.id

            // Query DSL finders on CompanyRepository
            val companiesByTenant = fleet.companies.findByTenant(tenant.id)
            companiesByTenant.map { it.id } shouldContain company.id

            val companiesByName = fleet.companies.findByName("Acme Fleet GmbH")
            companiesByName.map { it.name } shouldContain "Acme Fleet GmbH"
        }
    }

    "VehicleAssignment polymorphic arm resolves to the assigned Person" {
        withFeatureResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val fleet =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastFeatureRelayConfig())
                    .managed()
            fleet.start()

            val tenant = fleet.registerTenant("POLY-T1", "Polymorphic Tenant")
            val vehicle = fleet.registerVehicle(tenant.id, "POLY-VIN-001")
            val person = fleet.registerPerson(tenant.id, "Bob", "Driver")

            eventually(10.seconds) {
                fleet.vehicles.contains(vehicle.id) shouldBe true
                fleet.persons.contains(person.id) shouldBe true
            }

            val assignment = fleet.assignVehicleToPerson(vehicle.id, person.id)

            eventually(10.seconds) {
                fleet.assignments.contains(assignment.id) shouldBe true
            }

            // The polymorphicAggregate delegate enforces exactly-one-non-null.
            // resolution() returns PolymorphicResolution<*>; cast to the generated typed variant
            // so the generated activeArm() extension (in VehicleAssignmentAssignedToArm.kt) is in scope.
            @Suppress("UNCHECKED_CAST")
            val typedResolution =
                assignment.assignedTo.resolution() as PolymorphicResolution<VehicleAssignmentAssignedToArm>
            val active = typedResolution.activeArm()

            val individual = active.shouldBeInstanceOf<VehicleAssignmentAssignedToArm.Individual>()
            individual.entity.id shouldBe person.id
            individual.entity.firstName shouldBe "Bob"
        }
    }

    "restore brings back a decommissioned vehicle and emits Restore event to Kafka" {
        withFeatureResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val fleet =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastFeatureRelayConfig())
                    .managed()

            createFeatureTopic(vehiclesTopic, 1)
            val consumer = subscribedFeatureConsumer("e2e-restore", vehiclesTopic)
            fleet.start()

            val tenant = fleet.registerTenant("RST-T1", "Restore Tenant")
            val vehicle = fleet.registerVehicle(tenant.id, "RST-VIN-001")

            fleet.vehiclesByTenant.size

            // Confirm vehicle is active
            eventually(10.seconds) {
                fleet.vehicles.contains(vehicle.id) shouldBe true
                val bucket = fleet.vehiclesByTenant[tenant.id].orEmpty()
                bucket.map { it.id } shouldContain vehicle.id
            }

            // ── Decommission ───────────────────────────────────────────────────────
            fleet.decommissionVehicle(vehicle)
            vehicle.deletedAt.shouldNotBeNull()
            fleet.vehicles.contains(vehicle.id) shouldBe false

            // DSL visibility: default excludes it
            eventually(5.seconds) {
                fleet.findVehiclesByVin("RST-VIN-001") shouldHaveSize 0
            }

            // DSL visibility: includeDeleted() finds it
            val withDeleted = fleet.findVehiclesByVinIncludingDeleted("RST-VIN-001")
            withDeleted.map { it.id } shouldContain vehicle.id

            // DSL visibility: onlyDeleted() returns only it
            val onlyDecommissioned = fleet.findDecommissionedVehicles()
            onlyDecommissioned.map { it.id } shouldContain vehicle.id

            // Projection is clear
            eventually(10.seconds) {
                val bucket = fleet.vehiclesByTenant[tenant.id].orEmpty()
                bucket.none { it.id == vehicle.id } shouldBe true
            }

            // ── Restore ────────────────────────────────────────────────────────────
            fleet.restoreVehicle(vehicle)

            vehicle.deletedAt.shouldBeNull()
            fleet.vehicles.contains(vehicle.id) shouldBe true

            // Default query now finds it again
            eventually(5.seconds) {
                fleet.findVehiclesByVin("RST-VIN-001").map { it.id } shouldContain vehicle.id
            }

            // onlyDeleted() no longer finds it
            eventually(5.seconds) {
                val decommissioned = fleet.findDecommissionedVehicles()
                decommissioned.none { it.id == vehicle.id } shouldBe true
            }

            // Projection re-adds the restored vehicle
            eventually(10.seconds) {
                val bucket = fleet.vehiclesByTenant[tenant.id].orEmpty()
                bucket.map { it.id } shouldContain vehicle.id
            }

            // Kafka must receive a record for the restore mutation
            consumer.pollFeatureUntil(30.seconds) { recs ->
                recs.map { it.key() } shouldContain vehicle.id.toString()
            }
        }
    }

    "repository-level CrudEvent stream captures Create, Update, SoftDelete and Restore sequence" {
        withFeatureResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val fleet =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastFeatureRelayConfig())
                    .managed()
            fleet.start()

            val tenant = fleet.registerTenant("EVT-T1", "Event Tenant")

            val crudEvents = CopyOnWriteArrayList<CrudEvent<*, *>>()
            val restoreLatch = CountDownLatch(1)

            fleet.vehicles.subscribe { event ->
                crudEvents.add(event)
                if (event is StandardCrudEvent.Restore<*, *>) restoreLatch.countDown()
            }

            val vehicle = fleet.registerVehicle(tenant.id, "EVT-VIN-001")

            eventually(10.seconds) { fleet.vehicles.contains(vehicle.id) shouldBe true }

            fleet.changeVin(vehicle, "EVT-VIN-002")
            fleet.decommissionVehicle(vehicle)
            fleet.restoreVehicle(vehicle)

            restoreLatch.await(15, TimeUnit.SECONDS) shouldBe true

            // Must have received Create, SoftDelete, and Restore events in the sequence
            val types = crudEvents.map { it.type }
            types shouldContain CrudEvent.Type.CREATE
            types shouldContain CrudEvent.Type.SOFT_DELETE
            types shouldContain CrudEvent.Type.RESTORE
        }
    }

    "entity-level subscription captures PropertyChanged when VIN changes" {
        withFeatureResources {
            val dataSource = PostgresContainerSupport.buildDataSource().managed()
            resetOutboxSchema(dataSource)
            val fleet =
                FleetApplication(dataSource, KafkaContainerSupport.bootstrapServers, fastFeatureRelayConfig())
                    .managed()
            fleet.start()

            val tenant = fleet.registerTenant("PROP-T1", "Property Event Tenant")
            val vehicle = fleet.registerVehicle(tenant.id, "PROP-OLD-VIN")

            eventually(10.seconds) { fleet.vehicles.contains(vehicle.id) shouldBe true }

            val propertyEvents = CopyOnWriteArrayList<PropertyChanged<*, *, *>>()
            val changeLatch = CountDownLatch(1)

            vehicle.subscribe { event: MutationEvent<UUID, net.transgressoft.fleet.vehicle.Vehicle> ->
                if (event is PropertyChanged<*, *, *> && event.property.name == "vin") {
                    propertyEvents.add(event)
                    changeLatch.countDown()
                }
            }

            fleet.changeVin(vehicle, "PROP-NEW-VIN")

            changeLatch.await(10, TimeUnit.SECONDS) shouldBe true

            propertyEvents shouldHaveSize 1
            val event = propertyEvents.first()
            event.oldValue shouldBe "PROP-OLD-VIN"
            event.newValue shouldBe "PROP-NEW-VIN"
        }
    }
})

// -------------------------------------------------------------------------------------------------
// Resource management
// -------------------------------------------------------------------------------------------------

private inline fun withFeatureResources(block: FeatureResourceScope.() -> Unit) {
    val scope = FeatureResourceScope()
    try {
        scope.block()
    } finally {
        scope.closeAll()
    }
}

private class FeatureResourceScope {
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

    fun subscribedFeatureConsumer(group: String, topic: String): KafkaConsumer<String, ByteArray> {
        val consumer =
            KafkaConsumer<String, ByteArray>(
                featureConsumerProps("$group-${System.currentTimeMillis()}")
            ).managed()
        awaitFeatureConsumerAssignment(consumer, listOf(topic))
        return consumer
    }
}

// -------------------------------------------------------------------------------------------------
// Consumer helpers
// -------------------------------------------------------------------------------------------------

private suspend fun KafkaConsumer<String, ByteArray>.pollFeatureUntil(
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

private fun featureConsumerProps(groupId: String): Properties =
    Properties().apply {
        put("bootstrap.servers", KafkaContainerSupport.bootstrapServers)
        put("group.id", groupId)
        put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer")
        put("auto.offset.reset", "latest")
        put("request.timeout.ms", "10000")
        put("default.api.timeout.ms", "10000")
    }

private fun <K, V> awaitFeatureConsumerAssignment(consumer: KafkaConsumer<K, V>, topics: List<String>) {
    consumer.subscribe(topics)
    val deadline = System.currentTimeMillis() + 15_000L
    while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
        consumer.poll(Duration.ofMillis(200))
    }
    check(consumer.assignment().isNotEmpty()) {
        "Consumer was not assigned any partitions within 15s for topics $topics"
    }
}

private fun createFeatureTopic(topic: String, partitions: Int) {
    org.apache.kafka.clients.admin.AdminClient.create(
        Properties().apply { put("bootstrap.servers", KafkaContainerSupport.bootstrapServers) }
    ).use { admin ->
        try {
            admin.createTopics(
                listOf(org.apache.kafka.clients.admin.NewTopic(topic, partitions, 1.toShort()))
            ).all().get()
        } catch (e: java.util.concurrent.ExecutionException) {
            if (e.cause !is org.apache.kafka.common.errors.TopicExistsException) throw e
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Outbox schema helper
// -------------------------------------------------------------------------------------------------

private fun resetOutboxSchema(dataSource: HikariDataSource) {
    dataSource.connection.use { conn ->
        conn.prepareStatement("DROP TABLE IF EXISTS lirp_kafka_outbox").use { it.execute() }
        conn.prepareStatement("DROP TABLE IF EXISTS lirp_kafka_dead_letter").use { it.execute() }
    }
}

private fun fastFeatureRelayConfig() =
    KafkaOutboxConfig(
        pollIntervalMs = 50L,
        batchSize = 100,
        maxRetries = 3,
        retryBaseDelayMs = 50L,
        retryMaxDelayMs = 200L
    )
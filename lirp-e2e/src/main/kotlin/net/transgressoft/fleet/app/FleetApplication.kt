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

package net.transgressoft.fleet.app

import net.transgressoft.fleet.common.AssignmentType
import net.transgressoft.fleet.company.CompanyRepository
import net.transgressoft.fleet.person.PersonRepository
import net.transgressoft.fleet.tenant.TenantRepository
import net.transgressoft.fleet.vehicle.Vehicle
import net.transgressoft.fleet.vehicle.VehicleAssignment
import net.transgressoft.fleet.vehicle.VehicleAssignmentRepository
import net.transgressoft.fleet.vehicle.VehicleRepository
import net.transgressoft.lirp.kafka.KafkaOutboxConfig
import net.transgressoft.lirp.kafka.LirpKafkaConfig
import net.transgressoft.lirp.persistence.projection.RegistryProjection
import net.transgressoft.lirp.persistence.projection.registryProjection
import java.util.UUID
import javax.sql.DataSource

/**
 * Composition root for the fleet service, wiring together four domain repositories, a
 * Kafka outbox relay, and a live projection.
 *
 * Each repository is a `@LirpRepository`-annotated factory that auto-registers its entity type
 * into [net.transgressoft.lirp.persistence.LirpContext.default] on construction and deregisters
 * on [close]. Domain operations delegate to the repository factories so no raw repository
 * constructors are needed outside this class.
 *
 * The service owns:
 * - [vehicles] — Kafka-backed [VehicleRepository]; every create, update, and soft-delete is
 *   published to Kafka via the transactional-outbox relay.
 * - [tenants] — plain [TenantRepository]; no Kafka publishing needed for tenants.
 * - [persons] — plain [PersonRepository].
 * - [companies] — plain [CompanyRepository].
 * - [assignments] — plain [VehicleAssignmentRepository] for polymorphic driver/holder references.
 * - [vehiclesByTenant] — read-only projection grouping live (non-soft-deleted) vehicles by tenant id.
 *
 * Domain operations mutate through the repositories; the entity layer is unaware of Kafka — the
 * relay captures outbox rows and publishes them transparently.
 *
 * The relay lifecycle belongs to this service: [start] begins draining the outbox and [close]
 * stops the relay, closes the projection and all four repositories idempotently. Construct one
 * instance per running process; a restart after a crash is modelled by constructing a fresh
 * instance over the same [dataSource].
 */
class FleetApplication(
    private val dataSource: DataSource,
    bootstrapServers: String,
    private val relayConfig: KafkaOutboxConfig = KafkaOutboxConfig.DEFAULT
) : AutoCloseable {

    val tenants = TenantRepository(dataSource)
    val persons = PersonRepository(dataSource)
    val companies = CompanyRepository(dataSource)
    val vehicles = VehicleRepository(dataSource)
    val assignments = VehicleAssignmentRepository(dataSource)
    val vehiclesByTenant: RegistryProjection<UUID, UUID, Vehicle> =
        registryProjection(vehicles, keyExtractor = { v: Vehicle -> v.tenantId })

    private val kafka = LirpKafkaConfig.create(bootstrapServers)

    @Volatile
    private var closed = false

    /**
     * Starts the outbox relay so committed vehicle mutations are published to Kafka.
     *
     * Call once per instance after construction; behavior is undefined if called more than once.
     */
    fun start() {
        kafka.startRelay(dataSource, relayConfig)
    }

    /**
     * Registers a new tenant.
     *
     * @return the newly created tenant, already assigned to the repository.
     */
    fun registerTenant(code: String, name: String) = tenants.register(code, name)

    /**
     * Registers a new vehicle through the debounced write pipeline.
     *
     * The mutation is captured into the outbox by the write-pending hook once the debounce
     * window elapses, then published by the relay.
     *
     * @return the newly created [Vehicle], already assigned a random id.
     */
    fun registerVehicle(tenantId: UUID, vin: String) = vehicles.register(tenantId, vin)

    /**
     * Registers a new vehicle atomically inside an explicit transaction.
     *
     * The outbox row is written in the same transaction as the entity, so it is visible the
     * moment the transaction commits — no debounce delay.
     *
     * @return the newly created [Vehicle], already assigned a random id.
     */
    suspend fun registerVehicleAtomically(tenantId: UUID, vin: String) =
        vehicles.registerAtomically(tenantId, vin)

    /**
     * Updates the VIN of an existing vehicle in-place.
     */
    fun changeVin(vehicle: Vehicle, newVin: String) = vehicles.changeVin(vehicle, newVin)

    /**
     * Decommissions a vehicle via soft-delete.
     *
     * Sets [Vehicle.deletedAt] to the current instant, removes the vehicle from default
     * iteration and projections, and triggers a Delete event through the relay pipeline.
     */
    fun decommissionVehicle(vehicle: Vehicle) = vehicles.decommission(vehicle)

    /**
     * Restores a previously decommissioned vehicle.
     *
     * Clears [Vehicle.deletedAt], re-adds the vehicle to default iteration and projections,
     * and emits a Restore event through the relay pipeline.
     */
    fun restoreVehicle(vehicle: Vehicle) = vehicles.restore(vehicle)

    /**
     * Registers a new person belonging to the given tenant.
     *
     * @return the newly created [net.transgressoft.fleet.person.Person].
     */
    fun registerPerson(tenantId: UUID, firstName: String, lastName: String) =
        persons.register(tenantId, firstName, lastName)

    /**
     * Registers a new company belonging to the given tenant.
     *
     * @return the newly created [net.transgressoft.fleet.company.Company].
     */
    fun registerCompany(tenantId: UUID, name: String) = companies.register(tenantId, name)

    /**
     * Assigns a vehicle to a person as the primary driver.
     *
     * @return the newly created [VehicleAssignment].
     */
    fun assignVehicleToPerson(
        vehicleId: UUID,
        personId: UUID,
        type: AssignmentType = AssignmentType.DRIVER
    ): VehicleAssignment = assignments.assignToPerson(vehicleId, personId, type)

    /**
     * Finds vehicles matching the given VIN using the query DSL.
     *
     * [Vehicle.vin] carries `@Indexed`, so equality lookups use the index-aware planner path.
     * Soft-deleted vehicles are excluded by default.
     *
     * @return a list of matching vehicles (typically zero or one for exact VIN lookups).
     */
    fun findVehiclesByVin(vin: String) = vehicles.findByVin(vin)

    /**
     * Finds vehicles by VIN including soft-deleted (decommissioned) vehicles.
     */
    fun findVehiclesByVinIncludingDeleted(vin: String) = vehicles.findByVinIncludingDeleted(vin)

    /**
     * Returns only soft-deleted (decommissioned) vehicles.
     */
    fun findDecommissionedVehicles() = vehicles.findDecommissioned()

    /**
     * Finds vehicles by VIN and returns diagnostic information about the query execution plan.
     *
     * Because [Vehicle.vin] is `@Indexed`, the planner reports index hits in the returned
     * [net.transgressoft.lirp.persistence.query.DiagnosedQuery.diagnostic].
     */
    fun diagnoseVehiclesByVin(vin: String) = vehicles.diagnoseByVin(vin)

    /**
     * Closes the relay, projection, and all five repositories idempotently.
     *
     * Repositories are closed in reverse construction order so that foreign-key constraints are
     * honoured: assignments (referencing vehicles) close before vehicles, and vehicles (referencing
     * tenants) close before tenants. Each [close] deregisters the entity type from
     * [net.transgressoft.lirp.persistence.LirpContext.default].
     */
    override fun close() {
        if (closed) return
        closed = true
        kafka.close()
        vehiclesByTenant.close()
        assignments.close()
        vehicles.close()
        companies.close()
        persons.close()
        tenants.close()
    }
}
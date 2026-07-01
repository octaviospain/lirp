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

package net.transgressoft.fleet.vehicle

import net.transgressoft.lirp.kafka.KafkaOutboxSqlRepository
import net.transgressoft.lirp.persistence.LirpRepository
import net.transgressoft.lirp.persistence.query.DiagnosedQuery
import net.transgressoft.lirp.persistence.query.eq
import net.transgressoft.lirp.persistence.query.query
import net.transgressoft.lirp.persistence.query.queryWithDiagnostics
import net.transgressoft.lirp.persistence.transaction
import java.util.UUID
import javax.sql.DataSource

/**
 * Kafka-backed factory and query surface for the [Vehicle] aggregate.
 *
 * Extends [KafkaOutboxSqlRepository] so every create, update, and soft-delete mutation is
 * captured into the transactional outbox and published to Kafka by the relay. Auto-registers
 * in [net.transgressoft.lirp.persistence.LirpContext.default] on construction and deregisters
 * on [close].
 *
 * Factory methods build entities and add them to the repository in one call. Query methods
 * use the LIRP query DSL; [Vehicle.vin] carries `@Indexed`, so equality lookups on VIN take the
 * index-aware planner path.
 */
@LirpRepository
class VehicleRepository(dataSource: DataSource) :
    KafkaOutboxSqlRepository<UUID, Vehicle>(dataSource, Vehicle_LirpTableDef) {

    /**
     * Creates a new vehicle and adds it to the repository via the debounced write pipeline.
     *
     * The outbox row is captured when the debounce window elapses and then published by the relay.
     *
     * @return the newly created [Vehicle], already assigned a random id.
     */
    fun register(tenantId: UUID, vin: String): Vehicle =
        Vehicle(UUID.randomUUID(), tenantId).apply { this.vin = vin }.also(::add)

    /**
     * Creates a new vehicle atomically inside an explicit transaction.
     *
     * The outbox row is written in the same transaction as the entity row, so Kafka receipt
     * arrives without debounce delay.
     *
     * @return the newly created [Vehicle], already assigned a random id.
     */
    suspend fun registerAtomically(tenantId: UUID, vin: String): Vehicle {
        val vehicle = Vehicle(UUID.randomUUID(), tenantId).apply { this.vin = vin }
        transaction(this) { repo -> repo.add(vehicle) }
        return vehicle
    }

    /**
     * Updates the VIN of an existing vehicle in-place.
     *
     * The assignment triggers a reactive property mutation that is debounced and flushed to
     * the backing store; the relay then publishes the resulting Update event to Kafka.
     */
    fun changeVin(vehicle: Vehicle, newVin: String) {
        vehicle.vin = newVin
    }

    /**
     * Soft-deletes a vehicle, marking it as decommissioned.
     *
     * Sets [Vehicle.deletedAt] to the current instant, removes the vehicle from default
     * iteration and projections, and triggers a Delete event through the relay pipeline.
     */
    fun decommission(vehicle: Vehicle) {
        softDelete(vehicle)
    }

    /**
     * Returns all vehicles whose VIN matches [vin] exactly.
     *
     * Because [Vehicle.vin] carries `@Indexed`, the query planner resolves this lookup via
     * the index, reporting an index hit in [diagnoseByVin].
     */
    fun findByVin(vin: String): List<Vehicle> =
        query<UUID, Vehicle> {
            where { Vehicle::vin eq vin }
        }.toList()

    /**
     * Returns all vehicles belonging to the given tenant.
     */
    fun findByTenant(tenantId: UUID): List<Vehicle> =
        query<UUID, Vehicle> {
            where { Vehicle::tenantId eq tenantId }
        }.toList()

    /**
     * Executes a VIN lookup and returns results together with query-planner diagnostics.
     *
     * The [DiagnosedQuery.diagnostic] reports index hits because [Vehicle.vin] is `@Indexed`.
     */
    fun diagnoseByVin(vin: String): DiagnosedQuery<Vehicle> =
        queryWithDiagnostics<UUID, Vehicle> {
            where { Vehicle::vin eq vin }
        }

    /**
     * Returns all vehicles with the given VIN, including soft-deleted (decommissioned) vehicles.
     *
     * Useful for auditing or checking whether a VIN was previously registered.
     */
    fun findByVinIncludingDeleted(vin: String): List<Vehicle> =
        query<UUID, Vehicle> {
            where { Vehicle::vin eq vin }
            includeDeleted()
        }.toList()

    /**
     * Returns only soft-deleted (decommissioned) vehicles.
     *
     * Excludes active vehicles from the result set.
     */
    fun findDecommissioned(): List<Vehicle> =
        query<UUID, Vehicle> {
            onlyDeleted()
        }.toList()
}
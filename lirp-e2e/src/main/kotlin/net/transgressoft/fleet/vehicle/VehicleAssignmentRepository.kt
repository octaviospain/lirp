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

import net.transgressoft.fleet.common.AssignmentType
import net.transgressoft.lirp.persistence.LirpRepository
import net.transgressoft.lirp.persistence.query.eq
import net.transgressoft.lirp.persistence.query.query
import net.transgressoft.lirp.persistence.sql.SqlRepository
import java.util.UUID
import javax.sql.DataSource

/**
 * Factory and query surface for the [VehicleAssignment] aggregate.
 *
 * Auto-registers in [net.transgressoft.lirp.persistence.LirpContext.default] on construction
 * and deregisters on [close]. Vehicle assignments are not Kafka-backed — a plain
 * [SqlRepository] is sufficient for this PoC.
 *
 * Factory methods build entities and add them to the repository in one call. The
 * `polymorphicAggregate` invariant (exactly one of [personId] / [companyId] set) is
 * enforced by LIRP before persistence.
 */
@LirpRepository
class VehicleAssignmentRepository(dataSource: DataSource) :
    SqlRepository<UUID, VehicleAssignment>(dataSource, VehicleAssignment_LirpTableDef) {

    /**
     * Assigns a vehicle to a person (individual driver/user).
     *
     * Sets [VehicleAssignment.personId] and leaves [VehicleAssignment.companyId] null, satisfying
     * the polymorphic `assignedTo` exactly-one invariant for the `individual` arm.
     *
     * @return the newly created [VehicleAssignment], already added to the repository.
     */
    fun assignToPerson(
        vehicleId: UUID,
        personId: UUID,
        type: AssignmentType = AssignmentType.DRIVER
    ): VehicleAssignment =
        VehicleAssignment(UUID.randomUUID()).apply {
            this.vehicleId = vehicleId
            this.personId = personId
            this.assignmentType = type
        }.also(::add)

    /**
     * Assigns a vehicle to a company (organizational holder/user).
     *
     * Sets [VehicleAssignment.companyId] and leaves [VehicleAssignment.personId] null, satisfying
     * the polymorphic `assignedTo` exactly-one invariant for the `organization` arm.
     *
     * @return the newly created [VehicleAssignment], already added to the repository.
     */
    fun assignToCompany(
        vehicleId: UUID,
        companyId: UUID,
        type: AssignmentType = AssignmentType.USER
    ): VehicleAssignment =
        VehicleAssignment(UUID.randomUUID()).apply {
            this.vehicleId = vehicleId
            this.companyId = companyId
            this.assignmentType = type
        }.also(::add)

    /**
     * Returns all assignments for the given vehicle.
     */
    fun findByVehicle(vehicleId: UUID): List<VehicleAssignment> =
        query<UUID, VehicleAssignment> {
            where { VehicleAssignment::vehicleId eq vehicleId }
        }.toList()

    /**
     * Returns all assignments for the given person.
     */
    fun findByPerson(personId: UUID): List<VehicleAssignment> =
        query<UUID, VehicleAssignment> {
            where { VehicleAssignment::personId eq personId }
        }.toList()
}
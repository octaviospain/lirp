package net.transgressoft.fleet.vehicle

import net.transgressoft.fleet.common.AssignmentType
import net.transgressoft.fleet.common.VehicleUsageType
import net.transgressoft.fleet.company.Company
import net.transgressoft.fleet.person.Person
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceIgnore
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import net.transgressoft.lirp.persistence.arm
import net.transgressoft.lirp.persistence.polymorphicAggregate
import java.time.LocalDate
import java.util.UUID

/**
 * Assignment of a [Vehicle] to a person or company for a given period.
 *
 * The [assignmentType] determines whether the target is a driver, user, or holder.
 * Exactly one of [personId] or [companyId] should be populated depending on the assignment target.
 * The computed [isCurrentlyValid] property checks whether the assignment is active today and is
 * excluded from persistence.
 */
@PersistenceMapping(name = "vehicle_assignments")
class VehicleAssignment(
    override val id: UUID
) : ReactiveEntityBase<UUID, VehicleAssignment>() {

    @Indexed(name = "vehicleId")
    @ToOneAggregate(target = Vehicle::class, onDelete = CascadeAction.CASCADE)
    var vehicleId: UUID by reactiveProperty(UUID(0, 0))

    var assignmentType: AssignmentType by reactiveProperty(AssignmentType.DRIVER)

    @Indexed(name = "personId")
    var personId: UUID? by reactiveProperty(null)

    @Indexed(name = "companyId")
    var companyId: UUID? by reactiveProperty(null)

    /**
     * Polymorphic assignment target: exactly one of [personId] or [companyId] must be set.
     * The exactly-one invariant is enforced before persistence, and each arm keeps its own
     * `SET NULL` foreign key. Resolve the active target with the generated typed accessor:
     * `(assignedTo.resolution() as PolymorphicResolution<VehicleAssignmentAssignedToArm>).activeArm()`.
     */
    val assignedTo by polymorphicAggregate(
        arm<UUID, Person>("individual", onDelete = CascadeAction.DETACH) { personId },
        arm<UUID, Company>("organization", onDelete = CascadeAction.DETACH) { companyId }
    )

    @ToOneAggregate(target = VehicleUsageType::class, onDelete = CascadeAction.DETACH)
    var usageTypeId: UUID? by reactiveProperty(null)

    var validFrom: LocalDate? by reactiveProperty(null)
    var validUntil: LocalDate? by reactiveProperty(null)

    @PersistenceIgnore
    val isCurrentlyValid: Boolean
        get() {
            val today = LocalDate.now()
            val afterStart = validFrom?.let { !today.isBefore(it) } ?: true
            val beforeEnd = validUntil?.let { !today.isAfter(it) } ?: true
            return afterStart && beforeEnd
        }

    override val uniqueId: String get() = "vehicle-assignment-$id"

    override fun clone(): VehicleAssignment =
        VehicleAssignment(id).apply {
            withEventsDisabled {
                vehicleId = this@VehicleAssignment.vehicleId
                assignmentType = this@VehicleAssignment.assignmentType
                personId = this@VehicleAssignment.personId
                companyId = this@VehicleAssignment.companyId
                usageTypeId = this@VehicleAssignment.usageTypeId
                validFrom = this@VehicleAssignment.validFrom
                validUntil = this@VehicleAssignment.validUntil
            }
        }
}
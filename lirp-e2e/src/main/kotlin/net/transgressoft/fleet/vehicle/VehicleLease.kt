package net.transgressoft.fleet.vehicle

import net.transgressoft.fleet.company.Company
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.time.LocalDate
import java.util.UUID

/**
 * Lease contract associated with a [Vehicle], capturing both contractual and actual date ranges.
 *
 * The [contractStartDate] and [contractEndDate] reflect the agreed-upon lease period, while
 * [actualStartDate] and [actualEndDate] record when the lease was physically active.
 */
@PersistenceMapping(name = "vehicle_leases")
class VehicleLease(
    override val id: UUID
) : ReactiveEntityBase<UUID, VehicleLease>() {

    @Indexed(name = "vehicleId")
    @ToOneAggregate(target = Vehicle::class, onDelete = CascadeAction.CASCADE)
    var vehicleId: UUID by reactiveProperty(UUID(0, 0))

    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.DETACH)
    var leasingCompanyId: UUID? by reactiveProperty(null)

    var contractNumber: String by reactiveProperty("")

    // contractTypeId has no domain entity in this model yet — no @ToOneAggregate emitted.
    var contractTypeId: UUID? by reactiveProperty(null)
    var contractStartDate: LocalDate? by reactiveProperty(null)
    var contractEndDate: LocalDate? by reactiveProperty(null)
    var actualStartDate: LocalDate? by reactiveProperty(null)
    var actualEndDate: LocalDate? by reactiveProperty(null)

    override val uniqueId: String get() = "vehicle-lease-$id"

    override fun clone(): VehicleLease =
        VehicleLease(id).apply {
            withEventsDisabled {
                vehicleId = this@VehicleLease.vehicleId
                leasingCompanyId = this@VehicleLease.leasingCompanyId
                contractNumber = this@VehicleLease.contractNumber
                contractTypeId = this@VehicleLease.contractTypeId
                contractStartDate = this@VehicleLease.contractStartDate
                contractEndDate = this@VehicleLease.contractEndDate
                actualStartDate = this@VehicleLease.actualStartDate
                actualEndDate = this@VehicleLease.actualEndDate
            }
        }
}
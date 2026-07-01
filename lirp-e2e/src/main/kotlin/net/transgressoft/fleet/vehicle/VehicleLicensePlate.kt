package net.transgressoft.fleet.vehicle

import net.transgressoft.fleet.common.Country
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceIgnore
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.time.LocalDate
import java.util.UUID

/**
 * License plate associated with a [Vehicle], supporting historical plate tracking via validity dates.
 *
 * Multiple plates may exist for a vehicle over time. The computed [isCurrentlyValid] property indicates
 * whether the plate is active today and is excluded from persistence.
 */
@PersistenceMapping(name = "vehicle_license_plates")
class VehicleLicensePlate(
    override val id: UUID
) : ReactiveEntityBase<UUID, VehicleLicensePlate>() {

    @Indexed(name = "vehicleId")
    @ToOneAggregate(target = Vehicle::class, onDelete = CascadeAction.CASCADE)
    var vehicleId: UUID by reactiveProperty(UUID(0, 0))

    @Indexed(name = "plateNumber")
    var plateNumber: String by reactiveProperty("")

    @ToOneAggregate(target = Country::class, onDelete = CascadeAction.DETACH)
    var countryId: UUID? by reactiveProperty(null)

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

    override val uniqueId: String get() = "vehicle-license-plate-$id"

    override fun clone(): VehicleLicensePlate =
        VehicleLicensePlate(id).apply {
            withEventsDisabled {
                vehicleId = this@VehicleLicensePlate.vehicleId
                plateNumber = this@VehicleLicensePlate.plateNumber
                countryId = this@VehicleLicensePlate.countryId
                validFrom = this@VehicleLicensePlate.validFrom
                validUntil = this@VehicleLicensePlate.validUntil
            }
        }
}
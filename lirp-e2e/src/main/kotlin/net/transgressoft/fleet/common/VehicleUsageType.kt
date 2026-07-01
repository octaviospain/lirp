package net.transgressoft.fleet.common

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.util.UUID

/**
 * Reference entity classifying the intended usage of a vehicle (e.g., pool car, company car, service vehicle).
 *
 * Used as a lookup table for usage type assignments on [net.transgressoft.fleet.vehicle.VehicleAssignment] instances.
 */
@PersistenceMapping(name = "vehicle_usage_types")
class VehicleUsageType(
    override val id: UUID
) : ReactiveEntityBase<UUID, VehicleUsageType>() {

    var code: String by reactiveProperty("")
    var name: String by reactiveProperty("")

    override val uniqueId: String get() = "vehicle-usage-type-$id"

    override fun clone(): VehicleUsageType =
        VehicleUsageType(id).apply {
            withEventsDisabled {
                code = this@VehicleUsageType.code
                name = this@VehicleUsageType.name
            }
        }
}
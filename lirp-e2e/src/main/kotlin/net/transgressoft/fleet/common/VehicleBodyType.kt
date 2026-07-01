package net.transgressoft.fleet.common

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.util.UUID

/**
 * Reference entity classifying the body style of a vehicle (e.g., sedan, SUV, van).
 *
 * Used as a lookup table for body type assignments on [net.transgressoft.fleet.vehicle.Vehicle] instances.
 */
@PersistenceMapping(name = "vehicle_body_types")
class VehicleBodyType(
    override val id: UUID
) : ReactiveEntityBase<UUID, VehicleBodyType>() {

    var code: String by reactiveProperty("")
    var name: String by reactiveProperty("")

    override val uniqueId: String get() = "vehicle-body-type-$id"

    override fun clone(): VehicleBodyType =
        VehicleBodyType(id).apply {
            withEventsDisabled {
                code = this@VehicleBodyType.code
                name = this@VehicleBodyType.name
            }
        }
}
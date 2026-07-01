package net.transgressoft.fleet.common

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.util.UUID

/**
 * Reference entity representing a vehicle manufacturer.
 *
 * Used as a lookup table for manufacturer assignments on [net.transgressoft.fleet.vehicle.Vehicle] instances.
 */
@PersistenceMapping(name = "manufacturers")
class Manufacturer(
    override val id: UUID
) : ReactiveEntityBase<UUID, Manufacturer>() {

    var code: String by reactiveProperty("")
    var name: String by reactiveProperty("")

    override val uniqueId: String get() = "manufacturer-$id"

    override fun clone(): Manufacturer =
        Manufacturer(id).apply {
            withEventsDisabled {
                code = this@Manufacturer.code
                name = this@Manufacturer.name
            }
        }
}
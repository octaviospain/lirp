package net.transgressoft.fleet.common

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.util.UUID

/**
 * Reference entity classifying the engine type of a vehicle (e.g., combustion, electric, hybrid).
 *
 * Used as a lookup table for engine type assignments on [net.transgressoft.fleet.vehicle.Vehicle] instances.
 */
@PersistenceMapping(name = "engine_types")
class EngineType(
    override val id: UUID
) : ReactiveEntityBase<UUID, EngineType>() {

    var code: String by reactiveProperty("")
    var name: String by reactiveProperty("")

    override val uniqueId: String get() = "engine-type-$id"

    override fun clone(): EngineType =
        EngineType(id).apply {
            withEventsDisabled {
                code = this@EngineType.code
                name = this@EngineType.name
            }
        }
}
package net.transgressoft.fleet.common

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.util.UUID

/**
 * Reference entity representing a country with ISO code identifiers.
 *
 * Used as a lookup table for address country fields throughout the fleet domain.
 */
@PersistenceMapping(name = "countries")
class Country(
    override val id: UUID
) : ReactiveEntityBase<UUID, Country>() {

    var isoCode2: String by reactiveProperty("")
    var isoCode3: String by reactiveProperty("")
    var plateCode: String? by reactiveProperty(null)
    var name: String by reactiveProperty("")

    override val uniqueId: String get() = "country-$id"

    override fun clone(): Country =
        Country(id).apply {
            withEventsDisabled {
                isoCode2 = this@Country.isoCode2
                isoCode3 = this@Country.isoCode3
                plateCode = this@Country.plateCode
                name = this@Country.name
            }
        }
}
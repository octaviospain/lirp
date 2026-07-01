package net.transgressoft.fleet.common

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.util.UUID

/**
 * Reference entity classifying types of person features (e.g., certifications, qualifications).
 *
 * Feature types are shared lookup values assigned to [net.transgressoft.fleet.person.PersonFeature]
 * instances.
 */
@PersistenceMapping(name = "person_feature_types")
class PersonFeatureType(
    override val id: UUID
) : ReactiveEntityBase<UUID, PersonFeatureType>() {

    var code: String by reactiveProperty("")
    var name: String by reactiveProperty("")
    var description: String? by reactiveProperty(null)

    override val uniqueId: String get() = "person-feature-type-$id"

    override fun clone(): PersonFeatureType =
        PersonFeatureType(id).apply {
            withEventsDisabled {
                code = this@PersonFeatureType.code
                name = this@PersonFeatureType.name
                description = this@PersonFeatureType.description
            }
        }
}
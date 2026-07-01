package net.transgressoft.fleet.person

import net.transgressoft.fleet.common.PersonFeatureType
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceIgnore
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.time.LocalDate
import java.util.UUID

/**
 * Association between a [Person] and a feature type, with optional validity period.
 *
 * A feature represents a qualification, certification, or attribute held by a person.
 * The [isCurrentlyValid] computed property checks whether the feature is active today
 * and is excluded from persistence.
 */
@PersistenceMapping(name = "person_features")
class PersonFeature(
    override val id: UUID
) : ReactiveEntityBase<UUID, PersonFeature>() {

    @Indexed
    @ToOneAggregate(target = Person::class, onDelete = CascadeAction.CASCADE)
    var personId: UUID by reactiveProperty(UUID(0, 0))

    @ToOneAggregate(target = PersonFeatureType::class, onDelete = CascadeAction.RESTRICT)
    var featureTypeId: UUID by reactiveProperty(UUID(0, 0))

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

    override val uniqueId: String get() = "person-feature-$id"

    override fun clone(): PersonFeature =
        PersonFeature(id).apply {
            withEventsDisabled {
                personId = this@PersonFeature.personId
                featureTypeId = this@PersonFeature.featureTypeId
                validFrom = this@PersonFeature.validFrom
                validUntil = this@PersonFeature.validUntil
            }
        }
}
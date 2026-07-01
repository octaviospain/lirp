package net.transgressoft.fleet.company

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceIgnore
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.time.LocalDate
import java.util.UUID

/**
 * Association between a [Company] and a feature type, with optional validity period.
 *
 * A feature represents a qualification, certification, or attribute held by a company.
 * The [isCurrentlyValid] computed property checks whether the feature is active today
 * and is excluded from persistence.
 */
@PersistenceMapping(name = "company_features")
class CompanyFeature(
    override val id: UUID
) : ReactiveEntityBase<UUID, CompanyFeature>() {

    @Indexed
    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.CASCADE)
    var companyId: UUID by reactiveProperty(UUID(0, 0))

    @ToOneAggregate(target = CompanyFeatureType::class, onDelete = CascadeAction.RESTRICT)
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

    override val uniqueId: String get() = "company-feature-$id"

    override fun clone(): CompanyFeature =
        CompanyFeature(id).apply {
            withEventsDisabled {
                companyId = this@CompanyFeature.companyId
                featureTypeId = this@CompanyFeature.featureTypeId
                validFrom = this@CompanyFeature.validFrom
                validUntil = this@CompanyFeature.validUntil
            }
        }
}
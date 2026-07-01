package net.transgressoft.fleet.company

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.util.UUID

/**
 * Reference entity classifying types of company features (e.g., certifications, accreditations).
 *
 * Feature types are shared lookup values assigned to [CompanyFeature] instances.
 */
@PersistenceMapping(name = "company_feature_types")
class CompanyFeatureType(
    override val id: UUID
) : ReactiveEntityBase<UUID, CompanyFeatureType>() {

    var code: String by reactiveProperty("")
    var name: String by reactiveProperty("")
    var description: String? by reactiveProperty(null)

    override val uniqueId: String get() = "company-feature-type-$id"

    override fun clone(): CompanyFeatureType =
        CompanyFeatureType(id).apply {
            withEventsDisabled {
                code = this@CompanyFeatureType.code
                name = this@CompanyFeatureType.name
                description = this@CompanyFeatureType.description
            }
        }
}
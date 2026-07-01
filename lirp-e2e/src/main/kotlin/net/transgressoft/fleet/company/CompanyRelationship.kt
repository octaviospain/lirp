package net.transgressoft.fleet.company

import net.transgressoft.fleet.common.RelationshipType
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceIgnore
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.time.LocalDate
import java.util.UUID

/**
 * Directed relationship between two companies with a typed [relationshipType] and optional validity period.
 *
 * The [sourceCompanyId] is the originating party and [targetCompanyId] is the related party.
 * The [isCurrentlyValid] computed property checks whether the relationship is active today
 * and is excluded from persistence.
 */
@PersistenceMapping(name = "company_relationships")
class CompanyRelationship(
    override val id: UUID
) : ReactiveEntityBase<UUID, CompanyRelationship>() {

    @Indexed
    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.CASCADE)
    var sourceCompanyId: UUID by reactiveProperty(UUID(0, 0))

    @Indexed
    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.CASCADE)
    var targetCompanyId: UUID by reactiveProperty(UUID(0, 0))

    var relationshipType: RelationshipType by reactiveProperty(RelationshipType.PARTNER)
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

    override val uniqueId: String get() = "company-relationship-$id"

    override fun clone(): CompanyRelationship =
        CompanyRelationship(id).apply {
            withEventsDisabled {
                sourceCompanyId = this@CompanyRelationship.sourceCompanyId
                targetCompanyId = this@CompanyRelationship.targetCompanyId
                relationshipType = this@CompanyRelationship.relationshipType
                validFrom = this@CompanyRelationship.validFrom
                validUntil = this@CompanyRelationship.validUntil
            }
        }
}
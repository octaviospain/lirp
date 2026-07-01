package net.transgressoft.fleet.company

import net.transgressoft.fleet.common.Auditable
import net.transgressoft.fleet.tenant.Tenant
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.MutableSoftDeletable
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Company entity representing a legal entity in the fleet domain.
 *
 * A company belongs to a [tenantId] and may carry financial identifiers ([taxNumber], [vatId],
 * [debtorNumber], [creditorNumber]) along with legacy system references ([legacyGrumId], [externalId]).
 * The [vatDeductible] flag indicates whether input tax can be reclaimed.
 */
@PersistenceMapping(name = "companies")
class Company(
    override val id: UUID,
    tenantId: UUID
) : ReactiveEntityBase<UUID, Company>(), Auditable, MutableSoftDeletable {

    @ToOneAggregate(target = Tenant::class, onDelete = CascadeAction.RESTRICT)
    var tenantId: UUID by reactiveProperty(tenantId)

    var name: String by reactiveProperty("")
    var additionalName: String? by reactiveProperty(null)
    var taxNumber: String? by reactiveProperty(null)
    var vatId: String? by reactiveProperty(null)
    var vatDeductible: Boolean by reactiveProperty(false)
    var debtorNumber: String? by reactiveProperty(null)
    var creditorNumber: String? by reactiveProperty(null)
    var notes: String? by reactiveProperty(null)
    var companyMatch: String? by reactiveProperty(null)
    var legacyGrumId: String? by reactiveProperty(null)
    var externalId: String? by reactiveProperty(null)

    override var createdAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var updatedAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var createdBy: String? by reactiveProperty(null)
    override var lastModifiedBy: String? by reactiveProperty(null)

    override var deletedAt: Instant? by reactiveProperty(null)

    override val uniqueId: String get() = "company-$id"

    override fun clone(): Company =
        Company(id, tenantId).apply {
            withEventsDisabled {
                name = this@Company.name
                additionalName = this@Company.additionalName
                taxNumber = this@Company.taxNumber
                vatId = this@Company.vatId
                vatDeductible = this@Company.vatDeductible
                debtorNumber = this@Company.debtorNumber
                creditorNumber = this@Company.creditorNumber
                notes = this@Company.notes
                companyMatch = this@Company.companyMatch
                legacyGrumId = this@Company.legacyGrumId
                externalId = this@Company.externalId
                createdAt = this@Company.createdAt
                updatedAt = this@Company.updatedAt
                createdBy = this@Company.createdBy
                lastModifiedBy = this@Company.lastModifiedBy
                deletedAt = this@Company.deletedAt
            }
        }
}
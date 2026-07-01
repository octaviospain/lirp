package net.transgressoft.fleet.company

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Insurance agreement held by a [Company], covering liability, comprehensive, and partial policies.
 *
 * An agreement may reference a [parentAgreementId] for hierarchical structures (e.g. consortium
 * arrangements). Deductible amounts ([deductibleComprehensive], [deductiblePartial]) are stored
 * with up to 10 significant digits and 2 decimal places. The [isOwnCoverage] and
 * [isConsortiumPolicyholder] flags further classify the coverage arrangement.
 */
@PersistenceMapping(name = "company_insurance_agreements")
class CompanyInsuranceAgreement(
    override val id: UUID
) : ReactiveEntityBase<UUID, CompanyInsuranceAgreement>() {

    @Indexed
    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.CASCADE)
    var companyId: UUID by reactiveProperty(UUID(0, 0))

    @ToOneAggregate(target = CompanyInsuranceAgreement::class, onDelete = CascadeAction.DETACH)
    var parentAgreementId: UUID? by reactiveProperty(null)

    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.DETACH)
    var liabilityInsuranceCompanyId: UUID? by reactiveProperty(null)

    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.DETACH)
    var comprehensiveInsuranceCompanyId: UUID? by reactiveProperty(null)

    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.DETACH)
    var partialInsuranceCompanyId: UUID? by reactiveProperty(null)

    var deductibleComprehensive: BigDecimal? by reactiveProperty(null)
    var deductiblePartial: BigDecimal? by reactiveProperty(null)
    var defaultPolicyNumberLiability: String? by reactiveProperty(null)
    var defaultPolicyNumberComprehensive: String? by reactiveProperty(null)
    var defaultPolicyNumberPartial: String? by reactiveProperty(null)
    var isOwnCoverage: Boolean by reactiveProperty(false)
    var isConsortiumPolicyholder: Boolean by reactiveProperty(false)
    var validFrom: LocalDate? by reactiveProperty(null)
    var validUntil: LocalDate? by reactiveProperty(null)

    override val uniqueId: String get() = "company-insurance-agreement-$id"

    override fun clone(): CompanyInsuranceAgreement =
        CompanyInsuranceAgreement(id).apply {
            withEventsDisabled {
                companyId = this@CompanyInsuranceAgreement.companyId
                parentAgreementId = this@CompanyInsuranceAgreement.parentAgreementId
                liabilityInsuranceCompanyId = this@CompanyInsuranceAgreement.liabilityInsuranceCompanyId
                comprehensiveInsuranceCompanyId = this@CompanyInsuranceAgreement.comprehensiveInsuranceCompanyId
                partialInsuranceCompanyId = this@CompanyInsuranceAgreement.partialInsuranceCompanyId
                deductibleComprehensive = this@CompanyInsuranceAgreement.deductibleComprehensive
                deductiblePartial = this@CompanyInsuranceAgreement.deductiblePartial
                defaultPolicyNumberLiability = this@CompanyInsuranceAgreement.defaultPolicyNumberLiability
                defaultPolicyNumberComprehensive = this@CompanyInsuranceAgreement.defaultPolicyNumberComprehensive
                defaultPolicyNumberPartial = this@CompanyInsuranceAgreement.defaultPolicyNumberPartial
                isOwnCoverage = this@CompanyInsuranceAgreement.isOwnCoverage
                isConsortiumPolicyholder = this@CompanyInsuranceAgreement.isConsortiumPolicyholder
                validFrom = this@CompanyInsuranceAgreement.validFrom
                validUntil = this@CompanyInsuranceAgreement.validUntil
            }
        }
}
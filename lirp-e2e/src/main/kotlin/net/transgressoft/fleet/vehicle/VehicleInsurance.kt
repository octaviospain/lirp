package net.transgressoft.fleet.vehicle

import net.transgressoft.fleet.common.PolicyType
import net.transgressoft.fleet.company.Company
import net.transgressoft.fleet.company.CompanyInsuranceAgreement
import net.transgressoft.fleet.person.Person
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import net.transgressoft.lirp.persistence.arm
import net.transgressoft.lirp.persistence.polymorphicAggregate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Insurance policy attached to a [Vehicle], covering a specific [policyType] and validity period.
 *
 * The policyholder may be either a person ([policyholderPersonId]) or a company ([policyholderCompanyId]).
 * Financial terms including [deductible], [coverageLimit], and [annualPremium] are stored with
 * high-precision decimal types to support accurate premium calculations.
 */
@PersistenceMapping(name = "vehicle_insurances")
class VehicleInsurance(
    override val id: UUID
) : ReactiveEntityBase<UUID, VehicleInsurance>() {

    @Indexed(name = "vehicleId")
    @ToOneAggregate(target = Vehicle::class, onDelete = CascadeAction.CASCADE)
    var vehicleId: UUID by reactiveProperty(UUID(0, 0))

    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.DETACH)
    var insuranceCompanyId: UUID? by reactiveProperty(null)

    var policyholderPersonId: UUID? by reactiveProperty(null)

    var policyholderCompanyId: UUID? by reactiveProperty(null)

    /**
     * Polymorphic policyholder: exactly one of [policyholderPersonId] or [policyholderCompanyId]
     * must be set. The exactly-one invariant is enforced before persistence, and each arm keeps
     * its own `SET NULL` foreign key. Resolve the active policyholder with the generated typed
     * accessor: `(policyholder.resolution() as PolymorphicResolution<VehicleInsurancePolicyholderArm>).activeArm()`.
     */
    val policyholder by polymorphicAggregate(
        arm<UUID, Person>("individual", onDelete = CascadeAction.DETACH) { policyholderPersonId },
        arm<UUID, Company>("organization", onDelete = CascadeAction.DETACH) { policyholderCompanyId }
    )

    @ToOneAggregate(target = CompanyInsuranceAgreement::class, onDelete = CascadeAction.DETACH)
    var companyInsuranceAgreementId: UUID? by reactiveProperty(null)

    var policyNumber: String by reactiveProperty("")
    var policyType: PolicyType by reactiveProperty(PolicyType.LIABILITY)
    var coverageStartDate: LocalDate? by reactiveProperty(null)
    var coverageEndDate: LocalDate? by reactiveProperty(null)
    var deductible: BigDecimal? by reactiveProperty(null)
    var coverageLimit: BigDecimal? by reactiveProperty(null)
    var annualPremium: BigDecimal? by reactiveProperty(null)

    override val uniqueId: String get() = "vehicle-insurance-$id"

    override fun clone(): VehicleInsurance =
        VehicleInsurance(id).apply {
            withEventsDisabled {
                vehicleId = this@VehicleInsurance.vehicleId
                insuranceCompanyId = this@VehicleInsurance.insuranceCompanyId
                policyholderPersonId = this@VehicleInsurance.policyholderPersonId
                policyholderCompanyId = this@VehicleInsurance.policyholderCompanyId
                companyInsuranceAgreementId = this@VehicleInsurance.companyInsuranceAgreementId
                policyNumber = this@VehicleInsurance.policyNumber
                policyType = this@VehicleInsurance.policyType
                coverageStartDate = this@VehicleInsurance.coverageStartDate
                coverageEndDate = this@VehicleInsurance.coverageEndDate
                deductible = this@VehicleInsurance.deductible
                coverageLimit = this@VehicleInsurance.coverageLimit
                annualPremium = this@VehicleInsurance.annualPremium
            }
        }
}
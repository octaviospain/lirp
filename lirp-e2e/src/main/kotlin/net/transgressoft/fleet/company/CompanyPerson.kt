package net.transgressoft.fleet.company

import net.transgressoft.fleet.common.CompanyPersonRole
import net.transgressoft.fleet.person.Person
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.time.LocalDate
import java.util.UUID

/**
 * Association between a [Company] and a person, defining the person's [role] within the company.
 *
 * An optional validity period ([validFrom], [validUntil]) constrains the active timeframe of
 * the assignment. The [notes] field allows free-text remarks about the relationship.
 */
@PersistenceMapping(name = "company_persons")
class CompanyPerson(
    override val id: UUID
) : ReactiveEntityBase<UUID, CompanyPerson>() {

    @Indexed
    @ToOneAggregate(target = Company::class, onDelete = CascadeAction.CASCADE)
    var companyId: UUID by reactiveProperty(UUID(0, 0))

    @Indexed
    @ToOneAggregate(target = Person::class, onDelete = CascadeAction.CASCADE)
    var personId: UUID by reactiveProperty(UUID(0, 0))

    var role: CompanyPersonRole by reactiveProperty(CompanyPersonRole.EMPLOYEE)
    var validFrom: LocalDate? by reactiveProperty(null)
    var validUntil: LocalDate? by reactiveProperty(null)
    var notes: String? by reactiveProperty(null)

    override val uniqueId: String get() = "company-person-$id"

    override fun clone(): CompanyPerson =
        CompanyPerson(id).apply {
            withEventsDisabled {
                companyId = this@CompanyPerson.companyId
                personId = this@CompanyPerson.personId
                role = this@CompanyPerson.role
                validFrom = this@CompanyPerson.validFrom
                validUntil = this@CompanyPerson.validUntil
                notes = this@CompanyPerson.notes
            }
        }
}
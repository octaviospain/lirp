package net.transgressoft.fleet.person

import net.transgressoft.fleet.common.Auditable
import net.transgressoft.fleet.common.Salutation
import net.transgressoft.fleet.common.Title
import net.transgressoft.fleet.tenant.Tenant
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.MutableSoftDeletable
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceIgnore
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Person entity representing an individual in the fleet domain.
 *
 * A person belongs to a [tenantId] and may have optional identity attributes such as
 * [salutation], [title], and [birthDate]. Debtor/creditor numbers link to financial systems.
 * The computed [fullName] property is excluded from persistence.
 */
@PersistenceMapping(name = "persons")
class Person(
    override val id: UUID,
    tenantId: UUID
) : ReactiveEntityBase<UUID, Person>(), Auditable, MutableSoftDeletable {

    @ToOneAggregate(target = Tenant::class, onDelete = CascadeAction.RESTRICT)
    var tenantId: UUID by reactiveProperty(tenantId)

    var firstName: String by reactiveProperty("")
    var lastName: String by reactiveProperty("")
    var salutation: Salutation? by reactiveProperty(null)
    var title: Title? by reactiveProperty(null)
    var birthDate: LocalDate? by reactiveProperty(null)
    var debtorNumber: String? by reactiveProperty(null)
    var creditorNumber: String? by reactiveProperty(null)
    var isVip: Boolean by reactiveProperty(false)
    var notes: String? by reactiveProperty(null)
    var legacyGrumId: String? by reactiveProperty(null)
    var externalId: String? by reactiveProperty(null)

    override var createdAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var updatedAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var createdBy: String? by reactiveProperty(null)
    override var lastModifiedBy: String? by reactiveProperty(null)

    override var deletedAt: Instant? by reactiveProperty(null)

    @PersistenceIgnore
    val fullName: String get() = "$firstName $lastName".trim()

    override val uniqueId: String get() = "person-$id"

    override fun clone(): Person =
        Person(id, tenantId).apply {
            withEventsDisabled {
                firstName = this@Person.firstName
                lastName = this@Person.lastName
                salutation = this@Person.salutation
                title = this@Person.title
                birthDate = this@Person.birthDate
                debtorNumber = this@Person.debtorNumber
                creditorNumber = this@Person.creditorNumber
                isVip = this@Person.isVip
                notes = this@Person.notes
                legacyGrumId = this@Person.legacyGrumId
                externalId = this@Person.externalId
                createdAt = this@Person.createdAt
                updatedAt = this@Person.updatedAt
                createdBy = this@Person.createdBy
                lastModifiedBy = this@Person.lastModifiedBy
                deletedAt = this@Person.deletedAt
            }
        }
}
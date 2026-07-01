package net.transgressoft.fleet.common

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.time.LocalDateTime
import java.util.UUID

/**
 * Contact information entry associated with a person or company.
 *
 * A contact may belong to a person ([personId]) or a company ([companyId]), but not both.
 * The [contactType] classifies the communication channel (phone, email, website, etc.).
 */
@PersistenceMapping(name = "contacts")
class Contact(
    override val id: UUID
) : ReactiveEntityBase<UUID, Contact>(), Auditable {

    @Indexed
    var personId: UUID? by reactiveProperty(null)

    @Indexed
    var companyId: UUID? by reactiveProperty(null)

    var contactType: ContactType by reactiveProperty(ContactType.OTHER)
    var phoneNumber: String? by reactiveProperty(null)
    var emailAddress: String? by reactiveProperty(null)
    var websiteUrl: String? by reactiveProperty(null)
    var label: String? by reactiveProperty(null)
    var notes: String? by reactiveProperty(null)

    override var createdAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var updatedAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var createdBy: String? by reactiveProperty(null)
    override var lastModifiedBy: String? by reactiveProperty(null)

    override val uniqueId: String get() = "contact-$id"

    override fun clone(): Contact =
        Contact(id).apply {
            withEventsDisabled {
                personId = this@Contact.personId
                companyId = this@Contact.companyId
                contactType = this@Contact.contactType
                phoneNumber = this@Contact.phoneNumber
                emailAddress = this@Contact.emailAddress
                websiteUrl = this@Contact.websiteUrl
                label = this@Contact.label
                notes = this@Contact.notes
                createdAt = this@Contact.createdAt
                updatedAt = this@Contact.updatedAt
                createdBy = this@Contact.createdBy
                lastModifiedBy = this@Contact.lastModifiedBy
            }
        }
}
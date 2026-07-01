package net.transgressoft.fleet.common

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Physical or postal address associated with a person or company.
 *
 * An address may belong to a person ([personId]) or a company ([companyId]), but not both.
 * The [addressType] categorizes the address purpose. Validity is bounded by optional
 * [validFrom]/[validUntil] dates.
 */
@PersistenceMapping(name = "addresses")
class Address(
    override val id: UUID
) : ReactiveEntityBase<UUID, Address>(), Auditable {

    @Indexed
    var personId: UUID? by reactiveProperty(null)

    @Indexed
    var companyId: UUID? by reactiveProperty(null)

    var addressType: AddressType by reactiveProperty(AddressType.OTHER)
    var isPrimary: Boolean by reactiveProperty(false)
    var street: String by reactiveProperty("")
    var street2: String? by reactiveProperty(null)
    var postalCode: String by reactiveProperty("")
    var city: String by reactiveProperty("")
    var stateProvince: String? by reactiveProperty(null)
    var countryId: UUID? by reactiveProperty(null)
    var validFrom: LocalDate? by reactiveProperty(null)
    var validUntil: LocalDate? by reactiveProperty(null)

    override var createdAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var updatedAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var createdBy: String? by reactiveProperty(null)
    override var lastModifiedBy: String? by reactiveProperty(null)

    override val uniqueId: String get() = "address-$id"

    override fun clone(): Address =
        Address(id).apply {
            withEventsDisabled {
                personId = this@Address.personId
                companyId = this@Address.companyId
                addressType = this@Address.addressType
                isPrimary = this@Address.isPrimary
                street = this@Address.street
                street2 = this@Address.street2
                postalCode = this@Address.postalCode
                city = this@Address.city
                stateProvince = this@Address.stateProvince
                countryId = this@Address.countryId
                validFrom = this@Address.validFrom
                validUntil = this@Address.validUntil
                createdAt = this@Address.createdAt
                updatedAt = this@Address.updatedAt
                createdBy = this@Address.createdBy
                lastModifiedBy = this@Address.lastModifiedBy
            }
        }
}
package net.transgressoft.fleet.tenant

import net.transgressoft.fleet.common.Auditable
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.MutableSoftDeletable
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Root tenant entity representing an organization in a multi-tenant hierarchy.
 *
 * Tenants can form a parent-child tree via [parentTenantId]. Root tenants have
 * a null parent. The nullable [parentTenantId] produces an optional reference via
 * the KSP-generated extension accessor.
 */
@PersistenceMapping(name = "tenants")
class Tenant(
    override val id: UUID,
    parentTenantId: UUID? = null
) : ReactiveEntityBase<UUID, Tenant>(), Auditable, MutableSoftDeletable {

    var code: String by reactiveProperty("")
    var name: String by reactiveProperty("")
    var displayName: String? by reactiveProperty(null)
    var shortName: String? by reactiveProperty(null)
    var color: String? by reactiveProperty(null)
    var logoUrl: String? by reactiveProperty(null)
    var isSelectable: Boolean by reactiveProperty(true)
    var isMainTenant: Boolean by reactiveProperty(false)
    var sortOrder: Int? by reactiveProperty(null)
    var legacyId: String? by reactiveProperty(null)

    @ToOneAggregate(target = Tenant::class, onDelete = CascadeAction.DETACH)
    var parentTenantId: UUID? by reactiveProperty(parentTenantId)

    override var createdAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var updatedAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var createdBy: String? by reactiveProperty(null)
    override var lastModifiedBy: String? by reactiveProperty(null)

    override var deletedAt: Instant? by reactiveProperty(null)

    override val uniqueId: String get() = "tenant-$id"

    override fun clone(): Tenant =
        Tenant(id, parentTenantId).apply {
            withEventsDisabled {
                code = this@Tenant.code
                name = this@Tenant.name
                displayName = this@Tenant.displayName
                shortName = this@Tenant.shortName
                color = this@Tenant.color
                logoUrl = this@Tenant.logoUrl
                isSelectable = this@Tenant.isSelectable
                isMainTenant = this@Tenant.isMainTenant
                sortOrder = this@Tenant.sortOrder
                legacyId = this@Tenant.legacyId
                createdAt = this@Tenant.createdAt
                updatedAt = this@Tenant.updatedAt
                createdBy = this@Tenant.createdBy
                lastModifiedBy = this@Tenant.lastModifiedBy
                deletedAt = this@Tenant.deletedAt
            }
        }
}
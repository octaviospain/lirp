package net.transgressoft.fleet.vehicle

import net.transgressoft.fleet.common.Auditable
import net.transgressoft.fleet.common.ContractStatus
import net.transgressoft.fleet.common.EngineType
import net.transgressoft.fleet.common.Manufacturer
import net.transgressoft.fleet.common.VehicleBodyType
import net.transgressoft.fleet.tenant.Tenant
import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.MutableSoftDeletable
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.ToOneAggregate
import net.transgressoft.lirp.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Core vehicle entity representing a physical vehicle in the fleet.
 *
 * A vehicle belongs to a [tenantId] and is uniquely identified by its [vin] (Vehicle Identification Number).
 * The [contractStatus] tracks whether the vehicle is currently under an active contract or completed.
 * Financial data such as [listenpreis] (list price) supports cost-center reporting via [costCenterCode].
 */
@PersistenceMapping(name = "vehicles")
class Vehicle(
    override val id: UUID,
    tenantId: UUID
) : ReactiveEntityBase<UUID, Vehicle>(), Auditable, MutableSoftDeletable {

    @ToOneAggregate(target = Tenant::class, onDelete = CascadeAction.NONE)
    var tenantId: UUID by reactiveProperty(tenantId)

    @Indexed(name = "vin")
    var vin: String by reactiveProperty("")

    @ToOneAggregate(target = Manufacturer::class, onDelete = CascadeAction.DETACH)
    var manufacturerId: UUID? by reactiveProperty(null)

    var modelName: String by reactiveProperty("")

    @ToOneAggregate(target = VehicleBodyType::class, onDelete = CascadeAction.DETACH)
    var bodyTypeId: UUID? by reactiveProperty(null)

    @ToOneAggregate(target = EngineType::class, onDelete = CascadeAction.DETACH)
    var engineTypeId: UUID? by reactiveProperty(null)

    var contractStatus: ContractStatus by reactiveProperty(ContractStatus.RUNNING)
    var costCenterCode: String? by reactiveProperty(null)
    var firstRegistrationDate: LocalDate? by reactiveProperty(null)
    var listenpreis: BigDecimal? by reactiveProperty(null)
    var legacyGrumId: String? by reactiveProperty(null)
    var externalId: String? by reactiveProperty(null)

    override var createdAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var updatedAt: LocalDateTime by reactiveProperty(LocalDateTime.now())
    override var createdBy: String? by reactiveProperty(null)
    override var lastModifiedBy: String? by reactiveProperty(null)

    override var deletedAt: Instant? by reactiveProperty(null)

    @Version
    var version: Long by reactiveProperty(0L)

    override val uniqueId: String get() = "vehicle-$id"

    override fun clone(): Vehicle =
        Vehicle(id, tenantId).apply {
            withEventsDisabled {
                vin = this@Vehicle.vin
                manufacturerId = this@Vehicle.manufacturerId
                modelName = this@Vehicle.modelName
                bodyTypeId = this@Vehicle.bodyTypeId
                engineTypeId = this@Vehicle.engineTypeId
                contractStatus = this@Vehicle.contractStatus
                costCenterCode = this@Vehicle.costCenterCode
                firstRegistrationDate = this@Vehicle.firstRegistrationDate
                listenpreis = this@Vehicle.listenpreis
                legacyGrumId = this@Vehicle.legacyGrumId
                externalId = this@Vehicle.externalId
                createdAt = this@Vehicle.createdAt
                updatedAt = this@Vehicle.updatedAt
                createdBy = this@Vehicle.createdBy
                lastModifiedBy = this@Vehicle.lastModifiedBy
                deletedAt = this@Vehicle.deletedAt
                version = this@Vehicle.version
            }
        }
}
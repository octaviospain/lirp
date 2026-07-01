package net.transgressoft.fleet.common

import java.time.LocalDateTime

/**
 * Mixin for entities that track creation and modification metadata.
 *
 * LIRP has no built-in audit support, so these are manual reactive properties
 * on each implementing entity. This interface provides the contract.
 */
interface Auditable {
    var createdAt: LocalDateTime
    var updatedAt: LocalDateTime
    var createdBy: String?
    var lastModifiedBy: String?
}
/******************************************************************************
 *     Copyright (C) 2025  Octavio Calleya Garcia                             *
 *                                                                            *
 *     This program is free software: you can redistribute it and/or modify   *
 *     it under the terms of the GNU General Public License as published by   *
 *     the Free Software Foundation, either version 3 of the License, or      *
 *     (at your option) any later version.                                    *
 *                                                                            *
 *     This program is distributed in the hope that it will be useful,        *
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of         *
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
 *     GNU General Public License for more details.                           *
 *                                                                            *
 *     You should have received a copy of the GNU General Public License      *
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>. *
 ******************************************************************************/

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.CascadeAction

/**
 * Marks a property as an aggregate reference to another [net.transgressoft.lirp.entity.ReactiveEntity].
 *
 * At compile time, the LIRP KSP processor scans for `@Aggregate` annotations and generates a
 * [LirpRefAccessor] implementation per entity class. The generated accessor contains direct
 * property getter lambdas that retrieve the referenced entity's ID — no runtime reflection.
 *
 * At runtime, when an entity is first added to a repository, the generated accessor is loaded via
 * a convention-based class lookup (`{EntityClassName}_LirpRefAccessor`) and its [RefEntry] descriptors
 * drive reference resolution, bubble-up wiring, and cascade behavior.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@Aggregate` annotations have no effect.
 *
 * Example:
 * ```kotlin
 * // build.gradle.kts: ksp(project(":lirp-ksp"))
 *
 * class Invoice(override val id: Int, val orderId: Long) : ReactiveEntityBase<Int, Invoice>() {
 *     @Aggregate(bubbleUp = true, onDelete = CascadeAction.DETACH)
 *     val order by aggregate<Long, Order> { orderId }
 * }
 *
 * // Resolution
 * val resolvedOrder: Optional<Order> = invoice.order.resolve()
 * ```
 *
 * @param bubbleUp When `true`, mutation events from the referenced entity are forwarded to this
 *   entity's subscribers as [net.transgressoft.lirp.event.AggregateMutationEvent]. Disabled by default.
 * @param onDelete The [CascadeAction] to execute when this entity is removed from its repository.
 *   Defaults to [CascadeAction.DETACH].
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Aggregate(val bubbleUp: Boolean = false, val onDelete: CascadeAction = CascadeAction.DETACH)
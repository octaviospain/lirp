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

/**
 * Marks a `Long` reactive property as the optimistic-lock version column for an entity.
 *
 * At compile time, the LIRP KSP processor scans for `@Version` annotations and records the
 * version column on the generated `_LirpTableDef`. At runtime, `SqlRepository` includes the
 * annotated column in UPDATE and DELETE WHERE predicates so that a concurrent writer's mutation
 * is detected as a zero-row-affected result. On conflict the repository emits a
 * [net.transgressoft.lirp.event.StandardCrudEvent.Conflict] event carrying the attempted local
 * state and the canonical row re-fetched from the database. After a successful UPDATE the
 * library auto-bumps the value in-memory so the next mutation observes the new baseline.
 *
 * **Contract (enforced by the `lirp-ksp` processor):**
 * - Exactly one `@Version` property per entity class.
 * - Property type must be non-nullable [kotlin.Long].
 * - Property must be declared with `var`.
 * - Property must use the `reactiveProperty` delegate from `ReactiveEntityBase`.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@Version` annotations have no effect, optimistic locking is not active, and last-write-wins
 * behavior applies.
 *
 * Consumers SHOULD NOT read or write this property directly. Inserts start at version `0`; the
 * library auto-bumps after each successful UPDATE.
 *
 * Uses [AnnotationRetention.BINARY] retention — KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * Example:
 * ```kotlin
 * // build.gradle.kts: ksp(project(":lirp-ksp"))
 *
 * class Order(override val id: Long) : ReactiveEntityBase<Long, Order>() {
 *     var total: Long by reactiveProperty(0L)
 *
 *     @Version var version: Long by reactiveProperty(0L)
 *
 *     override val uniqueId: String get() = "order-$id"
 *     override fun clone(): Order = Order(id)
 * }
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Version
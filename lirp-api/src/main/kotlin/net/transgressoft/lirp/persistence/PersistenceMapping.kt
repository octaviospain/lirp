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
 * Marks a [net.transgressoft.lirp.entity.ReactiveEntity] subclass for persistence mapping.
 *
 * At compile time, the LIRP KSP processor scans for `@PersistenceMapping` annotations and generates a
 * `_LirpTableDef` object per annotated class. The generated object contains the table/collection name
 * and column definitions derived from the entity's properties — no runtime reflection is involved.
 *
 * At runtime, this annotation has no effect. The generated `_LirpTableDef` object drives schema
 * creation and SQL query generation via JetBrains Exposed.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * The [name] parameter controls the table or collection name used in the persistence backend. If
 * left empty, the KSP processor derives the name by converting the class name to snake_case
 * (e.g., `CustomerOrder` becomes `customer_order`).
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@PersistenceMapping` annotations have no effect.
 *
 * Example:
 * ```kotlin
 * // build.gradle.kts: ksp(project(":lirp-ksp"))
 *
 * @PersistenceMapping(name = "customers")
 * data class Customer(
 *     override val id: Int,
 *     val name: String
 * ) : ReactiveEntityBase<Int, Customer>()
 *
 * // Convention-based name (generates table "customer_order"):
 * @PersistenceMapping
 * data class CustomerOrder(
 *     override val id: Long,
 *     val customerId: Int
 * ) : ReactiveEntityBase<Long, CustomerOrder>()
 * ```
 *
 * @param name The table or collection name in the persistence backend. Defaults to a snake_case
 *   conversion of the class name when empty.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PersistenceMapping(val name: String = "")
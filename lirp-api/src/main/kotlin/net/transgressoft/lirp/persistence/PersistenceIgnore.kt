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
 * Excludes a property from all persistence mechanisms.
 *
 * At compile time, the LIRP KSP processor skips any property annotated with `@PersistenceIgnore`
 * during table definition and schema generation. The property will not appear as a column in
 * generated `_LirpTableDef` objects and will not be read from or written to the persistence backend.
 *
 * At runtime, this annotation has no effect. Exclusion is enforced entirely at the code generation
 * stage.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@PersistenceIgnore` annotations have no effect and the property will be included in persistence
 * as if the annotation were absent.
 *
 * Example:
 * ```kotlin
 * // build.gradle.kts: ksp(project(":lirp-ksp"))
 *
 * @PersistenceMapping
 * data class Customer(
 *     override val id: Int,
 *     val name: String,
 *     @PersistenceIgnore val displayLabel: String get() = "[$id] $name"
 * ) : ReactiveEntityBase<Int, Customer>()
 * ```
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class PersistenceIgnore
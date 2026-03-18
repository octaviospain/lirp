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
 * Marks a repository subclass for zero-config auto-registration in the global registry map.
 *
 * At compile time, the LIRP KSP processor scans for `@LirpRepository` annotations and generates a
 * [LirpRegistryInfo] implementation per annotated class. The generated class is named
 * `{ClassName}_LirpRegistryInfo` and lives in the same package as the annotated repository.
 *
 * At runtime, when the repository is constructed, the generated class is loaded via a
 * convention-based class lookup (`{ClassName}_LirpRegistryInfo`) and its [LirpRegistryInfo.entityClass]
 * is used to register the repository in the global registry map. The registration is automatic —
 * no manual registration call is needed.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * A second repository for the same entity type will throw [IllegalStateException] at construction
 * time. Closing the repository deregisters it automatically.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@LirpRepository` annotations have no effect.
 *
 * Example:
 * ```kotlin
 * // build.gradle.kts: ksp(project(":lirp-ksp"))
 *
 * @LirpRepository
 * class CustomerRepo : VolatileRepository<Int, Customer>("Customers")
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class LirpRepository
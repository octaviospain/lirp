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
 * Marks a property for O(1) equality index maintenance in repositories that extend `RegistryBase`.
 *
 * At compile time, the LIRP KSP processor scans for `@Indexed` annotations and generates a
 * [LirpIndexAccessor] implementation per entity class. The generated accessor contains direct
 * property getter lambdas — no runtime reflection is involved.
 *
 * At runtime, when an entity is first added to a repository, the generated accessor is loaded via
 * a convention-based class lookup (`{EntityClassName}_LirpIndexAccessor`) and its [IndexEntry]
 * descriptors populate the secondary index structure. Subsequent calls to [Registry.findByIndex]
 * or [Registry.findFirstByIndex] resolve in O(1) without scanning the collection.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * The [name] parameter controls the index key used in [Registry.findByIndex]. If left empty, the
 * Kotlin property name is used automatically.
 *
 * Null property values are silently skipped — entities with a null value for an indexed property
 * are simply not included in that index.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@Indexed` annotations have no effect and [Registry.findByIndex] throws [IllegalArgumentException].
 *
 * Example:
 * ```kotlin
 * // build.gradle.kts: ksp(project(":lirp-ksp"))
 *
 * data class Product(
 *     override val id: Int,
 *     @Indexed val category: String,
 *     @Indexed(name = "sku") val stockKeepingUnit: String
 * ) : IdentifiableEntity<Int>
 *
 * val repo = VolatileRepository<Int, Product>()
 * repo.add(Product(1, "electronics", "SKU-001"))
 *
 * val electronics = repo.findByIndex("category", "electronics")
 * val bySku = repo.findByIndex("sku", "SKU-001")
 * ```
 *
 * @param name The name of the index. Defaults to the property name when empty.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Indexed(val name: String = "")
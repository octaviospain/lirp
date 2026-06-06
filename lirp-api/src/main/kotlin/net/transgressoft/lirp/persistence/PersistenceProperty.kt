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

import kotlin.reflect.KClass

/**
 * Configures how an entity property maps to a persistence field or column.
 *
 * At compile time, the LIRP KSP processor reads this annotation's parameters to generate precise
 * column definitions in the `_LirpTableDef` object. Parameters not explicitly set fall back to
 * backend-specific defaults (e.g., `VARCHAR(255)` for unbounded strings).
 *
 * At runtime, this annotation has no effect. The generated `_LirpTableDef` drives schema creation
 * and query generation via JetBrains Exposed.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * Nullability is inferred from Kotlin's type system: `String` maps to NOT NULL, `String?` maps
 * to nullable. There is no separate `nullable` parameter.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@PersistenceProperty` annotations have no effect.
 *
 * Example:
 * ```kotlin
 * // build.gradle.kts: ksp(project(":lirp-ksp"))
 *
 * @PersistenceMapping
 * data class Product(
 *     override val id: Int,
 *     @PersistenceProperty(name = "full_name", length = 255) val name: String,
 *     @PersistenceProperty(precision = 10, scale = 2) val price: BigDecimal
 * ) : ReactiveEntityBase<Int, Product>()
 * ```
 *
 * @param name The column or field name in the persistence backend. Defaults to the Kotlin property
 *   name when empty.
 * @param length The maximum character length for string columns (e.g., `VARCHAR(255)`). A value of
 *   `-1` means no explicit length constraint is set; the backend default applies.
 * @param precision The total number of significant digits for numeric columns. A value of `-1` means
 *   no explicit precision is set; the backend default applies.
 * @param scale The number of digits to the right of the decimal point for numeric columns. A value
 *   of `-1` means no explicit scale is set; the backend default applies.
 * @param type An optional override for the SQL type name (e.g., `"TEXT"`, `"JSONB"`). When empty,
 *   the KSP processor infers the type from the Kotlin property type.
 * @param converter A [ColumnConverter] singleton (`object`) that maps a non-scalar domain type to a
 *   persistence-facing scalar. The default value [ColumnConverter] itself is a sentinel meaning
 *   "no converter declared — use type inference from the Kotlin property type." When a converter
 *   is supplied, the KSP processor derives the column type from [ColumnConverter.sqlType] and
 *   refines it with any compatible annotation hints: a positive `length` on a textual base type
 *   widens it to `VARCHAR(length)`; `precision`/`scale` on a numeric base type widen it to
 *   `DECIMAL(precision, scale)`; a non-empty `type` always overrides the converter's base type
 *   to honor explicit consumer intent. Hints incompatible with the converter's base type
 *   (for example, `length` on an integer converter) are reported as KSP errors at compile time.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class PersistenceProperty(
    val name: String = "",
    val length: Int = -1,
    val precision: Int = -1,
    val scale: Int = -1,
    val type: String = "",
    val converter: KClass<out ColumnConverter<*, *>> = ColumnConverter::class
)
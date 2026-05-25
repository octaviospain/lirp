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
 * Bidirectional mapping between a domain type [D] and a persistence-facing scalar [S].
 *
 * Used by `@PersistenceProperty(converter = ...)` to persist non-scalar domain types
 * (such as `java.nio.file.Path`, `java.time.Duration`, or custom value wrappers)
 * without forcing the entity to expose a scalar field. KSP-generated `_LirpTableDef`
 * code reads [sqlType] to derive the column shape, routes writes through [toSql],
 * and reconstructs the domain value via [fromSql] on row reads.
 *
 * ### Implementation requirements
 *
 * Implementations must be Kotlin `object` declarations (singletons). KSP-generated
 * code references the converter by its fully qualified name and never instantiates
 * it. Declaring a converter as a regular class, abstract class, sealed parent, or
 * anonymous object is rejected at compile time by the KSP processor.
 *
 * ### Supported scalar types
 *
 * [S] must resolve to one of the primitives natively supported by [ColumnType]:
 * `String`, `Int`, `Long`, `Short`, `Byte`, `Boolean`, `Double`, `Float`,
 * `java.math.BigDecimal`, `java.util.UUID`, `java.time.LocalDate`, and
 * `java.time.LocalDateTime`. Any other type is rejected at compile time so that
 * mismatches surface before a row read.
 *
 * ### Nullability
 *
 * Nullability is handled by codegen, not by the converter. The KSP-generated
 * read/write code branches on the property's declared nullability and never invokes
 * [toSql] or [fromSql] with a `null` argument. Implementations may therefore declare
 * non-null `D` and `S` parameters and return types unconditionally.
 *
 * ### Scope
 *
 * Currently consumed by the SQL persistence path only. JSON-backed entities continue
 * to rely on `kotlinx.serialization` (consumer-provided `KSerializer<T>`) for
 * non-scalar fields.
 *
 * ### Example
 *
 * A converter that persists `java.nio.file.Path` as a textual column:
 *
 * ```kotlin
 * object PathConverter : ColumnConverter<Path, String> {
 *     override val sqlType = ColumnType.TextType
 *     override fun toSql(value: Path): String = value.toString()
 *     override fun fromSql(raw: String): Path = Path.of(raw)
 * }
 *
 * @PersistenceMapping
 * data class Track(
 *     override val id: Int,
 *     @PersistenceProperty(converter = PathConverter::class, length = 1024)
 *     val location: Path
 * ) : ReactiveEntityBase<Int, Track>()
 * ```
 *
 * @param D the domain type exposed by the entity property.
 * @param S the persistence-facing scalar type bound to a SQL column.
 */
interface ColumnConverter<D, S : Any> {
    /**
     * The [ColumnType] used by KSP-generated schema and read/write code for the column
     * backing this converter. Annotation hints (`length`, `precision`, `scale`, `type`)
     * may refine this value when compatible; see `@PersistenceProperty.converter`.
     */
    val sqlType: ColumnType

    /**
     * Converts a domain [value] to its persistence-facing representation [S]. Invoked
     * by the SQL write pipeline before binding the parameter to the prepared statement.
     */
    fun toSql(value: D): S

    /**
     * Reconstructs the domain value from its persistence-facing [raw] representation.
     * Invoked by the SQL read pipeline after the column has been read from the JDBC
     * result set.
     */
    fun fromSql(raw: S): D
}
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
 * Persistence-agnostic column type hierarchy used by KSP-generated [LirpTableDef] descriptors.
 *
 * Singleton types ([IntType], [LongType], [TextType], [BooleanType], [DoubleType], [FloatType],
 * [UuidType], [DateType], [DateTimeType]) require no parameters. Parameterized types ([VarcharType],
 * [DecimalType], [EnumType]) carry the additional metadata needed for schema generation.
 *
 * These types are referenced only from generated `_LirpTableDef` objects and from the `lirp-sql`
 * module, which translates them into JetBrains Exposed column definitions.
 */
sealed class ColumnType {
    /** 32-bit signed integer column. */
    data object IntType : ColumnType()

    /** 64-bit signed integer column. */
    data object LongType : ColumnType()

    /** Unbounded text column, mapped to the dialect's largest character type. */
    data object TextType : ColumnType()

    /** Boolean column, mapped to the dialect's native boolean or its closest equivalent. */
    data object BooleanType : ColumnType()

    /** Double-precision floating-point column. */
    data object DoubleType : ColumnType()

    /** Single-precision floating-point column. */
    data object FloatType : ColumnType()

    /**
     * UUID column whose physical storage is selected by the SQL dialect adapter in `lirp-sql`.
     *
     * | Dialect | Storage | JDBC bind/read |
     * |---------|---------|----------------|
     * | H2 / PostgreSQL | native `UUID` | `setObject(i, uuid)` / `getObject(c, UUID::class.java)` |
     * | MySQL / MariaDB | `BINARY(16)` | `setBytes(i, bigEndianBytes)` / `getBytes(c)` |
     * | SQLite | `BLOB(16)` | `setBytes(i, bigEndianBytes)` / `getBytes(c)` |
     *
     * Out-of-band JDBC consumers should use `bindUuid` / `readUuid` in
     * `net.transgressoft.lirp.persistence.sql` rather than binding canonical hex
     * strings, which do not match the byte-backed storage used by MySQL, MariaDB,
     * and SQLite.
     */
    data object UuidType : ColumnType()

    /** Date-only column (no time component). */
    data object DateType : ColumnType()

    /** Timestamp column carrying both date and time. */
    data object DateTimeType : ColumnType()

    /**
     * @param length The maximum number of characters for the VARCHAR column.
     */
    data class VarcharType(val length: Int) : ColumnType() {
        init {
            require(length > 0) { "Varchar length must be > 0" }
        }
    }

    /**
     * @param precision The total number of significant digits.
     * @param scale The number of digits to the right of the decimal point.
     */
    data class DecimalType(val precision: Int, val scale: Int) : ColumnType() {
        init {
            require(precision > 0) { "Decimal precision must be > 0" }
            require(scale >= 0) { "Decimal scale must be >= 0" }
            require(scale <= precision) { "Decimal scale must be <= precision" }
        }
    }

    /**
     * @param enumClassFqn The fully qualified name of the Kotlin enum class.
     */
    data class EnumType(val enumClassFqn: String) : ColumnType() {
        init {
            require(enumClassFqn.isNotBlank()) { "Enum FQN must not be blank" }
        }
    }
}
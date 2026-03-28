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
    data object IntType : ColumnType()

    data object LongType : ColumnType()

    data object TextType : ColumnType()

    data object BooleanType : ColumnType()

    data object DoubleType : ColumnType()

    data object FloatType : ColumnType()

    data object UuidType : ColumnType()

    data object DateType : ColumnType()

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
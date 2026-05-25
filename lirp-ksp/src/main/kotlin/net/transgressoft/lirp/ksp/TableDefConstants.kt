/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.lirp.ksp

internal const val PERSISTENCE_MAPPING_FQN = "net.transgressoft.lirp.persistence.PersistenceMapping"
internal const val PERSISTENCE_PROPERTY_FQN = "net.transgressoft.lirp.persistence.PersistenceProperty"
internal const val VERSION_FQN = "net.transgressoft.lirp.persistence.Version"
internal const val SQL_TABLE_DEF_FQN = "net.transgressoft.lirp.persistence.sql.SqlTableDef"
internal const val UUID_FQN = "java.util.UUID"
internal const val LOCAL_DATE_FQN = "java.time.LocalDate"
internal const val LOCAL_DATE_TIME_FQN = "java.time.LocalDateTime"
internal const val KOTLIN_STRING_FQN = "kotlin.String"
internal const val KOTLIN_INT_FQN = "kotlin.Int"
internal const val KOTLIN_LONG_FQN = "kotlin.Long"
internal const val KOTLIN_SHORT_FQN = "kotlin.Short"
internal const val KOTLIN_BYTE_FQN = "kotlin.Byte"
internal const val KOTLIN_BOOLEAN_FQN = "kotlin.Boolean"
internal const val KOTLIN_DOUBLE_FQN = "kotlin.Double"
internal const val KOTLIN_FLOAT_FQN = "kotlin.Float"
internal const val KOTLIN_UUID_FQN = "kotlin.UUID"
internal const val BIG_DECIMAL_FQN = "java.math.BigDecimal"
internal const val COLUMN_TYPE_INT_EXPR = "ColumnType.IntType"
internal const val COLUMN_TYPE_TEXT_EXPR = "ColumnType.TextType"
internal const val COLUMN_TYPE_LONG_EXPR = "ColumnType.LongType"
internal const val COLUMN_TYPE_BOOLEAN_EXPR = "ColumnType.BooleanType"
internal const val COLUMN_TYPE_DOUBLE_EXPR = "ColumnType.DoubleType"
internal const val COLUMN_TYPE_FLOAT_EXPR = "ColumnType.FloatType"
internal const val COLUMN_TYPE_UUID_EXPR = "ColumnType.UuidType"
internal const val COLUMN_TYPE_DATE_EXPR = "ColumnType.DateType"
internal const val COLUMN_TYPE_DATETIME_EXPR = "ColumnType.DateTimeType"
internal const val LIST_ITEM_SEPARATOR = ",\n        "
internal const val COLUMN_CONVERTER_FQN = "net.transgressoft.lirp.persistence.ColumnConverter"
internal const val EMBEDDABLE_FQN = "net.transgressoft.lirp.persistence.Embeddable"
internal const val EMBEDDED_FQN = "net.transgressoft.lirp.persistence.Embedded"

// Allow-list of supported S type FQNs for ColumnConverter<D, S>, mapped to the canonical
// ColumnType expression. Keys gate D-08 validation; values seed the codegen path that
// emits the column type from the converter's base scalar.
internal fun String.toSnakeCase(): String =
    replace(Regex("([a-z\\d])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .lowercase()

internal val SUPPORTED_CONVERTER_S_TYPES: Map<String, String> =
    mapOf(
        KOTLIN_STRING_FQN to COLUMN_TYPE_TEXT_EXPR,
        KOTLIN_INT_FQN to COLUMN_TYPE_INT_EXPR,
        KOTLIN_LONG_FQN to COLUMN_TYPE_LONG_EXPR,
        KOTLIN_SHORT_FQN to COLUMN_TYPE_INT_EXPR,
        KOTLIN_BYTE_FQN to COLUMN_TYPE_INT_EXPR,
        KOTLIN_BOOLEAN_FQN to COLUMN_TYPE_BOOLEAN_EXPR,
        KOTLIN_DOUBLE_FQN to COLUMN_TYPE_DOUBLE_EXPR,
        KOTLIN_FLOAT_FQN to COLUMN_TYPE_FLOAT_EXPR,
        BIG_DECIMAL_FQN to "ColumnType.DecimalType",
        UUID_FQN to COLUMN_TYPE_UUID_EXPR,
        LOCAL_DATE_FQN to COLUMN_TYPE_DATE_EXPR,
        LOCAL_DATE_TIME_FQN to COLUMN_TYPE_DATETIME_EXPR
    )
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

import com.google.devtools.ksp.symbol.KSClassDeclaration

internal const val REACTIVE_ENTITY_FQN = "net.transgressoft.lirp.entity.ReactiveEntity"
internal const val PERSISTENCE_MAPPING_FQN = "net.transgressoft.lirp.persistence.PersistenceMapping"
internal const val PERSISTENCE_PROPERTY_FQN = "net.transgressoft.lirp.persistence.PersistenceProperty"
internal const val VERSION_FQN = "net.transgressoft.lirp.persistence.Version"
internal const val SQL_TABLE_DEF_FQN = "net.transgressoft.lirp.persistence.sql.SqlTableDef"
internal const val UUID_FQN = "java.util.UUID"
internal const val LOCAL_DATE_FQN = "java.time.LocalDate"
internal const val LOCAL_DATE_TIME_FQN = "java.time.LocalDateTime"
internal const val DURATION_FQN = "java.time.Duration"
internal const val INSTANT_FQN = "java.time.Instant"
internal const val OFFSET_DATE_TIME_FQN = "java.time.OffsetDateTime"
internal const val PATH_FQN = "java.nio.file.Path"
internal const val URI_FQN = "java.net.URI"
internal const val URL_FQN = "java.net.URL"
internal const val BIG_INTEGER_FQN = "java.math.BigInteger"
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

// Closing-brace source fragments emitted by [TableDefSourceEmitter]. The indentation is part of
// the generated layout: [INNER_BLOCK_CLOSE] closes a block nested inside a method body (a `when`
// branch or `withEventsDisabled { … }` wrapper), [METHOD_CLOSE] closes an override's body.
internal const val INNER_BLOCK_CLOSE = "        }"
internal const val METHOD_CLOSE = "    }"

internal const val COLUMN_CONVERTER_FQN = "net.transgressoft.lirp.persistence.ColumnConverter"
internal const val EMBEDDABLE_FQN = "net.transgressoft.lirp.persistence.Embeddable"
internal const val EMBEDDED_FQN = "net.transgressoft.lirp.persistence.Embedded"
internal const val ELEMENT_COLLECTION_FQN = "net.transgressoft.lirp.persistence.ElementCollection"
internal const val PERSISTENCE_IGNORE_FQN = "net.transgressoft.lirp.persistence.PersistenceIgnore"
internal const val PERSISTENCE_CREATOR_FQN = "net.transgressoft.lirp.persistence.PersistenceCreator"
internal const val KOTLIN_LIST_FQN = "kotlin.collections.List"
internal const val KOTLIN_SET_FQN = "kotlin.collections.Set"
internal const val KOTLIN_MUTABLE_LIST_FQN = "kotlin.collections.MutableList"
internal const val KOTLIN_MUTABLE_SET_FQN = "kotlin.collections.MutableSet"
internal const val KOTLIN_MAP_FQN = "kotlin.collections.Map"
internal const val TRANSIENT_FQN = "kotlin.jvm.Transient"
internal const val KOTLINX_SERIALIZATION_TRANSIENT_FQN = "kotlinx.serialization.Transient"

internal val LIRP_COLLECTION_FQNS: Set<String> =
    setOf(
        "kotlin.collections.List",
        "kotlin.collections.MutableList",
        "kotlin.collections.Set",
        "kotlin.collections.MutableSet",
        "kotlin.collections.Collection",
        "kotlin.collections.MutableCollection",
        "kotlin.collections.Map",
        "kotlin.collections.MutableMap"
    )

// Allow-list of supported S type FQNs for ColumnConverter<D, S>, mapped to the canonical
// ColumnType expression. Keys gate validation; values seed the codegen path that
// emits the column type from the converter's base scalar.
internal fun String.toSnakeCase(): String =
    replace(Regex("([a-z\\d])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .lowercase()

/**
 * Resolves the SQL table name for a class, honoring an explicit `name` argument on
 * `@PersistenceMapping` when present and falling back to snake_case of the simple class name.
 * Extracted here to avoid duplication between [TableDefProcessor] and [ForeignKeyAnalyzer].
 */
internal fun resolveTableName(classDecl: KSClassDeclaration, className: String): String {
    val mappingAnnotation =
        classDecl.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == PERSISTENCE_MAPPING_FQN
        }
    val customName = mappingAnnotation?.arguments?.firstOrNull { it.name?.asString() == "name" }?.value as? String
    return if (!customName.isNullOrEmpty()) customName else className.toSnakeCase()
}

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

// The 8 Kotlin primitives encodable natively by kotlinx.serialization without contextual
// serializer wiring. JDK-bridge types (BigDecimal, UUID, LocalDate, LocalDateTime) require
// custom KSerializer instances and are therefore rejected for @ElementCollection element converters.
internal val ELEMENT_COLLECTION_S_TYPES: Set<String> =
    setOf(
        KOTLIN_STRING_FQN, KOTLIN_INT_FQN, KOTLIN_LONG_FQN, KOTLIN_SHORT_FQN,
        KOTLIN_BYTE_FQN, KOTLIN_BOOLEAN_FQN, KOTLIN_DOUBLE_FQN, KOTLIN_FLOAT_FQN
    )

private const val DEFAULT_CONVERTER_PACKAGE = "net.transgressoft.lirp.persistence"

// Built-in default converters keyed by the domain type FQN they map. A column whose declared type
// matches one of these keys — and which carries no explicit @PersistenceProperty(converter = …) —
// is bound to the named converter exactly as if the consumer had annotated it, so the existing
// converter codegen path (sqlType refinement, fromSql on read, toSql on write) drives the column.
//
// Resolution is a fallback: explicit converter resolution runs first in the column builders, so a
// consumer-supplied converter for the same type always wins. Types lirp already maps natively
// (LocalDate, LocalDateTime, UUID, BigDecimal) are intentionally absent — adding them here would
// duplicate the FQN-driven inference path.
internal val DEFAULT_CONVERTERS: Map<String, ConverterInfo> =
    mapOf(
        PATH_FQN to ConverterInfo("$DEFAULT_CONVERTER_PACKAGE.PathColumnConverter", KOTLIN_STRING_FQN),
        DURATION_FQN to ConverterInfo("$DEFAULT_CONVERTER_PACKAGE.DurationColumnConverter", KOTLIN_LONG_FQN),
        INSTANT_FQN to ConverterInfo("$DEFAULT_CONVERTER_PACKAGE.InstantColumnConverter", KOTLIN_STRING_FQN),
        OFFSET_DATE_TIME_FQN to ConverterInfo("$DEFAULT_CONVERTER_PACKAGE.OffsetDateTimeColumnConverter", KOTLIN_STRING_FQN),
        URI_FQN to ConverterInfo("$DEFAULT_CONVERTER_PACKAGE.UriColumnConverter", KOTLIN_STRING_FQN),
        URL_FQN to ConverterInfo("$DEFAULT_CONVERTER_PACKAGE.UrlColumnConverter", KOTLIN_STRING_FQN),
        BIG_INTEGER_FQN to ConverterInfo("$DEFAULT_CONVERTER_PACKAGE.BigIntegerColumnConverter", KOTLIN_STRING_FQN)
    )
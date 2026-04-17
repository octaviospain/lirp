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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import java.math.BigDecimal
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Manual [SqlTableDef] for [AllTypesEntity] covering all 12 [ColumnType] variants.
 *
 * The `uuid` column uses [ColumnType.UuidType] which Exposed translates to the native UUID type per dialect.
 * The `enum_val` column uses [ColumnType.EnumType] which is stored as `VARCHAR(255)` for portability.
 */
@OptIn(ExperimentalUuidApi::class)
object AllTypesTableDef : SqlTableDef<AllTypesEntity> {
    override val tableName = "all_types"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("long_val", ColumnType.LongType, nullable = false, primaryKey = false),
            ColumnDef("text_val", ColumnType.TextType, nullable = false, primaryKey = false),
            ColumnDef("bool_val", ColumnType.BooleanType, nullable = false, primaryKey = false),
            ColumnDef("double_val", ColumnType.DoubleType, nullable = false, primaryKey = false),
            ColumnDef("float_val", ColumnType.FloatType, nullable = false, primaryKey = false),
            ColumnDef("uuid_val", ColumnType.UuidType, nullable = false, primaryKey = false),
            ColumnDef("date_val", ColumnType.DateType, nullable = false, primaryKey = false),
            ColumnDef("date_time_val", ColumnType.DateTimeType, nullable = false, primaryKey = false),
            ColumnDef("varchar_val", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("decimal_val", ColumnType.DecimalType(10, 2), nullable = false, primaryKey = false),
            ColumnDef("enum_val", ColumnType.EnumType("com.example.Status"), nullable = false, primaryKey = false)
        )

    override fun fromRow(row: ResultRow, table: Table): AllTypesEntity {
        val cols = table.columns.associateBy { it.name }

        @Suppress("UNCHECKED_CAST")
        return AllTypesEntity(row[cols["id"]!! as Column<Int>]).also { e ->
            e.longVal = row[cols["long_val"]!! as Column<Long>]
            e.textVal = row[cols["text_val"]!! as Column<String>]
            e.boolVal = row[cols["bool_val"]!! as Column<Boolean>]
            e.doubleVal = row[cols["double_val"]!! as Column<Double>]
            e.floatVal = row[cols["float_val"]!! as Column<Float>]
            e.uuidVal = row[cols["uuid_val"]!! as Column<Uuid>]
            e.dateVal = row[cols["date_val"]!! as Column<LocalDate>]
            e.dateTimeVal = row[cols["date_time_val"]!! as Column<LocalDateTime>]
            e.varcharVal = row[cols["varchar_val"]!! as Column<String>]
            e.decimalVal = row[cols["decimal_val"]!! as Column<BigDecimal>]
            e.enumVal = row[cols["enum_val"]!! as Column<String>]
        }
    }

    override fun toParams(entity: AllTypesEntity, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["long_val"]!! to entity.longVal,
            cols["text_val"]!! to entity.textVal,
            cols["bool_val"]!! to entity.boolVal,
            cols["double_val"]!! to entity.doubleVal,
            cols["float_val"]!! to entity.floatVal,
            cols["uuid_val"]!! to entity.uuidVal,
            cols["date_val"]!! to entity.dateVal,
            cols["date_time_val"]!! to entity.dateTimeVal,
            cols["varchar_val"]!! to entity.varcharVal,
            cols["decimal_val"]!! to entity.decimalVal,
            cols["enum_val"]!! to entity.enumVal
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: AllTypesEntity, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.longVal = row[cols["long_val"]!! as Column<Long>]
        entity.textVal = row[cols["text_val"]!! as Column<String>]
        entity.boolVal = row[cols["bool_val"]!! as Column<Boolean>]
        entity.doubleVal = row[cols["double_val"]!! as Column<Double>]
        entity.floatVal = row[cols["float_val"]!! as Column<Float>]
        entity.uuidVal = row[cols["uuid_val"]!! as Column<Uuid>]
        entity.dateVal = row[cols["date_val"]!! as Column<LocalDate>]
        entity.dateTimeVal = row[cols["date_time_val"]!! as Column<LocalDateTime>]
        entity.varcharVal = row[cols["varchar_val"]!! as Column<String>]
        entity.decimalVal = row[cols["decimal_val"]!! as Column<BigDecimal>]
        entity.enumVal = row[cols["enum_val"]!! as Column<String>]
        // id is PK — immutable on AllTypesEntity
    }
}
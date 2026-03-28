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
import net.transgressoft.lirp.persistence.LirpTableDef
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.ExperimentalUuidApi

/**
 * Converts a persistence-agnostic [LirpTableDef] descriptor into a live JetBrains Exposed [Table]
 * object with all columns registered and the primary key configured.
 *
 * The resulting [ExposedTable] wraps both the [Table] instance and a column-name-to-column map,
 * which allows [SqlTableDef] implementations to perform column lookups by name at runtime during
 * `fromRow` and `toParams` operations.
 *
 * All 12 [ColumnType] variants are mapped exhaustively. [ColumnType.EnumType] is stored as a
 * `VARCHAR(255)` because the actual enum class is only known at KSP compile time.
 */
class ExposedTableInterpreter {

    /**
     * Interprets the given [LirpTableDef] descriptor into a live Exposed [ExposedTable].
     *
     * @param def The persistence descriptor to interpret.
     * @return An [ExposedTable] containing the Exposed [Table] and a column-by-name index.
     */
    fun interpret(def: LirpTableDef<*>): ExposedTable {
        val columnsByName = mutableMapOf<String, Column<*>>()
        val pkDefs = def.columns.filter { it.primaryKey }
        require(pkDefs.size <= 1) { "Composite primary keys are not supported by SqlRepository" }
        val pkDef = pkDefs.singleOrNull()

        val table = LirpDynamicTable(def.tableName, def.columns, columnsByName, pkDef)

        return ExposedTable(table, columnsByName)
    }
}

/**
 * Internal Exposed [Table] subclass that registers columns from a list of [ColumnDef] descriptors
 * and exposes a column-by-name index populated during construction.
 */
@OptIn(ExperimentalUuidApi::class)
private class LirpDynamicTable(
    tableName: String,
    columnDefs: List<ColumnDef>,
    columnsByName: MutableMap<String, Column<*>>,
    pkDef: ColumnDef?
) : Table(tableName) {

    override val primaryKey: PrimaryKey?

    init {
        for (col in columnDefs) {
            val column = buildColumn(col)
            columnsByName[col.name] = column
        }
        primaryKey =
            pkDef?.let { pk ->
                columnsByName[pk.name]?.let { pkCol -> PrimaryKey(pkCol) }
            }
    }

    private fun buildColumn(col: ColumnDef): Column<*> {
        val raw: Column<*> =
            when (val type = col.type) {
                is ColumnType.IntType -> integer(col.name)
                is ColumnType.LongType -> long(col.name)
                is ColumnType.TextType -> text(col.name)
                is ColumnType.BooleanType -> bool(col.name)
                is ColumnType.DoubleType -> double(col.name)
                is ColumnType.FloatType -> float(col.name)
                is ColumnType.UuidType -> uuid(col.name)
                is ColumnType.DateType -> date(col.name)
                is ColumnType.DateTimeType -> datetime(col.name)
                is ColumnType.VarcharType -> varchar(col.name, type.length)
                is ColumnType.DecimalType -> decimal(col.name, type.precision, type.scale)
                is ColumnType.EnumType -> varchar(col.name, 255)
            }
        @Suppress("UNCHECKED_CAST")
        return if (col.nullable) (raw as Column<Any>).nullable() else raw
    }
}

/**
 * Wraps a live Exposed [Table] together with a column-by-name index produced by [ExposedTableInterpreter].
 *
 * @property table The Exposed [Table] with all columns and primary key configured.
 * @property columnsByName A map from column name to the corresponding [Column] instance,
 *   enabling [SqlTableDef] implementations to look up columns by name at runtime.
 */
data class ExposedTable(
    val table: Table,
    val columnsByName: Map<String, Column<*>>
)
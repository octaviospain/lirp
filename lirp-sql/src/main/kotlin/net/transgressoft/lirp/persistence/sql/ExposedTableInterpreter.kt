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
 *
 * The resulting [ExposedTable] also exposes the single `@Version`-flagged column (if any) as a
 * typed `Column<Long>` for use by [SqlRepository] when composing versioned UPDATE/DELETE predicates.
 */
class ExposedTableInterpreter {

    /**
     * Interprets the given [LirpTableDef] descriptor into a live Exposed [ExposedTable].
     *
     * @param def The persistence descriptor to interpret.
     * @return An [ExposedTable] containing the Exposed [Table], a column-by-name index, and the
     *   typed `@Version` column reference (or `null` when no column is flagged `isVersion = true`).
     */
    fun interpret(def: LirpTableDef<*>): ExposedTable {
        val columnsByName = mutableMapOf<String, Column<*>>()
        val pkDefs = def.columns.filter { it.primaryKey }
        require(pkDefs.size <= 1) { "Composite primary keys are not supported by SqlRepository" }
        val pkDef = pkDefs.singleOrNull()

        val versionDefs = def.columns.filter { it.isVersion }
        require(versionDefs.size <= 1) {
            "At most one @Version column is allowed per entity; found ${versionDefs.size} on ${def.tableName}"
        }
        val versionDef = versionDefs.singleOrNull()
        // Manually-authored SqlTableDefs bypass KSP's D-15 validation. Enforce the Long type
        // requirement here at runtime so a misconfigured isVersion flag fails loudly at
        // interpret() time rather than silently breaking optimistic-lock predicates later.
        require(versionDef == null || versionDef.type is ColumnType.LongType) {
            "@Version column '${versionDef?.name}' on ${def.tableName} must use ColumnType.LongType " +
                "(got ${versionDef?.type}). Manual SqlTableDef authors must match the KSP D-15 contract."
        }

        val table = LirpDynamicTable(def.tableName, def.columns, columnsByName, pkDef)

        // Safe: KSP validation (D-15) enforces @Version columns map to ColumnType.LongType, which
        // buildColumn always produces via long(col.name) — yielding Column<Long>.
        @Suppress("UNCHECKED_CAST")
        val versionCol: Column<Long>? = versionDef?.let { columnsByName[it.name] as? Column<Long> }
        return ExposedTable(table, columnsByName, versionCol)
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
        // Safe: raw was just created by this method from the declared ColumnType. Exposed's nullable()
        // extension requires Column<Any> as receiver but buildColumn produces Column<*>.
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
 * @property versionCol The typed `@Version` column (`Column<Long>`), or `null` when the tableDef
 *   has no column flagged `isVersion = true`. Consumed by [SqlRepository] to compose versioned
 *   UPDATE/DELETE WHERE clauses and to read the canonical version during conflict recovery.
 */
data class ExposedTable(
    val table: Table,
    val columnsByName: Map<String, Column<*>>,
    val versionCol: Column<Long>? = null
)
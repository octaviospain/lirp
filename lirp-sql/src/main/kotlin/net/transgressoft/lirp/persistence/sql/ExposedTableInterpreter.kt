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

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.LirpTableDef
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
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
     * Interprets the given [JunctionTableDef] descriptor into a live Exposed [ExposedJunctionTable].
     *
     * The returned [Table] has a composite primary key over `(parent_id, item_id)` and an optional
     * `position` column when `descriptor.isOrdered` is `true`. The columns are plain (typed) columns,
     * not Exposed `reference()` columns — foreign-key constraints are installed later via
     * `SqlRepository.installJunctionForeignKeys()` once every parent / item entity table has
     * materialised. This allows junction tables to be created during repository init even when the
     * referenced entity table belongs to a not-yet-constructed [SqlRepository].
     *
     * @param descriptor The junction descriptor to interpret.
     * @return An [ExposedJunctionTable] exposing the [Table] handle plus typed `parent_id`,
     *   `item_id`, and (for ordered descriptors) `position` column references.
     */
    internal fun interpretJunction(descriptor: JunctionTableDef): ExposedJunctionTable {
        val parentColDef =
            descriptor.columns.firstOrNull { it.name == "parent_id" }
                ?: error("JunctionTableDef '${descriptor.tableName}' is missing a 'parent_id' column")
        val itemColDef =
            descriptor.columns.firstOrNull { it.name == "item_id" }
                ?: error("JunctionTableDef '${descriptor.tableName}' is missing an 'item_id' column")
        val positionColDef = descriptor.columns.firstOrNull { it.name == "position" }
        require(!descriptor.isOrdered || positionColDef != null) {
            "JunctionTableDef '${descriptor.tableName}' is ordered but does not declare a 'position' column"
        }

        val columnsByName = mutableMapOf<String, Column<*>>()
        val table =
            LirpDynamicJunctionTable(
                tableName = descriptor.tableName,
                parentColDef = parentColDef,
                itemColDef = itemColDef,
                positionColDef = if (descriptor.isOrdered) positionColDef else null,
                columnsByName = columnsByName
            )

        @Suppress("UNCHECKED_CAST")
        val parentIdCol = columnsByName.getValue("parent_id") as Column<Any>

        @Suppress("UNCHECKED_CAST")
        val itemIdCol = columnsByName.getValue("item_id") as Column<Any>

        @Suppress("UNCHECKED_CAST")
        val positionCol: Column<Int>? =
            if (descriptor.isOrdered) columnsByName["position"] as? Column<Int> else null

        return ExposedJunctionTable(
            descriptor = descriptor,
            table = table,
            parentIdCol = parentIdCol,
            itemIdCol = itemIdCol,
            positionCol = positionCol
        )
    }

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
        // Manually-authored SqlTableDefs bypass KSP's validation. Enforce the Long type
        // requirement here at runtime so a misconfigured isVersion flag fails loudly at
        // interpret() time rather than silently breaking optimistic-lock predicates later.
        require(versionDef == null || versionDef.type is ColumnType.LongType) {
            "@Version column '${versionDef?.name}' on ${def.tableName} must use ColumnType.LongType " +
                "(got ${versionDef?.type}). Manual SqlTableDef authors must match the KSP version-column contract."
        }

        val table = LirpDynamicTable(def.tableName, def.columns, columnsByName, pkDef)

        // Safe: KSP validation enforces @Version columns map to ColumnType.LongType, which
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

        // defaultExpression is not applied as a DDL DEFAULT clause. MySQL rejects DEFAULT on TEXT/BLOB
        // columns unconditionally, and the generated toParams always supplies explicit values for
        // element-collection columns — the DDL DEFAULT is therefore unreachable at runtime. Skipping
        // it keeps DDL portable across all supported dialects without per-dialect branching.
        // Safe: Exposed's nullable() extension requires Column<Any>; buildColumn produces Column<*>.
        @Suppress("UNCHECKED_CAST")
        return if (col.nullable) (raw as Column<Any>).nullable() else raw
    }
}

/**
 * Internal Exposed [Table] subclass for junction tables. Registers a `parent_id` column, an
 * `item_id` column, and an optional `position` column. The primary key is composite over
 * `(parent_id, item_id)`. Junction columns are plain (typed) columns — foreign-key constraints
 * are installed later by [SqlRepository.installJunctionForeignKeys].
 */
private class LirpDynamicJunctionTable(
    tableName: String,
    parentColDef: JunctionColumnDef,
    itemColDef: JunctionColumnDef,
    positionColDef: JunctionColumnDef?,
    columnsByName: MutableMap<String, Column<*>>
) : Table(tableName) {

    override val primaryKey: PrimaryKey

    init {
        val parentCol = buildJunctionColumn(parentColDef)
        val itemCol = buildJunctionColumn(itemColDef)
        columnsByName[parentColDef.name] = parentCol
        columnsByName[itemColDef.name] = itemCol
        if (positionColDef != null) {
            val positionCol = buildJunctionColumn(positionColDef)
            columnsByName[positionColDef.name] = positionCol
        }
        primaryKey = PrimaryKey(parentCol, itemCol)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun buildJunctionColumn(col: JunctionColumnDef): Column<*> {
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
 * Wraps the Exposed [Table] produced by [ExposedTableInterpreter.interpretJunction] together with
 * typed handles to the junction columns.
 *
 * @property descriptor The junction descriptor this table was built from.
 * @property table The Exposed [Table] with composite PK over `(parent_id, item_id)`.
 * @property parentIdCol The `parent_id` column, typed as `Column<Any>` to support the full range of
 *   key types (Int, Long, UUID, …) without compile-time specialization.
 * @property itemIdCol The `item_id` column, typed as `Column<Any>`.
 * @property positionCol The `position` column, present only when `descriptor.isOrdered` is `true`.
 */
internal data class ExposedJunctionTable(
    val descriptor: JunctionTableDef,
    val table: Table,
    val parentIdCol: Column<Any>,
    val itemIdCol: Column<Any>,
    val positionCol: Column<Int>?
)

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

/**
 * Maps a LIRP [CascadeAction] to the corresponding Exposed [ReferenceOption] used on
 * `REFERENCES … ON DELETE …` foreign-key constraints.
 *
 * Returns `null` for [CascadeAction.NONE] — the convention is that NONE means "no FK clause at
 * all", which preserves backwards compatibility with consumer schemas whose existing tables lack
 * the constraint. Callers translate `null` into "skip the `reference()` call entirely".
 *
 * | LIRP `CascadeAction` | Exposed `ReferenceOption` |
 * |----------------------|---------------------------|
 * | `RESTRICT`           | `RESTRICT`                |
 * | `CASCADE`            | `CASCADE`                 |
 * | `DETACH`             | `SET_NULL`                |
 * | `NONE`               | `null` (no FK clause)     |
 */
internal fun cascadeToReferenceOption(action: CascadeAction): ReferenceOption? =
    when (action) {
        CascadeAction.RESTRICT -> ReferenceOption.RESTRICT
        CascadeAction.CASCADE -> ReferenceOption.CASCADE
        CascadeAction.DETACH -> ReferenceOption.SET_NULL
        CascadeAction.NONE -> null
    }
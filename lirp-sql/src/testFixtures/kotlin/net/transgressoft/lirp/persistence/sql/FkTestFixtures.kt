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
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.LirpRawInitializer
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import javax.sql.DataSource

// Foreign-key test fixtures shared across the integration test suite.
//
// Two reactive entities — FkParent and FkChild — exercise both junction-table foreign-key
// semantics (via FkParent.childIds) and a scalar foreign-key column (single_child_id). The
// scalar FK constraint is installed by FkScalarFkInstaller during table creation:
// SqlTableDef.foreignKeys() is declared by KSP for single-entity @ToOneAggregate references but is
// not yet consumed by SqlRepository.init, so the integration tests pre-create the parent / child
// tables with an inline ON DELETE clause (idempotent against SchemaUtils.create). This exercises
// the database-side semantics that LIRP will eventually emit and verifies the cascade-action
// mapping end-to-end.

/**
 * Child entity referenced by [FkParent.singleChildId] (scalar FK) and by [FkParent.childIds]
 * (junction). Carries no FKs of its own.
 */
class FkChild(override val id: Int) : ReactiveEntityBase<Int, FkChild>() {

    var name: String by reactiveProperty("")

    override val uniqueId: String get() = "fk-child-$id"

    override fun clone(): FkChild =
        FkChild(id).also { copy ->
            copy.withEventsDisabled { copy.name = name }
        }
}

/**
 * Parent entity holding a versioned scalar property (`name`), a nullable scalar foreign-key
 * column (`singleChildId`) and an ordered list of child IDs ([childIds]) backed by a junction
 * table.
 *
 * `version` is annotated indirectly via [FkParentTableDef]'s `isVersion = true` column flag; the
 * scalar property is exposed as a plain `var Long` rather than via the `@Version` annotation
 * because the fixture is shared between manual table defs and reflection-free assertion paths.
 */
class FkParent(override val id: Int) : ReactiveEntityBase<Int, FkParent>() {

    var name: String by reactiveProperty("")
    var version: Long by reactiveProperty(0L)
    var singleChildId: Int? by reactiveProperty(null)

    /**
     * Backing collection for the junction-table aggregate. Held as a plain `var` so the
     * [FkParentChildrenJunctionAccessor] can read it and so KSP-style `applyJunctionRows`
     * can reconcile it.
     */
    var childIds: List<Int> = emptyList()

    override val uniqueId: String get() = "fk-parent-$id"

    override fun clone(): FkParent =
        FkParent(id).also { copy ->
            copy.withEventsDisabled {
                copy.name = name
                copy.version = version
                copy.singleChildId = singleChildId
            }
            copy.childIds = childIds.toList()
        }
}

/**
 * SQL table definition for [FkChild]. Standalone — no junction descriptors, no scalar FKs.
 */
object FkChildTableDef : SqlTableDef<FkChild> {
    override val tableName = "fk_children"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("name", ColumnType.VarcharType(200), nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): FkChild {
        val cols = table.columns.associateBy { it.name }
        return FkChild(row[cols["id"]!! as Column<Int>]).also { entity ->
            entity.withEventsDisabled {
                entity.name = row[cols["name"]!! as Column<String>]
            }
        }
    }

    override fun toParams(entity: FkChild, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["name"]!! to entity.name
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: FkChild, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.withEventsDisabled {
            entity.name = row[cols["name"]!! as Column<String>]
        }
    }

    override fun applyScalarRow(entity: FkChild, row: ResultRow, table: Table, rawInit: LirpRawInitializer<FkChild>) {
        // No-op: entity state is fully populated by fromRow.
    }
}

/**
 * Junction descriptor for [FkParent.childIds]. Ordered list, parent-side `CASCADE`, item-side
 * `RESTRICT`.
 */
object FkParentChildrenJunctionDef : JunctionTableDef {
    override val tableName = "fk_parent_children"
    override val parentTableName = "fk_parents"
    override val itemTableName = "fk_children"
    override val columns =
        listOf(
            JunctionColumnDef("parent_id", ColumnType.IntType, primaryKey = true),
            JunctionColumnDef("item_id", ColumnType.IntType, primaryKey = true),
            JunctionColumnDef("position", ColumnType.IntType, primaryKey = false, nullable = false)
        )
    override val isOrdered = true
    override val parentFkOnDelete = CascadeAction.CASCADE
    override val itemFkOnDelete = CascadeAction.RESTRICT
}

/**
 * Junction accessor pairing [FkParentChildrenJunctionDef] with [FkParent.childIds].
 */
object FkParentChildrenJunctionAccessor : JunctionAccessor<FkParent> {
    override val descriptor: JunctionTableDef = FkParentChildrenJunctionDef

    override fun idsOf(entity: FkParent): Collection<Any> = entity.childIds.toList()
}

/**
 * SQL table definition for [FkParent]. Declares the [FkParentChildrenJunctionDef] junction +
 * matching accessor (so the `SqlRepository` fail-loud check passes), an `isVersion = true` column,
 * and a nullable `single_child_id` scalar column. The scalar FK constraint is NOT installed at
 * table creation — tests install it via [FkScalarFkInstaller] for the cascade action under test.
 */
object FkParentTableDef : SqlTableDef<FkParent>, VersionedTableDef<FkParent>, JunctionAware<FkParent> {
    override val tableName = "fk_parents"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("name", ColumnType.VarcharType(200), nullable = false, primaryKey = false),
            ColumnDef("version", ColumnType.LongType, nullable = false, primaryKey = false, isVersion = true),
            ColumnDef("single_child_id", ColumnType.IntType, nullable = true, primaryKey = false)
        )

    override val junctionTableDefs: List<JunctionTableDef> = listOf(FkParentChildrenJunctionDef)
    override val junctionAccessors: List<JunctionAccessor<FkParent>> = listOf(FkParentChildrenJunctionAccessor)

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): FkParent {
        val cols = table.columns.associateBy { it.name }
        return FkParent(row[cols["id"]!! as Column<Int>]).also { entity ->
            entity.withEventsDisabled {
                entity.name = row[cols["name"]!! as Column<String>]
                entity.version = row[cols["version"]!! as Column<Long>]
                entity.singleChildId = row[cols["single_child_id"]!! as Column<Int?>]
            }
        }
    }

    override fun toParams(entity: FkParent, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["name"]!! to entity.name,
            cols["version"]!! to entity.version,
            cols["single_child_id"]!! to entity.singleChildId
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: FkParent, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.withEventsDisabled {
            entity.name = row[cols["name"]!! as Column<String>]
            entity.version = row[cols["version"]!! as Column<Long>]
            entity.singleChildId = row[cols["single_child_id"]!! as Column<Int?>]
        }
    }

    override fun bumpVersion(entity: FkParent, newVersion: Long) {
        entity.withEventsDisabled { entity.version = newVersion }
    }

    override fun versionOf(entity: FkParent): Long = entity.version

    /**
     * Reconciles [FkParent.childIds] from junction rows during bulk load. Mirrors what KSP would
     * generate for an `aggregateList` collection ref.
     */
    override fun applyJunctionRows(entity: FkParent, descriptor: JunctionTableDef, ids: List<Any>) {
        if (descriptor !== FkParentChildrenJunctionDef) return
        entity.withEventsDisabled {
            entity.childIds = ids.filterIsInstance<Int>()
        }
    }

    override fun applyScalarRow(entity: FkParent, row: ResultRow, table: Table, rawInit: LirpRawInitializer<FkParent>) {
        // No-op: entity state is fully populated by fromRow.
    }
}

/**
 * Pre-creates the full FK schema (`fk_children`, `fk_parents`, and the junction
 * `fk_parent_children`) with every foreign-key clause inline in the `CREATE TABLE` DDL.
 *
 * Two dialect quirks force this approach:
 * - **MySQL** silently ignores the inline `column REFERENCES` short syntax; FKs only take effect
 *   when declared as an explicit `FOREIGN KEY (col) REFERENCES tbl(col) ON DELETE …` clause
 *   inside the `CREATE TABLE` body.
 * - **SQLite** does not support `ALTER TABLE … ADD CONSTRAINT`, so junction FK installation must
 *   happen at table-creation time, not afterwards.
 *
 * Both quirks are covered by emitting one `CREATE TABLE` per dialect with explicit `FOREIGN KEY`
 * clauses. Subsequent `SqlRepository.init` calls find the tables already present, and
 * `SchemaUtils.create` is a no-op. `installJunctionForeignKeys()` may still be called by tests; its
 * runCatching idempotency contract treats the duplicate-constraint failure as a debug-log no-op.
 *
 * `setupNone` omits the scalar FK clause but still installs the junction FKs (the junction table
 * is independent of the scalar column).
 */
object FkScalarFkInstaller {

    /** RESTRICT: the database refuses to delete a child row referenced by `fk_parents.single_child_id`. */
    fun setupRestrict(dataSource: DataSource) = createSchema(dataSource, scalarOnDelete = "RESTRICT")

    /** CASCADE: deleting the child also removes the parent row. */
    fun setupCascade(dataSource: DataSource) = createSchema(dataSource, scalarOnDelete = "CASCADE")

    /** DETACH: maps to `SET NULL`. Deleting the child nulls the scalar on the parent row. */
    fun setupDetach(dataSource: DataSource) = createSchema(dataSource, scalarOnDelete = "SET NULL")

    /** NONE: leave the scalar column as a plain nullable scalar without any FK constraint. */
    fun setupNone(dataSource: DataSource) = createSchema(dataSource, scalarOnDelete = null)

    /**
     * Creates the three tables with explicit `FOREIGN KEY` clauses. When [scalarOnDelete] is null
     * the scalar FK is omitted entirely (NONE → no FK clause at all). The junction parent
     * FK is always `CASCADE`, the junction item FK is always `RESTRICT` — matching
     * [FkParentChildrenJunctionDef].
     */
    private fun createSchema(dataSource: DataSource, scalarOnDelete: String?) {
        val childDdl =
            "CREATE TABLE fk_children (" +
                "id INTEGER PRIMARY KEY NOT NULL, " +
                "name VARCHAR(200) NOT NULL" +
                ")"

        val parentBody =
            buildList {
                add("id INTEGER PRIMARY KEY NOT NULL")
                add("name VARCHAR(200) NOT NULL")
                add("version BIGINT NOT NULL")
                add("single_child_id INTEGER")
                if (scalarOnDelete != null) {
                    add(
                        "CONSTRAINT fk_parents_single_child_fk " +
                            "FOREIGN KEY (single_child_id) REFERENCES fk_children(id) " +
                            "ON DELETE $scalarOnDelete"
                    )
                }
            }
        val parentDdl = "CREATE TABLE fk_parents (" + parentBody.joinToString(", ") + ")"

        // Junction must be created with FKs inline for SQLite's sake. CASCADE on the parent edge
        // and RESTRICT on the item edge match FkParentChildrenJunctionDef.
        val junctionDdl =
            "CREATE TABLE fk_parent_children (" +
                "parent_id INTEGER NOT NULL, " +
                "item_id INTEGER NOT NULL, " +
                "position INTEGER NOT NULL, " +
                "PRIMARY KEY (parent_id, item_id), " +
                "CONSTRAINT fk_parent_children_parent_fk " +
                "FOREIGN KEY (parent_id) REFERENCES fk_parents(id) ON DELETE CASCADE, " +
                "CONSTRAINT fk_parent_children_item_fk " +
                "FOREIGN KEY (item_id) REFERENCES fk_children(id) ON DELETE RESTRICT" +
                ")"

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(childDdl)
                stmt.execute(parentDdl)
                stmt.execute(junctionDdl)
            }
        }
    }
}
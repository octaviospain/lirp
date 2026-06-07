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

import net.transgressoft.lirp.persistence.ColumnType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ForeignKeyConstraint
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource
import kotlin.uuid.ExperimentalUuidApi

/**
 * Installs and manages the SQL schema for a [SqlRepository]'s entity and junction tables,
 * including deferred FK constraints for single-entity and collection (`@Aggregate`) references.
 *
 * Schema creation runs during [SqlRepository] init; FK installation is deferred to
 * [installJunctionForeignKeys] / [installEntityForeignKeys] so that cross-repository references
 * are wired only after all tables exist.
 *
 * All install operations are idempotent — duplicate-constraint errors raised by the database
 * are swallowed and logged at DEBUG level.
 */
internal class SqlSchemaInstaller<K : Comparable<K>, R>(
    private val dataSource: DataSource,
    private val tableDef: SqlTableDef<R>,
    private val exposedTable: ExposedTable,
    private val junctionTables: Map<JunctionTableDef, ExposedJunctionTable>,
    private val db: Database
) {
    private val table: Table = exposedTable.table
    private val log = KotlinLogging.logger(javaClass.name)

    /**
     * Installs junction-table FK constraints for this repository's collection references.
     *
     * Junction tables are created without FK constraints during init because the referenced
     * item table may belong to a different [SqlRepository] that has not yet been constructed.
     * Call this method after all repositories have been constructed.
     *
     * The parent-side FK always uses `ON DELETE CASCADE`; the item-side FK uses
     * `descriptor.itemFkOnDelete` (or is skipped when the action is `NONE`).
     */
    fun installJunctionForeignKeys() {
        if (junctionTables.isEmpty()) return

        // Each FK is installed in its own transaction. Postgres (and some other dialects) abort the
        // entire transaction on a DDL error — even a swallowed duplicate-constraint error poisons the
        // connection for subsequent statements in the same transaction. Independent transactions keep
        // each idempotent install isolated so a duplicate on the parent-side FK does not prevent the
        // item-side FK from being installed on the first call.
        for (junction in junctionTables.values) {
            val descriptor = junction.descriptor

            transaction(db = db) {
                installFk(
                    fromCol = junction.parentIdCol,
                    targetTableName = descriptor.parentTableName,
                    targetColumnName = "id",
                    targetColType = parentIdJunctionType(descriptor),
                    onDelete = ReferenceOption.CASCADE
                )
            }

            val itemRefOption = cascadeToReferenceOption(descriptor.itemFkOnDelete)
            if (itemRefOption != null) {
                transaction(db = db) {
                    installFk(
                        fromCol = junction.itemIdCol,
                        targetTableName = descriptor.itemTableName,
                        targetColumnName = "id",
                        targetColType = itemIdJunctionType(descriptor),
                        onDelete = itemRefOption
                    )
                }
            }
        }
    }

    /**
     * Installs FK constraints declared on this entity's scalar `@Aggregate` references.
     *
     * The entity table is created without these constraints during init for the same reason
     * junction tables are: the referenced target table may belong to a different [SqlRepository]
     * that has not yet been constructed.
     *
     * **SQLite caveat:** SQLite does not support `ALTER TABLE ADD CONSTRAINT`; this method
     * logs a warning and returns without installing any constraints on that dialect.
     */
    fun installEntityForeignKeys() {
        val foreignKeys = (tableDef as? ForeignKeyAware)?.foreignKeys() ?: return

        if (isSqliteDialect()) {
            log.warn {
                "installEntityForeignKeys: skipping ${foreignKeys.size} single-entity FK(s) on table " +
                    "'${tableDef.tableName}' — SQLite does not support ALTER TABLE ADD CONSTRAINT. " +
                    "FK constraints must be declared inline at CREATE TABLE time on this dialect."
            }
            return
        }

        val columnTypesByName = tableDef.columns.associate { it.name to it.type }

        // Each FK is installed in its own transaction. Postgres (and some other dialects) abort the
        // entire transaction on a DDL error — even a swallowed duplicate-constraint error poisons the
        // connection for subsequent statements. Independent transactions keep each idempotent install
        // isolated.
        for (fk in foreignKeys) {
            val refOption = cascadeToReferenceOption(fk.onDelete) ?: continue

            val columnType =
                columnTypesByName[fk.columnName]
                    ?: error(
                        "SqlTableDef '${tableDef::class.qualifiedName}' declares ForeignKeyDef on column " +
                            "'${fk.columnName}' but no such column exists in tableDef.columns. This is a KSP " +
                            "or hand-written SqlTableDef contract violation."
                    )

            @Suppress("UNCHECKED_CAST")
            val fromCol = exposedTable.columnsByName.getValue(fk.columnName) as Column<Any>

            transaction(db = db) {
                installFk(
                    fromCol = fromCol,
                    targetTableName = fk.referencedTable,
                    targetColumnName = fk.referencedColumn,
                    targetColType = columnType,
                    onDelete = refOption
                )
            }
        }
    }

    /**
     * Detects SQLite by inspecting the underlying JDBC connection's `DatabaseMetaData`.
     * Used by [installEntityForeignKeys] to short-circuit before attempting an unsupported
     * `ALTER TABLE ADD CONSTRAINT` DDL statement.
     */
    private fun isSqliteDialect(): Boolean =
        dataSource.connection.use { conn ->
            conn.metaData.databaseProductName.orEmpty().lowercase().contains("sqlite")
        }

    /**
     * Builds an Exposed [ForeignKeyConstraint] anchored at a shadow target table and executes
     * the resulting DDL inside the current transaction. Duplicate-constraint failures are caught
     * and logged at DEBUG so repeated calls are no-ops.
     */
    private fun JdbcTransaction.installFk(
        fromCol: Column<Any>,
        targetTableName: String,
        targetColumnName: String,
        targetColType: ColumnType,
        onDelete: ReferenceOption
    ) {
        val shadowTarget = ShadowEntityIdTable(targetTableName, targetColumnName, targetColType)

        @Suppress("UNCHECKED_CAST")
        val targetCol = shadowTarget.idColumn as Column<Any>

        val fk =
            ForeignKeyConstraint(
                target = targetCol,
                from = fromCol,
                onUpdate = null,
                onDelete = onDelete,
                name = null
            )
        for (sql in SchemaUtils.createFKey(fk)) {
            try {
                exec(sql)
            } catch (e: ExposedSQLException) {
                // Duplicate FK constraint is idempotent — already installed by a prior call.
                // Only swallow errors that indicate the constraint already exists; rethrow everything
                // else (missing referenced table, type mismatch, permissions, etc.) so callers are
                // not silently left without the FK constraint.
                if (isDuplicateConstraintException(e)) {
                    log.debug(e) { "installFk: skipping '$sql' — duplicate constraint (SQLState=${e.sqlState})" }
                } else {
                    throw e
                }
            }
        }
    }

    private fun parentIdJunctionType(descriptor: JunctionTableDef): ColumnType =
        descriptor.columns.first { it.name == "parent_id" }.type

    private fun itemIdJunctionType(descriptor: JunctionTableDef): ColumnType =
        descriptor.columns.first { it.name == "item_id" }.type

    companion object {
        // SQLState codes that indicate a duplicate/already-existing constraint, which is the expected
        // idempotency condition for installFk(). All other SQLStates are genuine errors and re-thrown.
        // 42P07: PostgreSQL "relation/object already exists"
        // 42710: ISO SQL / IBM DB2 "duplicate object"
        // 42S01: MySQL / MariaDB "table/object already exists"
        // 90045: H2 CONSTRAINT_ALREADY_EXISTS_1 (H2 returns the numeric error code as SQLState for
        //        non-standard codes, i.e. Integer.toString(90045))
        private val DUPLICATE_CONSTRAINT_SQL_STATES = setOf("42P07", "42710", "42S01", "90045")

        // MySQL and MariaDB both use the generic SQLState HY000 for duplicate FK constraint name
        // errors (MySQL error 1826, MariaDB errno 121). As a fallback for HY000, check the error
        // message for dialect-specific duplicate-constraint keywords so we don't silently swallow
        // unrelated HY000 errors.
        private val DUPLICATE_CONSTRAINT_MESSAGE_PATTERNS =
            listOf(
                "duplicate foreign key constraint",
                "duplicate key on write or update",
                "already exists"
            )

        internal fun isDuplicateConstraintException(e: ExposedSQLException): Boolean {
            val state = e.sqlState.orEmpty()
            if (state in DUPLICATE_CONSTRAINT_SQL_STATES) return true
            // HY000 is a catch-all for many MySQL/MariaDB errors; only treat it as a duplicate
            // if the message matches a known duplicate-constraint pattern.
            if (state == "HY000") {
                val msg = e.message.orEmpty().lowercase()
                return DUPLICATE_CONSTRAINT_MESSAGE_PATTERNS.any { it in msg }
            }
            return false
        }
    }
}

/**
 * Shadow Exposed [Table] used solely as a target for [ForeignKeyConstraint] DDL emission during
 * deferred FK installation. The shadow table has a single column named [columnName] whose type
 * matches the descriptor's declaration; Exposed reads only the table name and column name when
 * rendering the `REFERENCES <tbl>(<col>)` clause, so the shadow table never needs to be created
 * in the database.
 */
@OptIn(ExperimentalUuidApi::class)
internal class ShadowEntityIdTable(
    tableName: String,
    columnName: String,
    idType: ColumnType
) : Table(tableName) {
    val idColumn: Column<*> =
        when (idType) {
            is ColumnType.IntType -> integer(columnName)
            is ColumnType.LongType -> long(columnName)
            is ColumnType.UuidType -> uuid(columnName)
            is ColumnType.VarcharType -> varchar(columnName, idType.length)
            is ColumnType.TextType -> text(columnName)
            else -> error("Unsupported id type for shadow target table: $idType")
        }
}
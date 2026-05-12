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

import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.PendingBatchDelete
import net.transgressoft.lirp.persistence.PendingBatchInsert
import net.transgressoft.lirp.persistence.PendingClear
import net.transgressoft.lirp.persistence.PendingDelete
import net.transgressoft.lirp.persistence.PendingInsert
import net.transgressoft.lirp.persistence.PendingOp
import net.transgressoft.lirp.persistence.PendingUpdate
import net.transgressoft.lirp.persistence.PersistentRepositoryBase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ForeignKeyConstraint
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import javax.sql.DataSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * SQL-backed reactive repository using JetBrains Exposed and HikariCP connection pooling.
 *
 * Extends [PersistentRepositoryBase] with a queue-based write pipeline: CRUD operations update
 * in-memory state immediately (optimistic reads) and enqueue [PendingOp] entries. The base class
 * debounce timer collapses and flushes the queue to SQL via [writePending], which executes all
 * collapsed operations in a single transaction using batch SQL where applicable.
 *
 * On initialization, this repository:
 * 1. Auto-creates the table using [SchemaUtils.create] (no-op if it already exists).
 * 2. When [loadOnInit] is `true` (default), loads all existing rows from the database into
 *    in-memory state immediately. When `false`, rows are not loaded until [load] is called.
 *
 * Two construction modes are supported:
 * - **User-provided [DataSource]:** The caller owns the connection pool; [close] does not close it.
 * - **JDBC URL constructor:** A [HikariDataSource] is created and owned by this repository;
 *   [close] shuts down the pool after the final flush.
 *
 * ## Transactional Model
 *
 * `SqlRepository` provides three guarantees and three intentional non-guarantees:
 *
 * **Guarantees:**
 * - **Single-aggregate atomicity** — all collapsed pending ops for a single `flush()` cycle
 *   execute in one Exposed `transaction(db) { ... }`. Either all operations commit, or the
 *   transaction rolls back entirely (subject to dialect-specific partial-commit semantics on
 *   failure — see the per-dialect integration tests for empirical behavior).
 * - **Event-before-persistence** — in-memory [net.transgressoft.lirp.event.CrudEvent]s are emitted
 *   at the call site (optimistic reads) not after SQL commit. Consumers see `Create`/`Update`/
 *   `Delete` immediately; any subsequent [net.transgressoft.lirp.event.StandardCrudEvent.Conflict]
 *   event explains if the persist ultimately failed due to an optimistic-lock conflict.
 * - **Optimistic `@Version` reads** — when the tableDef exposes a `@Version`-flagged column,
 *   UPDATE and DELETE augment their WHERE clause with `AND version = ?`. Zero-row-affected
 *   triggers the auto-reload + `Conflict` recovery path. See
 *   [net.transgressoft.lirp.persistence.Version] and
 *   [net.transgressoft.lirp.event.StandardCrudEvent.Conflict].
 *
 * **Non-guarantees:**
 * - No multi-aggregate transactions. Each `SqlRepository` transacts only over its own table.
 * - No saga orchestration. Consumers compose cross-aggregate workflows via `CrudEvent` subscribers.
 * - No outbox pattern. `CrudEvent`s go directly to subscribers; durable event logs are a consumer concern.
 *
 * See the wiki page "Transactional Boundaries" for prose and a saga/compensation example.
 *
 * @param K The type of entity identifier, must be [Comparable].
 * @param R The type of reactive entity stored in this repository.
 * @param loadOnInit When `true` (default), rows are loaded from the database immediately during
 *   construction. When `false`, the caller must invoke [load] explicitly before any mutating
 *   operations.
 */
open class SqlRepository<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    private val dataSource: DataSource,
    private val tableDef: SqlTableDef<R>,
    private val ownsDataSource: Boolean,
    loadOnInit: Boolean = true
) : PersistentRepositoryBase<K, R>("SqlRepository-${tableDef.tableName}", loadOnInit) {

    /**
     * Creates a [SqlRepository] using a user-provided [DataSource].
     *
     * The caller retains ownership of the [DataSource]; closing this repository will not close
     * the underlying connection pool.
     *
     * @param dataSource The JDBC data source to use for all SQL operations.
     * @param tableDef The SQL table definition describing the entity's column mapping.
     * @param loadOnInit When `true` (default), rows are loaded from the database immediately
     *   during construction. When `false`, [load] must be called explicitly.
     */
    constructor(dataSource: DataSource, tableDef: SqlTableDef<R>, loadOnInit: Boolean = true):
        this(dataSource, tableDef, false, loadOnInit)

    /**
     * Creates a [SqlRepository] with a HikariCP connection pool configured from the given JDBC URL.
     *
     * The created [HikariDataSource] is owned by this repository and will be closed when [close]
     * is called.
     *
     * For `jdbc:sqlite:` URLs, prefer [SqliteRepository.fileBacked] or
     * [SqliteRepository.inMemory], which apply the SQLite PRAGMA bundle
     * (`foreign_keys = ON`, `journal_mode = WAL`, `busy_timeout`,
     * `synchronous = NORMAL`) on every pooled connection. This constructor
     * leaves SQLite without those PRAGMAs unless the caller layers them in
     * via a pre-built [DataSource].
     *
     * @param jdbcUrl The JDBC connection URL (e.g. `jdbc:postgresql://host/db`).
     * @param tableDef The SQL table definition describing the entity's column mapping.
     * @param poolSize Maximum number of connections in the HikariCP pool. Defaults to 10.
     * @param schema Optional database schema name to use for the connection.
     * @param loadOnInit When `true` (default), rows are loaded from the database immediately
     *   during construction. When `false`, [load] must be called explicitly.
     */
    @JvmOverloads
    constructor(
        jdbcUrl: String,
        tableDef: SqlTableDef<R>,
        poolSize: Int = 10,
        schema: String? = null,
        loadOnInit: Boolean = true
    ) : this(buildDataSource(jdbcUrl, poolSize, schema), tableDef, true, loadOnInit)

    private val interpreter = ExposedTableInterpreter()
    private val exposedTable: ExposedTable = interpreter.interpret(tableDef)
    private val table: Table = exposedTable.table
    private val pkCol: Column<*> = exposedTable.columnsByName.getValue(tableDef.columns.first { it.primaryKey }.name)
    private val versionCol: Column<Long>? = exposedTable.versionCol
    private val db: Database = Database.connect(dataSource)
    private val log = KotlinLogging.logger(javaClass.name)

    /**
     * Cached interpretations of this entity's junction descriptors, keyed by descriptor reference.
     * Reused across `loadFromStore` (and, in subsequent commits, `writePending` and
     * `installJunctionForeignKeys`) so we don't re-allocate Exposed [Table] objects on every call.
     */
    private val junctionTables: Map<JunctionTableDef, ExposedJunctionTable> =
        tableDef.junctionTableDefs.associateWith { interpreter.interpretJunction(it) }

    init {
        // Fail-loud: a SqlTableDef that declares junction descriptors but supplies no matching
        // accessors would silently skip every junction-row write at flush time, producing schema
        // rows that never reflect the in-memory collection state. Hand-written SqlTableDefs MUST
        // either supply matching junctionAccessors or use the @PersistenceMapping KSP processor.
        val descriptorSet = tableDef.junctionTableDefs.toSet()
        val accessorDescriptorSet = tableDef.junctionAccessors.map { it.descriptor }.toSet()
        check(
            tableDef.junctionTableDefs.size == tableDef.junctionAccessors.size &&
                descriptorSet == accessorDescriptorSet
        ) {
            "SqlTableDef '${tableDef::class.qualifiedName}' declares ${tableDef.junctionTableDefs.size} " +
                "junction descriptor(s) but ${tableDef.junctionAccessors.size} junction accessor(s). " +
                "Hand-written SqlTableDefs must implement junctionAccessors when junctionTableDefs is non-empty. " +
                "Use the @PersistenceMapping KSP processor to generate both."
        }

        try {
            // Auto-create the entity table and every junction table that backs an aggregate
            // collection. Junction tables are created WITHOUT FK constraints — the referenced
            // entity table may belong to a not-yet-constructed SqlRepository, and adding the FK
            // up-front would fail with "referenced table does not exist". FKs install via
            // installJunctionForeignKeys() once every repository has materialised its entity table.
            transaction(db = db) {
                SchemaUtils.create(table)
                for (junction in junctionTables.values) {
                    SchemaUtils.create(junction.table)
                }
            }
            if (loadOnInit) load()
        } catch (e: Exception) {
            if (ownsDataSource) {
                (dataSource as? HikariDataSource)?.close()
            }
            throw e
        }
    }

    /**
     * Installs the junction-table foreign-key constraints for this repository.
     *
     * Junction tables are created without FK constraints during [init] because the referenced
     * item table may belong to a different [SqlRepository] that has not yet been constructed.
     * Once every repository in the application has initialised, call this method on each
     * repository (or once via the application bootstrapper) to install the constraints. The
     * parent-side FK is always emitted with `ON DELETE CASCADE` so deleting a parent row reaps
     * the orphaned junction rows; the item-side FK uses
     * `descriptor.itemFkOnDelete` (or is skipped entirely when the action is `NONE`).
     *
     * Safe to call multiple times — duplicate-constraint errors raised by the database are
     * swallowed so the operation is effectively idempotent.
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
     * Installs the single-entity foreign-key constraints declared on this entity's scalar
     * `@Aggregate` references.
     *
     * The entity table is created without these constraints during [init] for the same reason
     * junction tables are: the referenced target table may belong to a different [SqlRepository]
     * that has not yet been constructed. Once every repository in the application has
     * materialised, call this method on each repository (or once via the application bootstrapper)
     * to install the constraints declared in [SqlTableDef.foreignKeys].
     *
     * The `ON DELETE` clause is taken from each [ForeignKeyDef.onDelete] descriptor and translated
     * via [cascadeToReferenceOption]; entries whose action is [CascadeAction.NONE] are skipped
     * upstream by the KSP processor and never appear in `tableDef.foreignKeys()`.
     *
     * **SQLite caveat:** SQLite does not support `ALTER TABLE ADD CONSTRAINT`, so this method
     * logs a warning and returns without installing any constraints. Consumers needing FK
     * enforcement on SQLite must declare the constraints inline at `CREATE TABLE` time (a future
     * follow-up may emit them through the table builder for the SQLite dialect).
     *
     * Safe to call multiple times — duplicate-constraint errors raised by the database are
     * swallowed so the operation is effectively idempotent.
     */
    fun installEntityForeignKeys() {
        val foreignKeys = tableDef.foreignKeys()
        if (foreignKeys.isEmpty()) return

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
     * Builds an Exposed [ForeignKeyConstraint] anchored at a shadow target table whose only
     * column is [targetColumnName] with the descriptor-declared type, and executes the resulting
     * DDL inside the current transaction. Duplicate-constraint failures are caught and logged
     * at DEBUG so repeated calls are no-ops.
     */
    private fun JdbcTransaction.installFk(
        fromCol: Column<Any>,
        targetTableName: String,
        targetColumnName: String,
        targetColType: net.transgressoft.lirp.persistence.ColumnType,
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
                    log.debug(e) { "installJunctionForeignKeys: skipping '$sql' — duplicate constraint (SQLState=${e.sqlState})" }
                } else {
                    throw e
                }
            }
        }
    }

    private fun parentIdJunctionType(descriptor: JunctionTableDef): net.transgressoft.lirp.persistence.ColumnType =
        descriptor.columns.first { it.name == "parent_id" }.type

    private fun itemIdJunctionType(descriptor: JunctionTableDef): net.transgressoft.lirp.persistence.ColumnType =
        descriptor.columns.first { it.name == "item_id" }.type

    /**
     * Loads all existing rows from the database into memory.
     *
     * Called by [load] as part of the template method. Reads the full table contents via a
     * single SELECT query and returns the entities. After this method returns, the [dirty]
     * flag is reset so that the initial load does not trigger an immediate write-back.
     *
     * @return a map of entity ID to entity from the database, or an empty map if the table is empty.
     */
    override fun loadFromStore(): Map<K, R> {
        val byId =
            transaction(db = db) {
                val entities = table.selectAll().map { row -> tableDef.fromRow(row, table) }
                val byId = entities.associateBy { it.id }

                if (junctionTables.isEmpty()) return@transaction byId

                // Issue one ordered SELECT per junction descriptor and group the rows in process by
                // parent_id. For ordered descriptors, sort the per-parent ID list by the position
                // column before handing it off to applyJunctionRows. Junction rows referencing
                // parent IDs that are not present in this entity result set (orphans) are dropped
                // silently — the FK ON DELETE CASCADE installed later by
                // installJunctionForeignKeys() handles SQL-side cleanup.
                for (junction in junctionTables.values) {
                    val descriptor = junction.descriptor
                    val grouped = LinkedHashMap<Any, MutableList<Pair<Any, Int?>>>()

                    val rowsQuery = junction.table.selectAll()
                    val orderedQuery =
                        junction.positionCol?.let { posCol ->
                            rowsQuery.orderBy(junction.parentIdCol, SortOrder.ASC).orderBy(posCol, SortOrder.ASC)
                        } ?: rowsQuery.orderBy(junction.parentIdCol, SortOrder.ASC)

                    for (row in orderedQuery) {
                        val parentId = toDomainId(row[junction.parentIdCol])
                        val itemId = toDomainId(row[junction.itemIdCol])
                        val position = junction.positionCol?.let { row[it] }
                        grouped.getOrPut(parentId) { mutableListOf() }.add(itemId to position)
                    }

                    for ((parentId, pairs) in grouped) {
                        @Suppress("UNCHECKED_CAST")
                        val entity = byId[parentId as K] ?: continue
                        val orderedIds: List<Any> =
                            if (descriptor.isOrdered) {
                                pairs.sortedBy { it.second ?: 0 }.map { it.first }
                            } else {
                                pairs.map { it.first }
                            }
                        tableDef.applyJunctionRows(entity, descriptor, orderedIds)
                    }
                }
                byId
            }
        dirty.set(false)
        return byId
    }

    /**
     * Fetches junction rows for [entity] from the database and applies them via
     * [SqlTableDef.applyJunctionRows]. Issues one SELECT per junction table, filtered to the
     * entity's primary key, so the query set is proportional to the number of collection fields
     * rather than the total entity count. No-op when [junctionTables] is empty.
     *
     * Must be called from code that is either within an active Exposed transaction or can start a
     * new one; opens its own [transaction] block when junction tables are present.
     */
    private fun hydrateJunctionsForEntity(entity: R) {
        if (junctionTables.isEmpty()) return
        val exposedId = toExposedId(entity.id)
        transaction(db = db) {
            for (junction in junctionTables.values) {
                val descriptor = junction.descriptor
                val rows =
                    junction.table.selectAll()
                        .where {
                            @Suppress("UNCHECKED_CAST")
                            (junction.parentIdCol as Column<Any?>).eq(exposedId)
                        }
                val orderedRows =
                    junction.positionCol?.let { posCol ->
                        rows.orderBy(posCol, SortOrder.ASC)
                    } ?: rows
                val pairs =
                    orderedRows.map { row ->
                        val itemId = toDomainId(row[junction.itemIdCol])
                        val position = junction.positionCol?.let { row[it] }
                        itemId to position
                    }
                val orderedIds: List<Any> =
                    if (descriptor.isOrdered) {
                        pairs.sortedBy { it.second ?: 0 }.map { it.first }
                    } else {
                        pairs.map { it.first }
                    }
                tableDef.applyJunctionRows(entity, descriptor, orderedIds)
            }
        }
    }

    /**
     * Reads the optimistic-lock version from [entity] via the table descriptor's `toParams`
     * mapping. Returns `null` when the repository's `tableDef` has no `@Version` column.
     *
     * Rationale: LIRP has no runtime reflection API for typed property access. The existing
     * `toParams(entity, table)` Map already exposes every persisted column value keyed by its
     * [Column] reference, so a single map lookup on [versionCol] yields the value with zero
     * reflection — the same zero-reflection invariant preserved by `fromRow`/`applyRow`. The
     * O(columns) cost is acceptable for typical entity widths; if profiling shows the lookup
     * dominates mutation hot paths, a KSP-generated VersionAccessor remains available as a
     * future optimization.
     */
    override fun extractVersion(entity: R): Long? {
        val vc = versionCol ?: return null
        val params = tableDef.toParams(entity, table)
        return params[vc] as? Long
    }

    /**
     * Executes all collapsed pending operations against the database in a single transaction.
     *
     * Insert operations use [batchInsert] for efficient bulk inserts. Delete operations use
     * `deleteWhere` (per-id within a batch) to stay dialect-portable. Updates are applied
     * individually per entity. A [PendingClear] deletes all rows from the table.
     *
     * For versioned tables (tableDef carries a `@Version` column), UPDATE and DELETE augment
     * their WHERE clause with `AND version = ?`. A zero-row-affected result is treated as an
     * optimistic-lock conflict and accumulated into a per-entity list; the accumulator does
     * NOT throw inside the transaction, so any non-conflicting operations in the same flush
     * still commit. After the transaction commits, every accumulated conflict is recovered
     * (auto-reload + [StandardCrudEvent.Conflict] emission) in its own short-lived transaction.
     *
     * Rationale: wrapping the whole flush in one SQL transaction is required for D-01
     * single-aggregate atomicity, but letting a single conflict throw mid-transaction would
     * roll back every earlier insert/update/delete and the base class would drop the drained
     * snapshot — silently losing work. Accumulating instead preserves non-conflicting writes.
     *
     * @param ops the minimal collapsed list of operations to execute.
     */
    override fun writePending(ops: List<PendingOp<K, R>>) {
        val conflicts = mutableListOf<PendingConflict<K>>()
        transaction(db = db) {
            ops.forEach { op ->
                when (op) {
                    is PendingInsert -> executeInsert(op)
                    is PendingBatchInsert -> executeBatchInsert(op)
                    is PendingUpdate -> executeUpdate(op, conflicts)
                    is PendingDelete -> executeDelete(op, conflicts)
                    is PendingBatchDelete -> executeBatchDelete(op, conflicts)
                    is PendingClear -> table.deleteAll()
                }
            }
        }
        // The main transaction has committed. Recover every accumulated conflict — each path
        // re-SELECTs the canonical row and emits a [StandardCrudEvent.Conflict]. A recovery
        // failure must NOT escape to [writePending]: the base class would interpret it as a
        // generic write failure and re-enqueue the whole drained snapshot, re-applying the
        // non-conflicting ops that already succeeded. Log + continue per conflict.
        conflicts.forEach { conflict ->
            try {
                recoverEntityFromConflict(conflict.id, conflict.expectedVersion)
            } catch (e: Exception) {
                log.error(e) {
                    "recoverEntityFromConflict threw for id=${conflict.id} " +
                        "(expectedVersion=${conflict.expectedVersion}); conflict may not have been fully recovered"
                }
            }
        }
    }

    private fun executeInsert(op: PendingInsert<K, R>) {
        table.insert { stmt ->
            tableDef.toParams(op.entity, table).forEach { (col, value) ->
                // Safe: col was registered by ExposedTableInterpreter from the declared LirpTableDef column type.
                // Exposed erases Column<T> to Column<*> at the statement-builder level; Column<Any?> is the canonical workaround.
                @Suppress("UNCHECKED_CAST")
                stmt[col as Column<Any?>] = value
            }
        }
        syncJunctionRows(op.entity)
    }

    private fun executeBatchInsert(op: PendingBatchInsert<K, R>) {
        if (op.entities.isEmpty()) return
        table.batchInsert(op.entities, shouldReturnGeneratedValues = false) { entity ->
            tableDef.toParams(entity, table).forEach { (col, value) ->
                // Safe: col was registered by ExposedTableInterpreter from the declared LirpTableDef column type.
                @Suppress("UNCHECKED_CAST")
                this[col as Column<Any?>] = value
            }
        }
        // Sync junction rows after the batch insert. A future optimisation could batch-insert every
        // junction row in a single statement; per-entity is correct and acceptable for typical
        // batch sizes (Spike 008).
        op.entities.forEach { syncJunctionRows(it) }
    }

    /**
     * Synchronises the junction rows for [entity] using a delete-then-insert strategy. The
     * descriptor-driven `idsOf` accessor provides the current collection-ID state; previous junction
     * rows for this parent are deleted in bulk and re-inserted at positions `0..ids.size - 1` (for
     * ordered descriptors) or without position (for unordered). On insert the entity has no prior
     * junction rows, so the delete is a cheap no-op; on update the wholesale replacement keeps SQL
     * state consistent with the in-memory collection without diff-and-patch complexity (Spike 008
     * measured the trade-off as acceptable for typical aggregate sizes).
     *
     * `executeDelete` does NOT call this method — the parent-side FK's `ON DELETE CASCADE`
     * (installed by [installJunctionForeignKeys]) reaps the junction rows automatically.
     */
    private fun syncJunctionRows(entity: R) {
        if (junctionTables.isEmpty()) return
        val accessors = tableDef.junctionAccessors

        for (accessor in accessors) {
            val descriptor = accessor.descriptor
            val junction = junctionTables[descriptor] ?: continue
            val ids = accessor.idsOf(entity).toList()
            val parentId: Any = toExposedId(entity.id as Any)

            junction.table.deleteWhere { junction.parentIdCol eq parentId }

            ids.forEachIndexed { index, itemId ->
                junction.table.insert { stmt ->
                    stmt[junction.parentIdCol] = parentId
                    stmt[junction.itemIdCol] = toExposedId(itemId)
                    junction.positionCol?.let { posCol -> stmt[posCol] = index }
                }
            }
        }
    }

    private fun executeUpdate(op: PendingUpdate<K, R>, conflicts: MutableList<PendingConflict<K>>) {
        val expected = op.expectedVersion
        val vc = versionCol
        val rowsAffected =
            table.update({
                // Safe: pkCol is the PK column registered by ExposedTableInterpreter. Exposed's
                // eq() operator requires Column<Any?> due to statement-builder type erasure.
                @Suppress("UNCHECKED_CAST")
                val pkPred = (pkCol as Column<Any?>).eq(toExposedId(op.entity.id))
                if (expected != null && vc != null) pkPred and (vc eq expected) else pkPred
            }) { stmt ->
                tableDef.toParams(op.entity, table).forEach { (col, value) ->
                    // Safe: col was registered by ExposedTableInterpreter from the declared LirpTableDef column type.
                    @Suppress("UNCHECKED_CAST")
                    stmt[col as Column<Any?>] = value
                }
                // Advance the DB version to match the in-memory bump applied below. `toParams`
                // emits the pre-bump `entity.version` (what the caller saw), so without this
                // override the UPDATE would re-write the same version and leave the row at the
                // expected value — the next mutation's `WHERE version = expected + 1` predicate
                // would then miss and spuriously register an optimistic-lock conflict.
                if (expected != null && vc != null) {
                    @Suppress("UNCHECKED_CAST")
                    stmt[vc as Column<Any?>] = expected + 1
                }
            }
        if (expected != null && vc != null) {
            if (rowsAffected == 0) {
                // Accumulate instead of throwing: throwing here would abort the outer transaction
                // and roll back every earlier non-conflicting op in the same flush cycle, which
                // the base flush() path would then drop. Recovery runs post-commit.
                conflicts.add(PendingConflict(op.entity.id, expected))
                return
            }
            // auto-bump the in-memory version to expected + 1 with events disabled to avoid
            // re-enqueueing another PendingUpdate through the mutation subscription. Matches the
            // row state just written (the UPDATE payload above sets DB version = expected + 1,
            // and this bump keeps in-memory in sync).
            op.entity.withEventsDisabled {
                tableDef.bumpVersion(op.entity, expected + 1)
            }
        }
        // Wholesale-replace junction rows after the parent UPDATE succeeds. Skipped on optimistic
        // lock conflict (early return above) — the conflicting state will be reconciled via
        // recoverEntityFromConflict + the next user-driven UPDATE.
        syncJunctionRows(op.entity)
    }

    private fun executeDelete(op: PendingDelete<K, R>, conflicts: MutableList<PendingConflict<K>>) {
        val expected = op.expectedVersion
        val vc = versionCol
        val rowsAffected =
            table.deleteWhere {
                @Suppress("UNCHECKED_CAST")
                val pkPred = (pkCol as Column<Any?>).eq(toExposedId(op.id))
                if (expected != null && vc != null) pkPred and (vc eq expected) else pkPred
            }
        if (expected != null && vc != null && rowsAffected == 0) {
            // Accumulate instead of throwing — see executeUpdate rationale.
            conflicts.add(PendingConflict(op.id, expected))
        }
    }

    private fun executeBatchDelete(op: PendingBatchDelete<K, R>, conflicts: MutableList<PendingConflict<K>>) {
        // per-id independent DELETE loop. Accumulate conflicts rather than throwing so
        // the other ids still commit in the same transaction.
        val vc = versionCol
        op.idsWithVersions.forEach { (id, expected) ->
            val rowsAffected =
                table.deleteWhere {
                    @Suppress("UNCHECKED_CAST")
                    val pkPred = (pkCol as Column<Any?>).eq(toExposedId(id))
                    if (expected != null && vc != null) pkPred and (vc eq expected) else pkPred
                }
            if (expected != null && vc != null && rowsAffected == 0) {
                conflicts.add(PendingConflict(id, expected))
            }
        }
    }

    /**
     * Shared recovery path for conflicts accumulated during [writePending]. SELECTs the canonical
     * row; if missing, emits a deletion-sentinel Conflict; otherwise swaps in-memory state (or
     * reconstructs + re-inserts for defeated DELETE paths) and emits Conflict with the canonical
     * version.
     */
    private fun recoverEntityFromConflict(id: K, expectedVersion: Long) {
        // Defensive: unreachable under normal flow — conflict implies versioned repo.
        val vc = versionCol ?: return

        val canonicalRow: ResultRow? =
            transaction(db = db) {
                table.selectAll()
                    .where {
                        @Suppress("UNCHECKED_CAST")
                        (pkCol as Column<Any?>).eq(toExposedId(id))
                    }
                    .singleOrNull()
            }

        // Case 1: row was deleted by a third writer — treat as Conflict with oldEntity == newEntity
        // sentinel and actualVersion = -1L. The in-memory entity is dropped.
        if (canonicalRow == null) {
            // DELETE-DELETE race: if our failed op was itself a DELETE and the third writer also
            // deleted, `findById` returns empty because the local state already reflects the
            // intended removal. Both writers agreed — no subscriber-visible Conflict is emitted.
            val inMemory = findById(id).orElse(null) ?: return
            // Suppress the Delete event that would otherwise fire from removeFromMemoryOnly →
            // VolatileRepository.remove. Recovery should look like a single Conflict to
            // subscribers, not Delete + Conflict (which is indistinguishable from ordinary CRUD).
            disableEvents(CrudEvent.Type.DELETE)
            try {
                removeFromMemoryOnly(inMemory)
            } finally {
                activateEvents(CrudEvent.Type.DELETE)
            }
            publisher.emitAsync(
                StandardCrudEvent.Conflict(
                    oldEntity = inMemory,
                    newEntity = inMemory,
                    expectedVersion = expectedVersion,
                    actualVersion = -1L
                )
            )
            return
        }

        val actualVersion = canonicalRow[vc]
        val inMemoryOpt = findById(id)

        if (inMemoryOpt.isPresent) {
            // Case 2a: local entity still present — swap canonical state (scalars + junctions) into
            // it, emit Conflict with a clone of the pre-swap state as oldEntity for semantic clarity.
            // clone() on ReactiveEntity<K, R> returns ReactiveEntity<K, R>, so cast to R — the
            // implementation always returns its own type per ReactiveEntity.clone()'s contract.
            val inMemory = inMemoryOpt.get()

            @Suppress("UNCHECKED_CAST")
            val oldSnapshot = inMemory.clone() as R
            inMemory.withEventsDisabled {
                tableDef.applyRow(inMemory, canonicalRow, table)
            }
            hydrateJunctionsForEntity(inMemory)
            publisher.emitAsync(
                StandardCrudEvent.Conflict<K, R>(
                    oldEntity = oldSnapshot,
                    newEntity = inMemory,
                    expectedVersion = expectedVersion,
                    actualVersion = actualVersion
                )
            )
        } else {
            // Case 2b: our DELETE was defeated (D-11). The entity is no longer in in-memory state
            // but the canonical row exists — reconstruct and re-insert without enqueueing an
            // insert PendingOp (the row is already persisted).
            val reconstructed = tableDef.fromRow(canonicalRow, table)
            hydrateJunctionsForEntity(reconstructed)
            // Suppress the Create event that would otherwise fire from addToMemoryOnly →
            // VolatileRepository.add. Recovery should look like a single Conflict to
            // subscribers, not Create + Conflict.
            disableEvents(CrudEvent.Type.CREATE)
            try {
                addToMemoryOnly(reconstructed)
            } finally {
                activateEvents(CrudEvent.Type.CREATE)
            }
            publisher.emitAsync(
                StandardCrudEvent.Conflict(
                    oldEntity = reconstructed,
                    newEntity = reconstructed,
                    expectedVersion = expectedVersion,
                    actualVersion = actualVersion
                )
            )
        }
    }

    /**
     * Per-entity optimistic-lock conflict accumulated during a flush. Conflicts from UPDATE,
     * DELETE, and per-id batch-delete paths share this shape; all recovery happens post-commit.
     */
    private data class PendingConflict<K>(val id: K, val expectedVersion: Long)

    /**
     * Emits a [CrudEvent.Type.UPDATE] event to repository subscribers when an entity mutation is detected.
     *
     * The [MutationEvent] carries both the previous and current entity state, allowing subscribers
     * to observe what changed. The auto-reload path that reacts to optimistic-lock conflicts runs
     * inside `withEventsDisabled`, so the entity's mutation subscription does not fire during the
     * swap and `onEntityMutated` is not called for Conflict-induced state changes.
     */
    override fun onEntityMutated(event: MutationEvent<K, R>) {
        publisher.emitAsync(StandardCrudEvent.Update(event.newEntity, event.oldEntity))
    }

    /**
     * Closes this repository and, if this repository created the connection pool, shuts it down.
     *
     * The base class [close] cancels pending debounce timers, performs a synchronous final flush
     * of all pending ops, and cancels entity mutation subscriptions. HikariCP pool shutdown
     * follows only if this repository owns the data source.
     *
     * Idempotent: subsequent calls are safe no-ops.
     */
    override fun close() {
        if (closed) return
        try {
            super.close()
        } finally {
            if (ownsDataSource) {
                (dataSource as? HikariDataSource)?.close()
            }
        }
    }

    companion object {
        private val log = KotlinLogging.logger(SqlRepository::class.java.name)

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

        private fun isDuplicateConstraintException(e: ExposedSQLException): Boolean {
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

        private fun buildDataSource(jdbcUrl: String, poolSize: Int, schema: String?): HikariDataSource {
            if (jdbcUrl.startsWith("jdbc:sqlite:")) {
                log.warn {
                    "SQLite JDBC URL '$jdbcUrl' passed to SqlRepository(jdbcUrl, ...) without connectionInitSql; " +
                        "FK enforcement and WAL mode are not configured. Prefer SqliteRepository.fileBacked(...) " +
                        "or SqliteRepository.inMemory(...) for the curated PRAGMA bundle."
                }
            }
            val config =
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    this.maximumPoolSize = poolSize
                    schema?.let { this.schema = it }
                }
            return HikariDataSource(config)
        }

        /**
         * Converts a `java.util.UUID` to `kotlin.uuid.Uuid` for Exposed column operations.
         * Exposed 1.x uses `kotlin.uuid.Uuid` natively; entity IDs may be `java.util.UUID`.
         */
        @OptIn(ExperimentalUuidApi::class)
        private fun toExposedId(id: Any): Any =
            if (id is UUID) id.toKotlinUuid() else id

        /**
         * Converts a `kotlin.uuid.Uuid` read from Exposed column back to `java.util.UUID` for
         * domain-model comparison. Junction `parent_id` / `item_id` columns return `kotlin.uuid.Uuid`;
         * entity IDs stored as `java.util.UUID` must be normalized to the same type for map lookups
         * and collection matching to succeed.
         */
        @OptIn(ExperimentalUuidApi::class)
        private fun toDomainId(id: Any): Any =
            if (id is Uuid) id.toJavaUuid() else id
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
private class ShadowEntityIdTable(
    tableName: String,
    columnName: String,
    idType: net.transgressoft.lirp.persistence.ColumnType
) : Table(tableName) {
    val idColumn: Column<*> =
        when (idType) {
            is net.transgressoft.lirp.persistence.ColumnType.IntType -> integer(columnName)
            is net.transgressoft.lirp.persistence.ColumnType.LongType -> long(columnName)
            is net.transgressoft.lirp.persistence.ColumnType.UuidType -> uuid(columnName)
            is net.transgressoft.lirp.persistence.ColumnType.VarcharType -> varchar(columnName, idType.length)
            is net.transgressoft.lirp.persistence.ColumnType.TextType -> text(columnName)
            else -> error("Unsupported id type for shadow target table: $idType")
        }
}
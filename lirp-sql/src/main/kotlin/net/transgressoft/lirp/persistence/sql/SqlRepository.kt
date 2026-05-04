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
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
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

    private val exposedTable: ExposedTable = ExposedTableInterpreter().interpret(tableDef)
    private val table: Table = exposedTable.table
    private val pkCol: Column<*> = exposedTable.columnsByName.getValue(tableDef.columns.first { it.primaryKey }.name)
    private val versionCol: Column<Long>? = exposedTable.versionCol
    private val db: Database = Database.connect(dataSource)
    private val log = KotlinLogging.logger(javaClass.name)

    init {
        try {
            // Auto-create the table if it does not yet exist (always, even when loadOnInit=false)
            transaction(db = db) {
                SchemaUtils.create(table)
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
     * Loads all existing rows from the database into memory.
     *
     * Called by [load] as part of the template method. Reads the full table contents via a
     * single SELECT query and returns the entities. After this method returns, the [dirty]
     * flag is reset so that the initial load does not trigger an immediate write-back.
     *
     * @return a map of entity ID to entity from the database, or an empty map if the table is empty.
     */
    override fun loadFromStore(): Map<K, R> {
        val entities =
            transaction(db = db) {
                table.selectAll().map { row -> tableDef.fromRow(row, table) }
            }
        dirty.set(false)
        return entities.associateBy { it.id }
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
            // Case 2a: local entity still present — swap canonical state into it, emit Conflict
            // with a clone of the pre-swap state as oldEntity for semantic clarity.
            // clone() on ReactiveEntity<K, R> returns ReactiveEntity<K, R>, so cast to R — the
            // implementation always returns its own type per ReactiveEntity.clone()'s contract.
            val inMemory = inMemoryOpt.get()

            @Suppress("UNCHECKED_CAST")
            val oldSnapshot = inMemory.clone() as R
            inMemory.withEventsDisabled {
                tableDef.applyRow(inMemory, canonicalRow, table)
            }
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
    }
}
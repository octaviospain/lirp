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
import net.transgressoft.lirp.event.LirpErrorHandler
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.PendingUpdate
import net.transgressoft.lirp.persistence.PersistentRepositoryBase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * SQL-backed reactive repository using JetBrains Exposed and HikariCP connection pooling.
 *
 * Extends [PersistentRepositoryBase] with a per-key write pipeline: CRUD operations update
 * in-memory state immediately (optimistic reads) and collapse incrementally into the base class'
 * pending cell map. The debounce timer drains the per-key snapshot to SQL via [writePending],
 * which executes the grouped (inserts/updates/deletes/clear) payload in a single transaction
 * using batch SQL where applicable.
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
    loadOnInit: Boolean = true,
    onError: LirpErrorHandler? = null
) : PersistentRepositoryBase<K, R>("SqlRepository-${tableDef.tableName}", loadOnInit, onError) {

    /**
     * ABI-preserving constructor: forwards to the 5-param primary with `onError = null`.
     *
     * Retained to keep binary compatibility with callers that were compiled against the
     * previous 4-param primary constructor signature.
     */
    constructor(dataSource: DataSource, tableDef: SqlTableDef<R>, ownsDataSource: Boolean, loadOnInit: Boolean):
        this(dataSource, tableDef, ownsDataSource, loadOnInit, null)

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
     * @param onError Optional handler invoked after logging when an async flush failure escapes
     *   the scheduled coroutine. When `null`, behavior is log-only.
     */
    @JvmOverloads
    constructor(
        dataSource: DataSource,
        tableDef: SqlTableDef<R>,
        loadOnInit: Boolean = true,
        onError: LirpErrorHandler? = null
    ):
        this(dataSource, tableDef, false, loadOnInit, onError)

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
     * @param onError Optional handler invoked after logging when an async flush failure escapes
     *   the scheduled coroutine. When `null`, behavior is log-only.
     */
    @JvmOverloads
    constructor(
        jdbcUrl: String,
        tableDef: SqlTableDef<R>,
        poolSize: Int = 10,
        schema: String? = null,
        loadOnInit: Boolean = true,
        onError: LirpErrorHandler? = null
    ) : this(buildDataSource(jdbcUrl, poolSize, schema), tableDef, true, loadOnInit, onError)

    private val interpreter = ExposedTableInterpreter()
    private val exposedTable: ExposedTable = interpreter.interpret(tableDef)
    private val table: Table = exposedTable.table
    private val pkCol: Column<*> = exposedTable.columnsByName.getValue(tableDef.columns.first { it.primaryKey }.name)
    private val versionCol: Column<Long>? = exposedTable.versionCol
    private val db: Database = Database.connect(dataSource)
    private val log = KotlinLogging.logger(javaClass.name)
    private val schemaInstaller: SqlSchemaInstaller<K, R>
    private val entityLoader: SqlEntityLoader<K, R>
    private val writePipeline: SqlWritePipeline<K, R>
    private val recovery: OptimisticLockRecovery<K, R>?

    /**
     * Bounded retry queue of entity ids whose post-commit recovery threw. Owned by [SqlRepository]
     * and shared with [recovery]; drained at the start of each [writePending] cycle.
     * Capped at [STALE_IDS_CAP] to prevent unbounded heap growth.
     */
    private val staleIds: ConcurrentHashMap<K, StaleEntry> = ConcurrentHashMap()

    // Resolved once: the type argument is erased, so the cast is unchecked by construction but
    // safe — JunctionAware members are typed on the same self-type R as this repository's tableDef.
    @Suppress("UNCHECKED_CAST")
    private val junctionAware: JunctionAware<R>? = tableDef as? JunctionAware<R>

    /**
     * Cached interpretations of this entity's junction descriptors, keyed by descriptor reference.
     * Reused across `loadFromStore`, `writePending`, and `installJunctionForeignKeys` so we don't
     * re-allocate Exposed [Table] objects on every call.
     */
    private val junctionTables: Map<JunctionTableDef, ExposedJunctionTable> =
        junctionAware?.junctionTableDefs.orEmpty().associateWith { interpreter.interpretJunction(it) }

    init {
        // Fail-loud co-presence guards. Implementing JunctionAware / VersionedTableDef forces the
        // members to exist, but the type system cannot enforce that junction descriptors and
        // accessors agree, nor that a @Version column is paired with a bumpVersion implementation.
        // KSP-generated _LirpTableDef classes always keep these in lock-step; only hand-written
        // SqlTableDefs can drift, and silently doing so corrupts junction state or strands the
        // in-memory version. Surfacing it here is the bug detector, not a recoverable condition.
        junctionAware?.let { ja ->
            val descriptorSet = ja.junctionTableDefs.toSet()
            val accessorDescriptorSet = ja.junctionAccessors.map { it.descriptor }.toSet()
            check(
                ja.junctionTableDefs.size == ja.junctionAccessors.size &&
                    descriptorSet == accessorDescriptorSet
            ) {
                "SqlTableDef '${tableDef::class.qualifiedName}' declares ${ja.junctionTableDefs.size} " +
                    "junction descriptor(s) but ${ja.junctionAccessors.size} junction accessor(s). " +
                    "A JunctionAware implementation must provide a matching accessor for every junction descriptor. " +
                    "Use the @PersistenceMapping KSP processor to generate both."
            }
        }
        check(versionCol == null || tableDef is VersionedTableDef<*>) {
            "SqlTableDef '${tableDef::class.qualifiedName}' has a @Version column but does not implement " +
                "VersionedTableDef. Versioned UPDATE/DELETE would run while bumpVersion is silently skipped, " +
                "leaving the in-memory version stale and triggering spurious conflicts. " +
                "Implement VersionedTableDef, or use the @PersistenceMapping KSP processor to generate it."
        }

        // Activate RECOVERY_FAILED events: SqlRepository is the only emitter today, but the
        // event type is declared on the lirp-api CrudEvent contract so subscribers across modules
        // can react.
        activateEvents(CrudEvent.Type.RECOVERY_FAILED)
        schemaInstaller = SqlSchemaInstaller(dataSource, tableDef, exposedTable, junctionTables, db)
        entityLoader = SqlEntityLoader(tableDef, exposedTable, junctionTables, db, ::publicRawInitializerFor)
        writePipeline = SqlWritePipeline(tableDef, exposedTable, junctionTables, pkCol, versionCol)
        recovery =
            versionCol?.let { vc ->
                OptimisticLockRecovery(
                    tableDef = tableDef,
                    table = exposedTable.table,
                    pkCol = pkCol,
                    versionCol = vc,
                    db = db,
                    staleIds = staleIds,
                    hydrateJunctions = ::hydrateJunctionsForEntity,
                    findById = ::findById,
                    removeFromMemoryOnly = ::removeFromMemoryOnly,
                    addToMemoryOnly = ::addToMemoryOnly,
                    disableEvents = ::disableEvents,
                    activateEvents = ::activateEvents,
                    emitAsync = { event -> publisher.emitAsync(event) },
                    emitRecoveryFailed = { id, ver, e -> publisher.emitAsync(StandardCrudEvent.RecoveryFailed<K, R>(id, ver, e)) }
                )
            }
    }

    init {
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
        schemaInstaller.installJunctionForeignKeys()
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
        schemaInstaller.installEntityForeignKeys()
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
        val byId = entityLoader.loadFromStore()
        dirty.set(false)
        return byId
    }

    private fun hydrateJunctionsForEntity(entity: R) {
        entityLoader.hydrateJunctionsForEntity(entity)
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
     * Executes the grouped pending payload against the database in a single transaction.
     *
     * Inserts use [batchInsert] for efficient bulk inserts when more than one row is involved.
     * Deletes use `deleteWhere` per id to stay dialect-portable. Updates are applied individually
     * per entity. When [hadClear] is `true`, every row in the table is wiped first; the inserts,
     * updates and deletes that arrived after the clear in the debounce window are then applied in
     * that order — guaranteeing single-aggregate atomicity for the whole window.
     *
     * For versioned tables (tableDef carries a `@Version` column), UPDATE and DELETE augment
     * their WHERE clause with `AND version = ?`. A zero-row-affected result is treated as an
     * optimistic-lock conflict and accumulated into a per-entity list; the accumulator does
     * NOT throw inside the transaction, so any non-conflicting operations in the same flush
     * still commit. After the transaction commits, every accumulated conflict is recovered
     * (auto-reload + [StandardCrudEvent.Conflict] emission) in its own short-lived transaction.
     *
     * Rationale: wrapping the whole flush in one SQL transaction is required for
     * single-aggregate atomicity, but letting a single conflict throw mid-transaction would
     * roll back every earlier insert/update/delete and the base class would drop the drained
     * snapshot — silently losing work. Accumulating instead preserves non-conflicting writes.
     */
    override fun writePending(
        inserts: List<R>,
        updates: List<PendingUpdate<K, R>>,
        deletes: List<Pair<K, Long?>>,
        hadClear: Boolean
    ) {
        // Drain the bounded retry queue from previous flush cycles before applying new writes.
        // A successful retry observes the freshest canonical state; a permanently-failing entry
        // escalates to RecoveryFailed and is removed.
        recovery?.drainStaleIds()
        log.debug {
            "writePending: ${inserts.size} insert(s), ${updates.size} update(s), ${deletes.size} delete(s), hadClear=$hadClear"
        }
        val conflicts = mutableListOf<PendingConflict<K>>()
        transaction(db = db) {
            // #202: junction rows must be wiped before the parent table when FKs may not yet be
            // installed (the construction-time window before installJunctionForeignKeys() runs).
            // When FKs ARE installed with ON DELETE CASCADE the explicit delete is a harmless
            // no-op; Exposed handles the redundant wipe gracefully.
            if (hadClear) {
                junctionTables.values.forEach { it.table.deleteAll() }
                table.deleteAll()
            }
            when {
                inserts.size > 1 -> writePipeline.executeBatchInsertList(inserts)
                inserts.size == 1 -> writePipeline.executeInsertSingle(inserts.first())
            }
            updates.forEach { writePipeline.executeUpdate(it, conflicts) }
            when {
                deletes.size > 1 -> writePipeline.executeBatchDeleteList(deletes, conflicts)
                deletes.size == 1 -> writePipeline.executeDeleteSingle(deletes.first(), conflicts)
            }
        }
        // The main transaction has committed. Recover every accumulated conflict outside it.
        recoverConflicts(conflicts)
        // Honor the base-class contract: `dirty` signals pending work and must be cleared once
        // the SQL transaction commits successfully. Without this, a repository reports itself as
        // dirty forever after the first successful flush even when pendingCells is empty.
        dirty.set(false)
    }

    /**
     * Recovers every accumulated optimistic-lock conflict after the main transaction has committed.
     * Each path re-SELECTs the canonical row and emits a [StandardCrudEvent.Conflict]. A recovery
     * failure must NOT escape to [writePending]: the base class would interpret it as a generic
     * write failure and re-enqueue the whole drained snapshot, re-applying the non-conflicting ops
     * that already succeeded. Failures are logged and the id is enqueued for bounded retry.
     */
    private fun recoverConflicts(conflicts: List<PendingConflict<K>>) {
        val rec = recovery ?: return
        conflicts.forEach { conflict ->
            try {
                rec.recoverEntityFromConflict(conflict.id, conflict.expectedVersion)
            } catch (e: Exception) {
                log.error(e) {
                    "recoverEntityFromConflict threw for id=${conflict.id} " +
                        "(expectedVersion=${conflict.expectedVersion}); conflict may not have been fully recovered"
                }
                // Enqueue for bounded retry on the next flush cycle. drainStaleIds() escalates to a
                // RecoveryFailed event after MAX_RECOVERY_ATTEMPTS total failures for the same id.
                // Preserve the ORIGINAL expectedVersion across retries: once an entry is queued, its
                // expectedVersion is the row state at first-conflict capture and must not be
                // overwritten by a subsequent conflict carrying a newer version, or the retry would
                // silently re-target a different row generation.
                staleIds.compute(conflict.id) { _, prev ->
                    prev?.copy(attempts = prev.attempts + 1)
                        ?: StaleEntry(conflict.expectedVersion, 1)
                }
            }
        }
        evictStaleIdsOverflow()
    }

    /**
     * Hard backstop against unbounded `staleIds` growth under pathological recovery failure. This
     * is a round-number cap, not a precision LRU — ConcurrentHashMap iteration order is acceptable
     * as the eviction heuristic.
     */
    private fun evictStaleIdsOverflow() {
        if (staleIds.size <= STALE_IDS_CAP) return
        val overflow = staleIds.size - STALE_IDS_CAP
        log.warn {
            "staleIds cap exceeded (${staleIds.size} > $STALE_IDS_CAP); " +
                "dropping $overflow oldest entries to prevent unbounded growth"
        }
        val it = staleIds.keys.iterator()
        var dropped = 0
        while (dropped < overflow && it.hasNext()) {
            it.next()
            it.remove()
            dropped++
        }
    }

    /**
     * Emits a [CrudEvent.Type.UPDATE] event to repository subscribers when an entity mutation is detected.
     *
     * The auto-reload path that reacts to optimistic-lock conflicts runs inside `withEventsDisabled`,
     * so the entity's mutation subscription does not fire during the swap and `onEntityMutated` is
     * not called for Conflict-induced state changes.
     *
     * The `oldEntities` map supplied to [StandardCrudEvent.Update] is produced by cloning the
     * current entity at repository re-publish time. This is intentional: EVNT-06 targets the
     * property-setter hot path where `clone()` was called per-mutation under subscriber load;
     * the repository-level UPDATE re-publish is a low-frequency lifecycle hook where a single
     * targeted clone is acceptable and no mutation event field carries the full pre-mutation snapshot.
     */
    @Suppress("UNCHECKED_CAST")
    override fun onEntityMutated(event: MutationEvent<K, R>) {
        // A targeted clone at this re-publish site supplies the `oldEntities` before-snapshot for
        // StandardCrudEvent.Update consumers. See KDoc above for rationale.
        publisher.emitAsync(StandardCrudEvent.Update(event.entity, event.entity.clone() as R))
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

    /**
     * Tracks a single id's retry state across flush cycles. `attempts` counts the total number
     * of `recoverEntityFromConflict` failures observed for the id (including the original
     * post-commit failure that enqueued it); on reaching [MAX_RECOVERY_ATTEMPTS] the entry is
     * escalated to a [StandardCrudEvent.RecoveryFailed] event and removed.
     */
    internal data class StaleEntry(val expectedVersion: Long, val attempts: Int)

    companion object {
        private val log = KotlinLogging.logger(SqlRepository::class.java.name)

        /**
         * Hard backstop for the [staleIds] retry queue. If recovery is failing on more than 1024
         * distinct ids the repository is in an unrecoverable state — the cap merely prevents
         * unbounded heap growth. Eviction order falls back to ConcurrentHashMap iteration order;
         * a precision LRU is not warranted at the cap-overflow boundary.
         */
        const val STALE_IDS_CAP: Int = 1024

        /**
         * Maximum number of consecutive failed recovery attempts for the same id before the
         * retry path escalates to a [StandardCrudEvent.RecoveryFailed] event and drops the entry.
         */
        const val MAX_RECOVERY_ATTEMPTS: Int = 3

        private fun buildDataSource(jdbcUrl: String, poolSize: Int, schema: String?): HikariDataSource {
            if (jdbcUrl.startsWith("jdbc:sqlite:")) {
                log.warn {
                    "SQLite JDBC URL '${ConnectionUrlSanitizer.sanitize(jdbcUrl)}' passed to SqlRepository(jdbcUrl, ...) without connectionInitSql; " +
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
    }
}
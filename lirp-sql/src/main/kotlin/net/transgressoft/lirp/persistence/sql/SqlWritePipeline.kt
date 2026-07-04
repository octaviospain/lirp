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
import net.transgressoft.lirp.persistence.PendingUpdate
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.uuid.toKotlinUuid

/**
 * Executes insert, update, and delete SQL operations for a [SqlRepository] flush cycle.
 *
 * Handles single and batch variants for each operation type, synchronises junction rows
 * after every successful parent write, and accumulates optimistic-lock conflicts into a
 * caller-supplied list rather than throwing — preserving non-conflicting ops in the same
 * transaction.
 *
 * All methods must be called from within an active Exposed transaction.
 */
internal class SqlWritePipeline<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    private val tableDef: SqlTableDef<R>,
    private val exposedTable: ExposedTable,
    private val junctionTables: Map<JunctionTableDef, ExposedJunctionTable>,
    private val pkCol: Column<*>,
    private val versionCol: Column<Long>?
) {
    private val table: Table = exposedTable.table

    // Resolved once: the type arguments are erased, so these casts are unchecked by construction
    // but safe — both capability interfaces are typed on the same self-type R as this pipeline's
    // tableDef. Centralizing them here also avoids repeating the cast at every call site.
    @Suppress("UNCHECKED_CAST")
    private val versionedTableDef: VersionedTableDef<R>? = tableDef as? VersionedTableDef<R>

    @Suppress("UNCHECKED_CAST")
    private val junctionAware: JunctionAware<R>? = tableDef as? JunctionAware<R>

    /**
     * Inserts a single entity row and synchronises its junction rows.
     */
    fun executeInsertSingle(entity: R) {
        table.insert { stmt ->
            tableDef.toParams(entity, table).forEach { (col, value) ->
                // Safe: col was registered by ExposedTableInterpreter from the declared LirpTableDef column type.
                // Exposed erases Column<T> to Column<*> at the statement-builder level; Column<Any?> is the canonical workaround.
                @Suppress("UNCHECKED_CAST")
                stmt[col as Column<Any?>] = value
            }
        }
        syncJunctionRows(entity)
    }

    /**
     * Batch-inserts all entities in a single statement and synchronises junction rows
     * for each entity individually afterward.
     */
    fun executeBatchInsertList(entities: List<R>) {
        if (entities.isEmpty()) return
        table.batchInsert(entities, shouldReturnGeneratedValues = false) { entity ->
            tableDef.toParams(entity, table).forEach { (col, value) ->
                // Safe: col was registered by ExposedTableInterpreter from the declared LirpTableDef column type.
                @Suppress("UNCHECKED_CAST")
                this[col as Column<Any?>] = value
            }
        }
        // Sync junction rows after the batch insert. A future optimisation could batch-insert every
        // junction row in a single statement; per-entity is correct and acceptable for typical
        // batch sizes.
        entities.forEach { syncJunctionRows(it) }
    }

    /**
     * Updates a single entity row. When a version column is present, augments the WHERE clause
     * with `AND version = expectedVersion` and accumulates a conflict entry in [conflicts] when
     * zero rows are affected instead of throwing, so other pending ops in the same transaction
     * are not rolled back. On success, bumps the in-memory version and synchronises junction rows.
     */
    fun executeUpdate(op: PendingUpdate<K, R>, conflicts: MutableList<PendingConflict<K>>) {
        val expected = op.expectedVersion
        val vc = versionCol
        // Invariant: a versioned table (vc != null) must always carry an expectedVersion.
        // extractVersion() reads toParams()[vc] as? Long and the version column is always
        // populated for @Version entities, so expected == null with vc != null is a caller bug.
        if (vc != null && expected == null) {
            error(
                "executeUpdate called with a null expectedVersion for versioned entity id=${op.entity.id}. " +
                    "This is a caller bug: extractVersion() should always produce a non-null Long for @Version entities."
            )
        }
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
                    // Skip the version column here — it is set exclusively via the explicit +1 override below so that
                    // toParams' pre-bump value never reaches the statement unguarded.
                    if (col == vc) return@forEach
                    @Suppress("UNCHECKED_CAST")
                    stmt[col as Column<Any?>] = value
                }
                // Advance the DB version to match the in-memory bump applied below. toParams
                // emits the pre-bump entity.version (what the caller saw); excluding it above
                // and setting it only here guarantees the version column is always written as
                // expected + 1, never as the stale pre-bump value.
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
                versionedTableDef?.bumpVersion(op.entity, expected + 1)
            }
        }
        // Wholesale-replace junction rows after the parent UPDATE succeeds. Skipped on optimistic
        // lock conflict (early return above) — the conflicting state will be reconciled via
        // recoverEntityFromConflict + the next user-driven UPDATE.
        syncJunctionRows(op.entity)
    }

    /**
     * Removes a single entity by id, deleting any junction rows referencing it first so the
     * parent delete cannot leave orphans when FKs have not yet been installed. When FKs are
     * installed with `ON DELETE CASCADE` the manual junction delete is a harmless no-op.
     * Accumulates a conflict entry in [conflicts] on zero-row-affected for versioned tables.
     */
    fun executeDeleteSingle(idAndVersion: Pair<K, Long?>, conflicts: MutableList<PendingConflict<K>>) {
        val (id, expected) = idAndVersion
        val vc = versionCol
        // #202: wipe junction rows for this id before the parent delete so the operation is
        // idempotent w.r.t. FK installation timing.
        if (junctionTables.isNotEmpty()) {
            val parentId: Any = toExposedId(id as Any)
            junctionTables.values.forEach { junction ->
                junction.table.deleteWhere { junction.parentIdCol eq parentId }
            }
        }
        val rowsAffected =
            table.deleteWhere {
                @Suppress("UNCHECKED_CAST")
                val pkPred = (pkCol as Column<Any?>).eq(toExposedId(id))
                if (expected != null && vc != null) pkPred and (vc eq expected) else pkPred
            }
        if (expected != null && vc != null && rowsAffected == 0) {
            // Accumulate instead of throwing — see executeUpdate rationale.
            conflicts.add(PendingConflict(id, expected))
        }
    }

    /**
     * Removes a batch of entities by id. Junction rows referencing each id are deleted before
     * the corresponding parent row so the operation is idempotent w.r.t. FK installation timing.
     * All deletes run inside the caller's outer transaction.
     */
    fun executeBatchDeleteList(idsWithVersions: List<Pair<K, Long?>>, conflicts: MutableList<PendingConflict<K>>) {
        // per-id independent DELETE loop. Accumulate conflicts rather than throwing so
        // the other ids still commit in the same transaction.
        val vc = versionCol
        val hasJunctions = junctionTables.isNotEmpty()
        idsWithVersions.forEach { (id, expected) ->
            // #202: wipe junction rows for this id before the parent delete.
            if (hasJunctions) {
                val parentId: Any = toExposedId(id as Any)
                junctionTables.values.forEach { junction ->
                    junction.table.deleteWhere { junction.parentIdCol eq parentId }
                }
            }
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
     * Synchronises the junction rows for [entity] using a delete-then-insert strategy. The
     * descriptor-driven `idsOf` accessor provides the current collection-ID state; previous
     * junction rows for this parent are deleted in bulk and re-inserted at positions
     * `0..ids.size - 1` (for ordered descriptors) or without position (for unordered).
     *
     * `executeDeleteSingle`/`executeBatchDeleteList` do NOT call this method — the parent-side
     * FK's `ON DELETE CASCADE` (installed by [SqlRepository.installJunctionForeignKeys]) reaps
     * the junction rows automatically.
     */
    private fun syncJunctionRows(entity: R) {
        if (junctionTables.isEmpty()) return
        val accessors = junctionAware?.junctionAccessors ?: return

        for (accessor in accessors) {
            val descriptor = accessor.descriptor
            val junction = junctionTables[descriptor] ?: continue
            val ids = accessor.idsOf(entity).toList()
            val parentId: Any = toExposedId(entity.id as Any)

            junction.table.deleteWhere { junction.parentIdCol eq parentId }

            // Batch-insert all junction rows in a single statement rather than one round-trip per
            // item. The deleteWhere above runs unconditionally so clearing a collection still wipes
            // its rows; the insert is skipped when there is nothing to write.
            if (ids.isNotEmpty()) {
                junction.table.batchInsert(ids.withIndex(), shouldReturnGeneratedValues = false) { (index, itemId) ->
                    this[junction.parentIdCol] = parentId
                    this[junction.itemIdCol] = toExposedId(itemId)
                    junction.positionCol?.let { posCol -> this[posCol] = index }
                }
            }
        }
    }

    private fun toExposedId(id: Any): Any =
        if (id is UUID) id.toKotlinUuid() else id
}

/**
 * Per-entity optimistic-lock conflict accumulated during a [SqlWritePipeline] flush.
 * Conflicts from UPDATE, DELETE, and per-id batch-delete paths share this shape;
 * all recovery happens post-commit in [OptimisticLockRecovery].
 */
internal data class PendingConflict<K>(val id: K, val expectedVersion: Long)
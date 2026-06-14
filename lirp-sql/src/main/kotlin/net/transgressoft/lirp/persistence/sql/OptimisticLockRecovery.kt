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
import net.transgressoft.lirp.event.StandardCrudEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.toKotlinUuid

/**
 * Recovers entities after optimistic-lock conflicts and drains the stale-id bounded retry queue
 * between [SqlRepository] flush cycles.
 *
 * On conflict, the canonical row is re-selected and the in-memory entity is reconciled:
 * - If the row still exists, the scalar and junction state are swapped in and a
 *   [StandardCrudEvent.Conflict] is emitted.
 * - If the row was deleted by a third writer, the in-memory entity is removed and a
 *   deletion-sentinel [StandardCrudEvent.Conflict] (actualVersion = -1) is emitted.
 * - If the local entity was deleted but the canonical row survived, it is reconstructed
 *   and re-inserted into memory without re-enqueueing a create pending op.
 *
 * Failures during recovery are queued in [staleIds] and retried on the next flush cycle up to
 * [SqlRepository.MAX_RECOVERY_ATTEMPTS] times, after which a [StandardCrudEvent.RecoveryFailed]
 * event is emitted and the entry is dropped.
 */
internal class OptimisticLockRecovery<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    private val tableDef: SqlTableDef<R>,
    private val table: Table,
    private val pkCol: Column<*>,
    private val versionCol: Column<Long>,
    private val db: Database,
    private val staleIds: ConcurrentHashMap<K, SqlRepository.StaleEntry>,
    private val hydrateJunctions: (R) -> Unit,
    private val findById: (K) -> Optional<out R>,
    private val removeFromMemoryOnly: (R) -> Unit,
    private val addToMemoryOnly: (R) -> Unit,
    private val disableEvents: (CrudEvent.Type) -> Unit,
    private val activateEvents: (CrudEvent.Type) -> Unit,
    private val emitAsync: (CrudEvent<K, R>) -> Unit,
    private val emitRecoveryFailed: (K, Long, Exception) -> Unit
) {
    private val log = KotlinLogging.logger(javaClass.name)

    /**
     * Shared recovery path for a single conflict accumulated during a flush. Re-SELECTs the
     * canonical row; if missing, emits a deletion-sentinel Conflict and removes the in-memory
     * entity; otherwise reconciles state and emits Conflict with the canonical version.
     *
     * @param id The entity id whose write conflicted.
     * @param expectedVersion The version the failed operation targeted.
     */
    fun recoverEntityFromConflict(id: K, expectedVersion: Long) {
        // Defensive: unreachable under normal flow — conflict implies versioned repo.
        val vc = versionCol

        val canonicalRow =
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
            emitAsync(
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
            hydrateJunctions(inMemory)
            emitAsync(
                StandardCrudEvent.Conflict(
                    oldEntity = oldSnapshot,
                    newEntity = inMemory,
                    expectedVersion = expectedVersion,
                    actualVersion = actualVersion
                )
            )
        } else {
            // Case 2b: our DELETE was defeated. The entity is no longer in in-memory state
            // but the canonical row exists — reconstruct and re-insert without enqueueing an
            // insert PendingOp (the row is already persisted).
            val reconstructed = tableDef.fromRow(canonicalRow, table)
            hydrateJunctions(reconstructed)
            // Suppress the Create event that would otherwise fire from addToMemoryOnly →
            // VolatileRepository.add. Recovery should look like a single Conflict to
            // subscribers, not Create + Conflict.
            disableEvents(CrudEvent.Type.CREATE)
            try {
                addToMemoryOnly(reconstructed)
            } finally {
                activateEvents(CrudEvent.Type.CREATE)
            }
            emitAsync(
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
     * Drains [staleIds] in iteration order. For each entry, attempts the recovery again in a
     * fresh transaction so one failure does not roll back another's success. On success the
     * entry is removed; on failure with attempts already at [SqlRepository.MAX_RECOVERY_ATTEMPTS]
     * the entry is escalated via [StandardCrudEvent.RecoveryFailed] and removed; otherwise the
     * entry's attempt count is incremented and it remains queued for the next flush cycle.
     */
    fun drainStaleIds() {
        if (staleIds.isEmpty()) return
        // Snapshot to avoid mutation-during-iteration; concurrent inserts from the post-commit
        // loop in this same flush would race otherwise.
        val snapshot = staleIds.toMap()
        for ((id, entry) in snapshot) {
            try {
                transaction(db = db) {
                    recoverEntityFromConflict(id, entry.expectedVersion)
                }
                staleIds.remove(id)
            } catch (e: Exception) {
                // Count this drain attempt BEFORE deciding to escalate so escalation fires on
                // the MAX_RECOVERY_ATTEMPTS-th failure (not the (MAX+1)-th). The initial
                // post-commit failure already records attempts=1, so the on-disk semantics are
                // "escalate after exactly MAX_RECOVERY_ATTEMPTS total failures for this id".
                val nextAttempts = entry.attempts + 1
                if (nextAttempts >= SqlRepository.MAX_RECOVERY_ATTEMPTS) {
                    log.error(e) {
                        "recoverEntityFromConflict permanently failed for id=$id after " +
                            "${SqlRepository.MAX_RECOVERY_ATTEMPTS} attempts; emitting RecoveryFailed"
                    }
                    emitRecoveryFailed(id, entry.expectedVersion, e)
                    staleIds.remove(id)
                } else {
                    log.warn(e) {
                        "recoverEntityFromConflict attempt $nextAttempts failed for id=$id; will retry"
                    }
                    staleIds.compute(id) { _, prev ->
                        SqlRepository.StaleEntry((prev ?: entry).expectedVersion, nextAttempts)
                    }
                }
            }
        }
    }

    private fun toExposedId(id: Any): Any =
        if (id is UUID) id.toKotlinUuid() else id
}
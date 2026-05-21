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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.ReactiveEntity
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Per-key atomic pending write state held inside the repository's debounced write pipeline.
 *
 * A single cell represents the net intent for one key between debounce flushes: the most recent
 * insert, update, or delete after all prior writes have collapsed against each other. Cells are
 * mutated through atomic [java.util.concurrent.ConcurrentHashMap.compute] applications of
 * [mergeWriterSide] (writer hot-path) and [mergeOlder] (failure restoration), so the per-key
 * collapse algebra runs incrementally rather than as a linear pass over a queue.
 *
 * @param K the comparable key type of the entity.
 * @param R the reactive entity type.
 */
sealed interface PendingCell<K : Comparable<K>, R : ReactiveEntity<K, R>> {
    /**
     * Net intent: persist [entity] as a brand-new row. Carries no `expectedVersion` because
     * inserts always start at version 0 and cannot trigger an optimistic-lock conflict.
     */
    data class Insert<K : Comparable<K>, R : ReactiveEntity<K, R>>(
        val entity: R
    ) : PendingCell<K, R>

    /**
     * Net intent: persist [entity] as an update to an existing row.
     *
     * @property entity the latest entity state observed during the debounce window.
     * @property expectedVersion the FIRST optimistic-lock version observed across all merged
     *   updates in this debounce window. Preserving the first-observed value (not the latest)
     *   makes the whole debounce window behave as a single atomic mutation against the store:
     *   any concurrent writer who committed since the first local mutation will cause the entire
     *   window to fail with a conflict, exactly as if the mutations had been issued in one
     *   transaction. `null` when the entity has no `@Version` column.
     */
    data class Update<K : Comparable<K>, R : ReactiveEntity<K, R>>(
        val entity: R,
        val expectedVersion: Long?
    ) : PendingCell<K, R>

    /**
     * Net intent: remove the row keyed by this cell's slot.
     *
     * @property expectedVersion the optimistic-lock version captured at `remove()` time from the
     *   entity's current `@Version` value, reflecting the caller's view at the moment they
     *   requested the removal. `null` when the entity has no `@Version` column.
     */
    data class Delete<K : Comparable<K>, R : ReactiveEntity<K, R>>(
        val expectedVersion: Long?
    ) : PendingCell<K, R>
}

/**
 * Carrier struct passed to `writePending` so the underlying store can apply each update with its
 * (entity, expectedVersion) pair without re-deriving them from a [PendingCell.Update] variant.
 *
 * @property entity the latest entity state for this id.
 * @property expectedVersion the first-observed optimistic-lock version, or `null` for unversioned
 *   entities.
 */
data class PendingUpdate<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val entity: R,
    val expectedVersion: Long? = null
)

/**
 * Writer-side per-key collapse: merges an [incoming] write intent into the [current] cell stored
 * for the same key, returning the new cell (or `null` if the net effect is "no row pending", which
 * signals `ConcurrentHashMap.compute` to remove the slot).
 *
 * Transition table (rows that produce a cell-removal yield `null`):
 *
 * | current | incoming     | result                                                       |
 * |---------|--------------|--------------------------------------------------------------|
 * | null    | Insert       | Insert                                                       |
 * | null    | Update       | Update                                                       |
 * | null    | Delete       | Delete                                                       |
 * | Insert  | Insert(e')   | Insert(e')                                                   |
 * | Insert  | Update(e',_) | Insert(e') — expectedVersion dropped (insert starts at v=0)  |
 * | Insert  | Delete       | null — insert + delete cancels                               |
 * | Update  | Insert       | Insert — id-reuse, latest intent wins                        |
 * | Update  | Update(e',_) | Update(e', first-observed expectedVersion preserved)         |
 * | Update  | Delete(v')   | Delete(v') — Delete's view at `remove()` supersedes          |
 * | Delete  | Insert       | Insert — id-reuse pattern                                    |
 * | Delete  | Update       | Delete — update ignored, row is being removed                |
 * | Delete  | Delete       | current Delete — idempotent                                  |
 *
 * The first-observed `expectedVersion` rule across consecutive Updates is the carrier of the
 * debounce-window atomic-mutation invariant established with `@Version` optimistic-lock support:
 * any concurrent writer that commits since the first local mutation must surface as a conflict
 * across the entire window, regardless of how many updates collapsed into one.
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>> mergeWriterSide(
    current: PendingCell<K, R>?,
    incoming: PendingCell<K, R>
): PendingCell<K, R>? =
    when (current) {
        null -> incoming
        is PendingCell.Insert ->
            when (incoming) {
                is PendingCell.Insert -> incoming
                is PendingCell.Update -> PendingCell.Insert(incoming.entity)
                is PendingCell.Delete -> null
            }
        is PendingCell.Update ->
            when (incoming) {
                is PendingCell.Insert -> incoming
                is PendingCell.Update ->
                    PendingCell.Update(
                        incoming.entity,
                        // First-observed wins: only fall through to the incoming version when no
                        // prior version was captured for this debounce window.
                        current.expectedVersion ?: incoming.expectedVersion
                    )
                is PendingCell.Delete -> incoming
            }
        is PendingCell.Delete ->
            when (incoming) {
                is PendingCell.Insert -> incoming
                is PendingCell.Update -> current
                is PendingCell.Delete -> current
            }
    }

/**
 * Failure-restoration per-key collapse: merges a snapshotted [older] cell (taken before a failed
 * `writePending` I/O) back into the [newer] cell that may have accumulated in the live map while
 * the I/O was in flight. Asymmetric with [mergeWriterSide] because the snapshot represents the
 * pre-failure state, not a fresh write, so id-reuse and intent-divergence reconcile differently.
 *
 * Transition table:
 *
 * | older  | newer        | restored                                                          |
 * |--------|--------------|-------------------------------------------------------------------|
 * | any    | null         | older                                                             |
 * | Insert | Insert(e')   | Insert(e') — newer entity wins                                    |
 * | Insert | Update(e',_) | Insert(e') — net intent was insert→update; collapse to insert     |
 * | Insert | Delete       | Delete — newer intent wins, insert is cancelled                   |
 * | Update | Insert       | Insert — id-reuse, newer intent wins                              |
 * | Update | Update(e',_) | Update(e', older.expectedVersion) — first-observed wins           |
 * | Update | Delete       | Delete — newer intent wins                                        |
 * | Delete | Insert(e')   | Update(e', older.expectedVersion) — preserves delete-side version |
 * | Delete | Update(e',v')| Update(e', v') — invariant violation, keep newer (no error here)  |
 * | Delete | Delete       | Delete — idempotent                                               |
 *
 * The Delete + Update row is documented as an invariant violation reachable only via a writer-path
 * bug. It is kept as a logged fallthrough rather than an `error()` because [mergeOlder] runs on
 * the failure-recovery path and throwing here would compound a flush failure into a lost retry.
 * The log emits at ERROR severity so the invariant breach is visible in production diagnostics.
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>> mergeOlder(
    older: PendingCell<K, R>,
    newer: PendingCell<K, R>?
): PendingCell<K, R> =
    when (older) {
        is PendingCell.Insert ->
            when (newer) {
                null -> older
                is PendingCell.Insert -> newer
                is PendingCell.Update -> PendingCell.Insert(newer.entity)
                is PendingCell.Delete -> newer
            }
        is PendingCell.Update ->
            when (newer) {
                null -> older
                is PendingCell.Insert -> newer
                is PendingCell.Update -> PendingCell.Update(newer.entity, older.expectedVersion)
                is PendingCell.Delete -> newer
            }
        is PendingCell.Delete ->
            when (newer) {
                null -> older
                // Carry the delete-side `expectedVersion` into the restored Update so the retry
                // surfaces an optimistic-lock conflict if a concurrent writer committed since
                // the failed flush began. Returning `null` here would silently bypass the lock.
                is PendingCell.Insert -> PendingCell.Update(newer.entity, older.expectedVersion)
                is PendingCell.Update -> {
                    log.error {
                        "mergeOlder invariant violation: Delete + Update is unreachable through " +
                            "documented call paths (id=${newer.entity.id}, " +
                            "older.expectedVersion=${older.expectedVersion}, " +
                            "newer.expectedVersion=${newer.expectedVersion}). " +
                            "Preserving newer Update so flush can surface the underlying conflict."
                    }
                    newer
                }
                is PendingCell.Delete -> newer
            }
    }
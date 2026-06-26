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
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.PropertyChanged

/**
 * Carries the full delete intent captured inside a transaction block.
 *
 * Stores the entity alongside its key and expected version so the rollback path can re-add
 * the entity to in-memory state without a repository lookup, and the commit path can build
 * the `(id, expectedVersion)` pair needed by the SQL/JSON write pipeline.
 *
 * @param K the entity's key type
 * @param R the reactive entity type
 * @param id the entity's key
 * @param entity the entity instance at remove() time
 * @param expectedVersion the `@Version` value captured at remove() time; `null` for unversioned entities
 */
data class PendingDelete<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val id: K,
    val entity: R,
    val expectedVersion: Long?
)

/**
 * Mutable container accumulating the captured state for a single transaction block on [repo].
 *
 * During a transaction, three categories of data are collected here rather than in the normal
 * debounce pipeline:
 *
 * - **Op lists** ([inserts], [updates], [deletes]): the CRUD intents captured from `add`/`remove`
 *   calls made inside the block. On commit these are forwarded to the store in a single atomic
 *   write; on rollback they are discarded.
 *
 * - **Entity snapshots** ([entitySnapshots]): shallow property-value maps captured from each
 *   entity before its first mutation inside the block. Used to restore in-memory state on rollback
 *   without emitting spurious events.
 *
 * - **Deferred events** ([deferredEvents]): [MutationEvent]s buffered instead of published while
 *   the block runs. On commit, [collapseDeferredEvents] collapses them to one event per
 *   (entity, property) pair and releases them to subscribers. On rollback they are discarded.
 *
 * Visibility is `public` so that durable-store overrides of [PersistentRepositoryBase.commitTransactionBuffer]
 * in separate modules (such as `lirp-sql`) can receive and inspect the buffer. The class is not
 * part of the end-user API — it is a framework-internal worker type passed across module boundaries
 * by the transaction orchestration layer.
 *
 * @param K the comparable entity key type
 * @param R the reactive entity type
 * @param repo the repository that owns this transaction context
 */
class TransactionBuffer<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val repo: PersistentRepositoryBase<K, R>
) {
    /** Entities that were added to the repository inside the transaction block. */
    val inserts: MutableList<R> = mutableListOf()

    /** Entities that were mutated (but not inserted or deleted) inside the transaction block. */
    val updates: MutableList<PendingUpdate<K, R>> = mutableListOf()

    /** Delete intents captured inside the transaction block (id, entity, expectedVersion). */
    val deletes: MutableList<PendingDelete<K, R>> = mutableListOf()

    /**
     * Pre-block property-value snapshots, keyed by entity id.
     *
     * A snapshot is taken when an entity is first touched inside the block so that rollback can
     * restore exactly the pre-block state regardless of how many mutations happened inside.
     */
    val entitySnapshots: MutableMap<K, Map<String, Any?>> = mutableMapOf()

    /**
     * [MutationEvent]s buffered during the block instead of published to subscribers.
     *
     * Released (collapsed) on successful commit; discarded silently on rollback.
     */
    val deferredEvents: MutableList<MutationEvent<K, R>> = mutableListOf()

    /**
     * Collapses [deferredEvents] using a first-old / last-new algebra per (entity id, property name)
     * pair, then returns the resulting list.
     *
     * For each [PropertyChanged] event, only the first observed `oldValue` and the last observed
     * `newValue` are retained — collapsing a sequence of intermediate mutations into a single net
     * change identical to what a direct before/after snapshot would produce. Non-[PropertyChanged]
     * events (e.g. aggregate mutations) are forwarded unchanged.
     *
     * This mirrors the first-observed-version rule applied by [mergeWriterSide] to [PendingCell.Update]
     * collapse in the debounce pipeline, applied here to the event dimension rather than the
     * persistence dimension.
     *
     * @return collapsed list; order is insertion order of first occurrence per key
     */
    @Suppress("UNCHECKED_CAST")
    internal fun collapseDeferredEvents(): List<MutationEvent<K, R>> {
        // Ordered map: insertion order preserved so subscribers see events in a stable sequence.
        // Key: (entity id, property name) for PropertyChanged events.
        val collapsed = linkedMapOf<Pair<K, String>, PropertyChanged<K, R, *>>()
        val nonPropertyEvents = mutableListOf<MutationEvent<K, R>>()

        for (event in deferredEvents) {
            if (event is PropertyChanged<*, *, *>) {
                val pc = event as PropertyChanged<K, R, *>
                val key = pc.entity.id to pc.property.name
                val existing = collapsed[key]
                if (existing == null) {
                    // First observation: record as-is (oldValue is the pre-block value).
                    collapsed[key] = pc
                } else {
                    // Subsequent observation: keep first oldValue, update to latest newValue.
                    val updatedPc =
                        (existing as PropertyChanged<K, R, Any?>).copy(
                            newValue = (pc as PropertyChanged<K, R, Any?>).newValue,
                            newIndexKey = pc.newIndexKey
                        )
                    // A→B→A net-no-change: remove the entry entirely so no event fires.
                    if (updatedPc.oldValue == updatedPc.newValue && updatedPc.oldIndexKey == updatedPc.newIndexKey) {
                        collapsed.remove(key)
                    } else {
                        collapsed[key] = updatedPc
                    }
                }
            } else {
                nonPropertyEvents.add(event)
            }
        }

        return collapsed.values + nonPropertyEvents
    }
}
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

/**
 * Represents a pending write operation in a repository's operation queue.
 *
 * Operations are enqueued on every CRUD or mutation event and flushed
 * to the underlying store in batches. Before flushing, the queue is
 * collapsed via [collapse] to produce the minimal set of necessary writes.
 *
 * @param K the comparable key type of the entity.
 * @param R the reactive entity type.
 */
sealed interface PendingOp<K : Comparable<K>, R : ReactiveEntity<K, R>>

/**
 * Represents a pending insert of a single entity.
 *
 * @property entity the entity to be inserted.
 */
data class PendingInsert<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val entity: R
) : PendingOp<K, R>

/**
 * Represents a pending batch insert of multiple entities.
 *
 * @property entities the list of entities to be inserted together.
 */
data class PendingBatchInsert<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val entities: List<R>
) : PendingOp<K, R>

/**
 * Represents a pending update of a single entity's persisted state.
 *
 * @property entity the entity in its latest state to be persisted.
 */
data class PendingUpdate<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val entity: R
) : PendingOp<K, R>

/**
 * Represents a pending deletion of a single entity by its key.
 *
 * @property id the key of the entity to be deleted.
 */
data class PendingDelete<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val id: K
) : PendingOp<K, R>

/**
 * Represents a pending batch deletion of multiple entities by their keys.
 *
 * @property ids the list of keys of entities to be deleted together.
 */
data class PendingBatchDelete<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val ids: List<K>
) : PendingOp<K, R>

/**
 * Represents a pending clear of all entities from the repository store.
 *
 * When collapsed, a [PendingClear] cancels all preceding operations. Only
 * operations enqueued after the most recent clear are retained.
 */
class PendingClear<K : Comparable<K>, R : ReactiveEntity<K, R>> : PendingOp<K, R> {
    override fun equals(other: Any?): Boolean = other is PendingClear<*, *>

    override fun hashCode(): Int = javaClass.hashCode()
}

/** Tracks the resolved state of an entity's pending operation during collapse. */
private enum class OpKind { INSERT, UPDATE, DELETE }

private data class CollapseEntry<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val kind: OpKind,
    val id: K,
    val entity: R? = null
)

/**
 * Collapses a list of pending operations into a minimal, ordered set ready for persistence.
 *
 * The algorithm folds operations per entity ID using a [LinkedHashMap] to preserve insertion
 * order. Collapse rules applied per entity:
 * - Insert + Update -> Insert (latest state)
 * - Insert + Delete -> no-op (full collapse, entry removed)
 * - Insert + Delete + Insert (same ID) -> Insert (latest entity, ID-reuse)
 * - Multiple Updates -> single Update (latest state)
 * - Update + Delete -> Delete
 * - Delete + Update (same ID) -> Delete (update ignored — entity is being removed)
 * - Multiple individual Inserts on distinct entities -> [PendingBatchInsert]
 * - Multiple individual Deletes on distinct IDs -> [PendingBatchDelete]
 * - [PendingClear] cancels all preceding operations; post-clear operations are kept after the clear
 *
 * Output ordering: Clear (if any), then Inserts, then Updates, then Deletes.
 *
 * @param ops the raw queue of pending operations to collapse.
 * @return the minimal ordered list of operations to execute.
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>> collapse(ops: List<PendingOp<K, R>>): List<PendingOp<K, R>> {
    val state = LinkedHashMap<K, CollapseEntry<K, R>>()
    var hadClear = false

    for (op in ops) {
        when (op) {
            is PendingClear -> {
                state.clear()
                hadClear = true
            }
            is PendingInsert -> collapseInsert(state, op.entity)
            is PendingBatchInsert -> op.entities.forEach { collapseInsert(state, it) }
            is PendingUpdate -> collapseUpdate(state, op.entity)
            is PendingDelete -> collapseDelete(state, op.id)
            is PendingBatchDelete -> op.ids.forEach { collapseDelete(state, it) }
        }
    }

    val inserts = state.values.filter { it.kind == OpKind.INSERT }.map { it.entity!! }
    val updates = state.values.filter { it.kind == OpKind.UPDATE }.map { PendingUpdate<K, R>(it.entity!!) }
    val deletes = state.values.filter { it.kind == OpKind.DELETE }.map { it.id }

    val result = mutableListOf<PendingOp<K, R>>()

    if (hadClear) result.add(PendingClear())
    when {
        inserts.size > 1 -> result.add(PendingBatchInsert(inserts))
        inserts.size == 1 -> result.add(PendingInsert(inserts.first()))
    }
    result.addAll(updates)
    when {
        deletes.size > 1 -> result.add(PendingBatchDelete(deletes))
        deletes.size == 1 -> result.add(PendingDelete(deletes.first()))
    }

    return result
}

/**
 * Collapses an insert operation for [entity] into the accumulator [state].
 *
 * An insert always overwrites any existing entry for the same ID because every prior state
 * (no-op, existing insert, update, or delete) resolves to an INSERT of the latest entity.
 */
private fun <K : Comparable<K>, R : ReactiveEntity<K, R>> collapseInsert(
    state: MutableMap<K, CollapseEntry<K, R>>,
    entity: R
) {
    state[entity.id] = CollapseEntry(OpKind.INSERT, entity.id, entity)
}

/**
 * Collapses an update operation for [entity] into the accumulator [state].
 *
 * If a DELETE is already staged for this ID, the update is ignored — the entity is being removed.
 * If an INSERT is already staged, the entry stays as INSERT with the latest entity state.
 * Otherwise the entry becomes (or remains) an UPDATE with the latest state.
 */
private fun <K : Comparable<K>, R : ReactiveEntity<K, R>> collapseUpdate(
    state: MutableMap<K, CollapseEntry<K, R>>,
    entity: R
) {
    val existing = state[entity.id]
    if (existing?.kind == OpKind.DELETE) return
    val kind = if (existing != null && existing.kind == OpKind.INSERT) OpKind.INSERT else OpKind.UPDATE
    state[entity.id] = CollapseEntry(kind, entity.id, entity)
}

/**
 * Collapses a delete operation for [id] into the accumulator [state].
 *
 * If an INSERT is currently staged, the entry is removed entirely (insert + delete = no-op).
 * Otherwise a DELETE entry is recorded.
 */
private fun <K : Comparable<K>, R : ReactiveEntity<K, R>> collapseDelete(
    state: MutableMap<K, CollapseEntry<K, R>>,
    id: K
) {
    val existing = state[id]
    if (existing != null && existing.kind == OpKind.INSERT) {
        state.remove(id)
    } else {
        state[id] = CollapseEntry(OpKind.DELETE, id)
    }
}
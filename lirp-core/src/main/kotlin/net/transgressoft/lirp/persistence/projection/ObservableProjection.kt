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

package net.transgressoft.lirp.persistence.projection

/**
 * A single transformed-projection entry change.
 *
 * Exactly one of [oldValue]/[newValue] is null for a key that was added or removed; both are
 * non-null when an existing key's transformed value was recomputed in place:
 * - `oldValue == null && newValue != null` — the key was added (a create).
 * - `oldValue != null && newValue != null` — the key's value was replaced (an update).
 * - `oldValue != null && newValue == null` — the key was removed (a delete).
 *
 * @param PK the projection key type
 * @param V the transformed value type
 * @param key the projection key whose transformed value changed
 * @param oldValue the transformed value before the change, or null when the key was added
 * @param newValue the transformed value after the change, or null when the key was removed
 */
data class ProjectionEntryChange<PK, V>(val key: PK, val oldValue: V?, val newValue: V?) {
    init {
        require(oldValue != null || newValue != null) {
            "ProjectionEntryChange requires at least one of oldValue/newValue to be non-null (key=$key)"
        }
    }
}

/**
 * A [CloseableProjection] that additionally emits per-entry value changes carrying both the old
 * and new transformed values, mirroring the JavaFX `MapChangeListener` contract for the core layer.
 *
 * The value-transform projection factories return this type so a consumer can drive a CRUD-style
 * event stream directly from projection changes without maintaining a parallel diff cache of its
 * own: a null [ProjectionEntryChange.oldValue] is a create, a null
 * [ProjectionEntryChange.newValue] is a delete, and both-present is an update.
 *
 * ### When to use this versus a repository or aggregate-root subscription
 *
 * Repository and aggregate-root subscriptions (`subscribe { event: CrudEvent -> … }` /
 * `subscribeAsync { event: CrudEvent -> … }`) deliver `CrudEvent` instances keyed by **entity id**
 * and carrying the **entity** itself. They are the right choice when you need to react to individual
 * entity lifecycle transitions — the source of truth, with entity-level granularity.
 *
 * The projection entries-changed listener (this interface) is keyed by **projection key (bucket)**
 * and carries the **grouped/transformed value** — the result after re-keying, add-before-remove
 * ordering, soft-delete filtering, and the value transform have all been applied. It is the right
 * choice when you need to react to derived-view changes: for example, maintaining a downstream
 * registry of one derived object per bucket, or driving a CRUD stream for a secondary grouping of
 * the same entities.
 *
 * The two surfaces are **not relabelings of each other**:
 * - One entity `CrudEvent` can fan out to several projection bucket deltas when the entity's
 *   multi-key extractor yields multiple projection keys.
 * - Several entity `CrudEvent`s can coalesce into a single bucket delta when they all affect
 *   the same projection key.
 *
 * Subscribing to both is valid and produces no double-delivery: the repository subscription
 * sees all entity-level transitions, and the projection listener sees bucket-level derived-view
 * transitions. Closing the projection (via [CloseableProjection.close]) releases the
 * projection listener without affecting the repository subscription.
 *
 * ### Identity projection maps
 *
 * This interface is available only on the **value-transform** projection maps, not on the
 * identity projection maps (`Projection`, `RegistryProjection`, `MultiKeyProjection`,
 * `MultiKeyRegistryProjection`). Identity maps do not hold a per-key cached value; emitting
 * old/new bucket `List<E>` would require retaining the previous bucket contents per key inside
 * `ProjectionCore`, which fires its `onBucketsChanged` callback after the backing map has already
 * been mutated. That additional retention is not justified for this interface — see the identity-map
 * variants for bucket-set change notifications via `addOnBucketsChangedListener`.
 *
 * @param PK the projection key type
 * @param V the transformed value type
 */
interface ObservableProjection<PK, V> : CloseableProjection<PK, V> {

    /**
     * Registers [listener] to receive batched per-entry value changes after each projection delta.
     *
     * On registration the listener is invoked once with the current entries as adds (each with a
     * null [ProjectionEntryChange.oldValue]) so a late subscriber observes the full current state,
     * and then on every subsequent change. Multiple listeners are supported and fire in registration
     * order. The returned [AutoCloseable] deregisters the listener when closed.
     *
     * The delivery thread is implementation-defined: core implementations fire on the thread that
     * mutated the projection; FX implementations fire on the JavaFX Application Thread (dispatch
     * mode) or on the reactive flow-scope thread (non-dispatch mode).
     *
     * @param listener receiver of a non-empty batch of [ProjectionEntryChange] for one delta
     * @return an [AutoCloseable] that removes [listener] when closed
     */
    fun addOnEntriesChangedListener(listener: (List<ProjectionEntryChange<PK, V>>) -> Unit): AutoCloseable
}
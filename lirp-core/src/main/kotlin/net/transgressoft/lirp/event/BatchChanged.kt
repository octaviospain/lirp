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

package net.transgressoft.lirp.event

import net.transgressoft.lirp.entity.ReactiveEntity

/**
 * Typed mutation event emitted at the end of a `mutateAndPublish` block that touched one or more
 * reactive properties. Exactly one [BatchChanged] is emitted per block, regardless of how many
 * fields were modified.
 *
 * All carried values are immutable scalars captured synchronously during the block — no live
 * entity reference is used for the old-value state. This guarantees that deferred subscribers
 * observe the same per-field changes that occurred during the block, even if the entity is
 * mutated again before the subscriber drains.
 *
 * If no field net-changed during the block, the event is suppressed entirely.
 *
 * @param K the entity key type, which must be [Comparable]
 * @param R the entity type
 * @property entity the mutated entity
 * @property changes the list of per-field changes accumulated during the block; non-empty
 * @property versionAtMutation the pre-mutation optimistic-lock version, or `null` when the
 *   entity type has no `@Version` property or the version could not be captured
 * @property oldIndexKey the pre-mutation value of the first touched `@Indexed` property, or
 *   `null` when no indexed property was touched
 * @property newIndexKey the post-mutation value of the first touched `@Indexed` property, or
 *   `null` when no indexed property was touched
 */
data class BatchChanged<K, R>(
    override val entity: R,
    val changes: List<FieldChange<R, *>>,
    val versionAtMutation: Long? = null,
    val oldIndexKey: Any? = null,
    val newIndexKey: Any? = null
) : MutationEvent<K, R> where K : Comparable<K>, R : ReactiveEntity<K, R> {

    init {
        require(changes.isNotEmpty()) { "BatchChanged.changes must not be empty" }
    }

    override val type = MutationEvent.Type.BATCH_CHANGED
}
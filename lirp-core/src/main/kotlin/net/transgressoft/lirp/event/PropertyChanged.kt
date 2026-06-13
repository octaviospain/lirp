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
import kotlin.reflect.KProperty1

/**
 * Typed mutation event emitted when a single reactive property is assigned a new value.
 *
 * All carried values are immutable scalars captured synchronously at assignment time — no live
 * entity reference is used for change detection. This guarantees that deferred subscribers
 * (running on a coroutine after the setter returns) observe the same values that were current
 * at the moment of mutation, even if the entity is mutated again before the subscriber drains.
 *
 * [PropertyChanged] is a direct subtype of [MutationEvent] and a sibling of
 * [AggregateMutationEvent] — it does not extend it, nor is it a parent of it.
 *
 * @param K the entity key type, which must be [Comparable]
 * @param R the entity type
 * @param V the type of the mutated property value
 * @property entity the mutated entity
 * @property property the property whose value changed
 * @property oldValue the property value immediately before the assignment
 * @property newValue the property value immediately after the assignment
 * @property versionAtMutation the pre-mutation optimistic-lock version, or `null` when the
 *   entity type has no `@Version` property or the version could not be captured
 * @property oldIndexKey the pre-mutation value of the `@Indexed` property, or `null` when the
 *   changed property is not indexed
 * @property newIndexKey the post-mutation value of the `@Indexed` property, or `null` when the
 *   changed property is not indexed
 */
data class PropertyChanged<K, R, V>(
    override val entity: R,
    val property: KProperty1<R, V>,
    val oldValue: V,
    val newValue: V,
    val versionAtMutation: Long? = null,
    val oldIndexKey: Any? = null,
    val newIndexKey: Any? = null
) : MutationEvent<K, R> where K : Comparable<K>, R : ReactiveEntity<K, R> {

    override val type = MutationEvent.Type.PROPERTY_CHANGED
}
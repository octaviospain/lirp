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
 * Standard data class implementation of [AggregateMutationEvent].
 *
 * Emitted on a parent (referencing) entity's publisher when a referenced child entity mutates
 * or a mutable aggregate collection changes, and bubble-up propagation is enabled for that
 * reference (`@Aggregate(bubbleUp = true)`).
 *
 * The [newEntity] and [oldEntity] represent the **parent** entity's state at the time the
 * bubble-up event was emitted. Because the parent's own fields do not change during a child
 * mutation or collection change, both properties hold the same parent entity reference.
 * Subscribers should inspect [childEvent] to determine the nature of the change:
 *
 * ```kotlin
 * invoice.subscribe { event ->
 *     when (event) {
 *         is AggregateMutationEvent -> when (val child = event.childEvent) {
 *             is MutationEvent<*, *> -> println("Child '${event.refName}' property mutated")
 *             is CollectionChangeEvent<*> -> println("Collection '${event.refName}' changed: +${child.added.size} -${child.removed.size}")
 *         }
 *         else -> println("Direct mutation on invoice")
 *     }
 * }
 * ```
 *
 * @param K the type of the parent entity's ID, which must be [Comparable]
 * @param R the type of the parent entity
 * @property newEntity the parent entity reference (same as [oldEntity] — parent fields do not change)
 * @property oldEntity the parent entity reference (same as [newEntity])
 * @property refName the property name of the [@Aggregate][net.transgressoft.lirp.persistence.Aggregate]
 *   annotated property that triggered the bubble-up propagation
 * @property childEvent the original event emitted by the referenced child entity or collection
 */
data class StandardAggregateMutationEvent<K, R>(
    override val newEntity: R,
    override val oldEntity: R,
    override val refName: String,
    override val childEvent: LirpEvent<*>,
    override val type: MutationEvent.Type = MutationEvent.Type.MUTATE
) : AggregateMutationEvent<K, R> where K : Comparable<K>, R : ReactiveEntity<K, R>
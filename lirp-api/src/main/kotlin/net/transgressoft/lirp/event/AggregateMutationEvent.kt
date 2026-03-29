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
 * A [MutationEvent] emitted on a parent (referencing) entity when a referenced child entity mutates
 * and bubble-up propagation is enabled for that reference.
 *
 * Bubble-up is opt-in per reference via
 * [@Aggregate(bubbleUp = true)][net.transgressoft.lirp.persistence.Aggregate].
 * When active, a child mutation causes the parent entity to emit this event on its own publisher,
 * allowing parent subscribers to react to descendant state changes without subscribing to each child
 * individually.
 *
 * Propagation is single-level only: if `A` references `B` which references `C`, a mutation in `C`
 * notifies `B`'s subscribers but does not reach `A`'s subscribers.
 *
 * Subscribers can distinguish direct mutations ([MutationEvent]) from bubble-up events by checking
 * whether the received event is an instance of [AggregateMutationEvent]:
 *
 * ```kotlin
 * invoice.subscribe { event ->
 *     when (event) {
 *         is AggregateMutationEvent -> println("Child '${event.refName}' mutated")
 *         else -> println("Direct mutation on invoice")
 *     }
 * }
 * ```
 *
 * The [newEntity] and [oldEntity] properties inherited from [MutationEvent] represent the **parent**
 * entity's state at the time the bubble-up event was emitted, not the child entity's state.
 * The original child mutation is accessible via [childEvent].
 *
 * @param K the type of the parent entity's ID, which must be [Comparable]
 * @param R the type of the parent entity
 */
interface AggregateMutationEvent<K, R : ReactiveEntity<K, R>> : MutationEvent<K, R> where K : Comparable<K> {

    /**
     * The name of the reference that triggered the bubble-up propagation.
     *
     * Corresponds to the property name of the [@Aggregate][net.transgressoft.lirp.persistence.Aggregate]
     * annotated property on the parent entity.
     */
    val refName: String

    /**
     * The original [MutationEvent] that was emitted by the referenced child entity.
     *
     * Use this to access the child's previous and current state when reacting to a bubble-up event.
     */
    val childEvent: MutationEvent<*, *>
}
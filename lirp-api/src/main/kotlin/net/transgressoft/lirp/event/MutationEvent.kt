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
 * Represents a [LirpEvent] that tracks a mutation on a [ReactiveEntity].
 *
 * The base interface carries only the mutated entity and the event type. Typed subtypes
 * ([net.transgressoft.lirp.event.PropertyChanged], [net.transgressoft.lirp.event.BatchChanged])
 * carry the specific property-level changes and immutable captured context scalars needed for
 * deferred consumption.
 *
 * Subscribers that need to inspect individual property changes should pattern-match on the
 * concrete subtype:
 * ```kotlin
 * entity.subscribe { event ->
 *     when (event) {
 *         is PropertyChanged<*, *, *> -> println("${event.property.name}: ${event.oldValue} -> ${event.newValue}")
 *         is BatchChanged<*, *> -> event.changes.forEach { println("${it.property.name}: ${it.oldValue} -> ${it.newValue}") }
 *         else -> println("Mutation on ${event.entity}")
 *     }
 * }
 * ```
 *
 * @param K the type of the [ReactiveEntity] objects' id, which must be [Comparable]
 * @param R the type of the [ReactiveEntity] objects
 */
interface MutationEvent<K, R : ReactiveEntity<K, R>> : LirpEvent<MutationEvent.Type> where K: Comparable<K> {

    enum class Type(override val code: Int): EventType {
        PROPERTY_CHANGED(302),
        BATCH_CHANGED(303)
    }

    val entity: R
}
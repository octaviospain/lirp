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
 * Represents a [LirpEvent] that tracks a change from a [ReactiveEntity] by keeping both
 * the current state and the previous state.
 *
 * This event type is particularly useful for update operations where understanding
 * what changed between states is important for subscribers. It allows subscribers
 * to react to specific field changes or state transitions rather than just the
 * final state.
 *
 * @param K the type of the [ReactiveEntity] objects' id, which must be [Comparable]
 * @param R the type of the [ReactiveEntity] objects
 */
interface MutationEvent<K, R : ReactiveEntity<K, R>> : LirpEvent<MutationEvent.Type> where K: Comparable<K> {

    enum class Type(override val code: Int): EventType {
        MUTATE(301)
    }

    val newEntity: R
    val oldEntity: R
}
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
 * Interface for reactive primitive values that need change tracking.
 *
 * This interface provides a reactive wrapper around primitive values, allowing them
 * to participate in the reactive entity system. Changes to the wrapped value will
 * trigger notifications to subscribers, enabling reactive behavior for simple types.
 *
 * @param V The type of primitive value being wrapped, which must be non-nullable
 */
interface ReactivePrimitive<V : Any> : ReactiveEntity<String, ReactivePrimitive<V>> {
    /**
     * The wrapped primitive value.
     *
     * Changes to this property will trigger notification events to subscribers.
     * The value is nullable to allow for uninitialized or cleared states.
     */
    var value: V?
}
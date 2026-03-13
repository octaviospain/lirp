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

package net.transgressoft.lirp.persistence.json.primitives

import net.transgressoft.lirp.persistence.ReactivePrimitiveWrapper
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A reactive wrapper for Boolean values.
 *
 * This class enables Boolean values to participate in the reactive entity system,
 * with changes to the value triggering notification events to subscribers. It includes
 * custom serialization for JSON persistence.
 *
 * @property id The unique identifier for this reactive boolean
 * @param initialValue The initial boolean value, can be null
 */
class ReactiveBoolean internal constructor(id: String, initialValue: Boolean?) :
    ReactivePrimitiveWrapper<ReactiveBoolean, Boolean>(id, initialValue) {

        @ExperimentalUuidApi
        constructor(initialValue: Boolean) : this(Uuid.random().toString(), initialValue)

        override fun clone(): ReactiveBoolean = ReactiveBoolean(id, value)

        override fun hashCode() = (id.hashCode() + value.hashCode()) * 31

        override fun equals(other: Any?) =
            when {
                this === other -> true
                other !is ReactiveBoolean -> false
                else -> id == other.id && value == other.value
            }

        override fun toString() =
            buildString {
                append("ReactiveBoolean(")
                append("id=$id, ")
                append("value=$value")
                append(")")
            }
    }
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

import kotlin.reflect.KProperty1

/**
 * Immutable per-field value carrier used in [BatchChanged] events.
 *
 * Holds the pre- and post-mutation values for a single entity property, captured synchronously
 * at assignment time. All fields are immutable scalars — no live entity reference is carried.
 *
 * @param R the entity type that owns the property
 * @param V the property value type
 * @property property the property that was mutated
 * @property oldValue the value before the mutation
 * @property newValue the value after the mutation
 */
data class FieldChange<R, V>(
    val property: KProperty1<R, V>,
    val oldValue: V,
    val newValue: V
)
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

package net.transgressoft.lirp.persistence.fx.projection

import net.transgressoft.lirp.persistence.AudioItem
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.SimpleSetProperty
import javafx.collections.FXCollections

/**
 * FX-backed projection value type used by two-phase transform tests.
 *
 * Wraps a [SimpleSetProperty] populated from [items] and a [ReadOnlyBooleanWrapper] bound
 * to a non-empty condition via [bind]. Construction touches JavaFX observables, so it is only
 * safe to call from the FX Application Thread.
 *
 * The [items] parameter accepts any entity list; single-key projection tests pass [AudioItem]
 * instances while multi-key projection tests pass any entity type that the source produces.
 */
@Suppress("UNCHECKED_CAST")
class AlbumFxView(val albumName: String, items: List<*>) {
    val trackSet: SimpleSetProperty<Any> =
        SimpleSetProperty(FXCollections.observableSet(items.toHashSet() as HashSet<Any>))
    val hasTracksProperty: ReadOnlyBooleanWrapper =
        ReadOnlyBooleanWrapper().also {
            it.bind(trackSet.emptyProperty().not())
        }
    val hasTracks: Boolean get() = hasTracksProperty.get()
}
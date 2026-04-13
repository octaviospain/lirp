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

package net.transgressoft.lirp.persistence.fx

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import javafx.beans.property.IntegerProperty
import javafx.beans.property.StringProperty

/**
 * Test fixture entity combining [reactiveProperty], fx scalar delegates, and the [@Indexed][Indexed]
 * annotation in a single class. Exercises the [LirpEntitySerializer][net.transgressoft.lirp.persistence.json.LirpEntitySerializer]
 * code paths for `constructorDelegateParams` merge, `restoreFxScalarProperties`,
 * and `restoreReactiveProperties` simultaneously.
 */
class CombinedDelegateEntity(
    override val id: Int,
    name: String,
    initialCategory: String = "",
    initialPriority: Int = 0
) : ReactiveEntityBase<Int, CombinedDelegateEntity>(), IdentifiableEntity<Int> {

    override val uniqueId: String get() = "combined-delegate-$id"

    // reactiveProperty delegate
    var name: String by reactiveProperty(name)

    // @Indexed + reactiveProperty delegate
    @Indexed
    var category: String by reactiveProperty(initialCategory)

    // FxScalar delegates
    val priorityProperty: IntegerProperty by fxInteger(initialPriority, dispatchToFxThread = false)
    val labelProperty: StringProperty by fxString("", dispatchToFxThread = false)

    override fun clone(): CombinedDelegateEntity =
        CombinedDelegateEntity(id, name, category, priorityProperty.get()).also {
            it.withEventsDisabled { it.labelProperty.set(labelProperty.get()) }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CombinedDelegateEntity) return false
        return id == other.id &&
            name == other.name &&
            category == other.category &&
            priorityProperty.get() == other.priorityProperty.get() &&
            labelProperty.get() == other.labelProperty.get()
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + priorityProperty.get()
        result = 31 * result + labelProperty.get().hashCode()
        return result
    }
}
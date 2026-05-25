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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.PersistenceProperty

/**
 * Shared fixture entity exercising the ctor-param `val` mutability-gate exemption: `label` is a
 * primary-constructor `val` (immutable after construction) and `notes` is a body-level mutable
 * `var`. The KSP processor must emit a full `SqlTableDef` for this shape — `fromRow` rebuilds
 * `label` via the primary constructor, and `applyRow` reassigns only `notes`.
 */
@PersistenceMapping(name = "ctor_val_fixture")
data class CtorValFixtureEntity(
    @PersistenceProperty(length = 64) override val id: String,
    @PersistenceProperty(length = 128) val label: String
) : ReactiveEntityBase<String, CtorValFixtureEntity>() {
    var notes: String by reactiveProperty("")

    override val uniqueId: String get() = id

    override fun clone(): CtorValFixtureEntity =
        CtorValFixtureEntity(id, label).also { copy ->
            copy.withEventsDisabled {
                copy.notes = notes
            }
        }
}
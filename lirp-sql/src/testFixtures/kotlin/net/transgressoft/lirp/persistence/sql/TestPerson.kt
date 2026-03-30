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

/**
 * Shared test entity with three mutable properties for SQL CRUD integration testing.
 *
 * Consumed by both the `test` and `integrationTest` source sets via testFixtures.
 */
class TestPerson(override val id: Int) : ReactiveEntityBase<Int, TestPerson>() {
    var firstName: String by reactiveProperty("")
    var lastName: String by reactiveProperty("")
    var age: Int by reactiveProperty(0)
    override val uniqueId: String get() = id.toString()

    override fun clone(): TestPerson =
        TestPerson(id).also { copy ->
            copy.withEventsDisabled {
                copy.firstName = firstName
                copy.lastName = lastName
                copy.age = age
            }
        }
}
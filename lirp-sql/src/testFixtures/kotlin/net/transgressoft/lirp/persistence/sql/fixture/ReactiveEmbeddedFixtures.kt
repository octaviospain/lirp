/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.lirp.persistence.sql.fixture

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Embeddable
import net.transgressoft.lirp.persistence.Embedded
import net.transgressoft.lirp.persistence.PersistenceMapping

/**
 * Geographic coordinates value object for SQL round-trip fixture scenarios.
 * Two string fields exercise the basic scalar-leaf flattening path under `@Embedded`.
 */
@Embeddable
data class LocationValue(val lat: String, val lon: String)

/**
 * Round-trip fixture for `@Embedded` on a **body-declared reactive property** — the form
 * `var location: LocationValue by reactiveProperty(...)`.
 *
 * Exercises that KSP-generated `applyScalarRow` reconstruction dispatches through the
 * `silentSetter` path when hydrating the embedded value on load, producing no spurious
 * property change events.
 */
@PersistenceMapping(name = "reactive_embedded_fixture")
class ReactiveEmbeddedFixtureEntity(id: Int) :
    ReactiveEntityBase<Int, ReactiveEmbeddedFixtureEntity>() {

    override val id: Int by reactiveProperty(id)

    @Embedded
    var location: LocationValue by reactiveProperty(LocationValue("", ""))

    override val uniqueId: String get() = "$id"

    override fun clone(): ReactiveEmbeddedFixtureEntity =
        ReactiveEmbeddedFixtureEntity(id).also { copy ->
            copy.withEventsDisabled {
                copy.location = location
            }
        }
}

/**
 * Round-trip fixture for `@Embedded` on a **constructor `var` parameter** (`@Embedded var location`
 * in the primary constructor).
 *
 * Exercises that a mutable constructor parameter correctly participates in the standard
 * `fromRow` path, independent of the body-declared setter-slot route.
 */
@PersistenceMapping(name = "ctor_var_embedded_fixture")
data class CtorVarEmbeddedFixtureEntity(
    override val id: Int,
    @Embedded var location: LocationValue
) : ReactiveEntityBase<Int, CtorVarEmbeddedFixtureEntity>() {
    override val uniqueId: String get() = "$id"

    override fun clone(): CtorVarEmbeddedFixtureEntity = copy()
}
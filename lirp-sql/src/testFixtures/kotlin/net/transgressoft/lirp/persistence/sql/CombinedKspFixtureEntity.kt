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
 * Compound fixture exercising every KSP robustness path landed in issue #207: a `private var`
 * excluded from bulk-load rehydration, `Short` and `Byte` fields mapped to `IntType` columns with
 * narrowing, and a ctor-param `val` non-PK column that reaches `SqlTableDef` codegen through the
 * refined mutability gate.
 *
 * Round-trip behaviour is asserted by `CombinedKspRobustnessIT` against all five supported
 * dialects — the joint compile of all four shapes on a single entity is the test target.
 */
@PersistenceMapping(name = "combined_ksp_fixture")
data class CombinedKspFixtureEntity(
    @PersistenceProperty(length = 64) override val id: String,
    @PersistenceProperty(length = 128) val label: String
) : ReactiveEntityBase<String, CombinedKspFixtureEntity>() {
    var year: Short by reactiveProperty(0)
    var nullableYear: Short? by reactiveProperty(null)
    var flag: Byte by reactiveProperty(0)
    var notes: String by reactiveProperty("")

    private var cache: Int = 0

    fun cacheValue(): Int = cache

    fun setCacheValue(v: Int) {
        cache = v
    }

    override val uniqueId: String get() = id

    override fun clone(): CombinedKspFixtureEntity =
        CombinedKspFixtureEntity(id, label).also { copy ->
            copy.withEventsDisabled {
                copy.year = year
                copy.nullableYear = nullableYear
                copy.flag = flag
                copy.notes = notes
                copy.setCacheValue(cacheValue())
            }
        }
}
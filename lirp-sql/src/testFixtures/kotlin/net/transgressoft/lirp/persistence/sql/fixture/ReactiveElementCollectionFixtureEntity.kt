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
import net.transgressoft.lirp.persistence.ElementCollection
import net.transgressoft.lirp.persistence.PersistenceMapping

/**
 * Round-trip fixture for `@ElementCollection` on **body-declared reactive properties** — the
 * canonical LIRP entity pattern. Mirrors [ElementCollectionFixtureEntity] but declares the two
 * collections as `var x by reactiveProperty(initial)` instead of constructor `val` parameters.
 *
 * Exercises that `@ElementCollection` composes with the same accessor pipeline that
 * `LirpReactivePropertyAccessor` provides for scalars — the KSP-generated `_LirpTableDef` populates
 * the collections through the property setters on row reload, not through the primary constructor.
 */
@PersistenceMapping(name = "reactive_element_collection_fixture")
class ReactiveElementCollectionFixtureEntity(id: Int) :
    ReactiveEntityBase<Int, ReactiveElementCollectionFixtureEntity>() {

    override val id: Int by reactiveProperty(id)

    @ElementCollection(elementConverter = IntRatingConverter::class)
    var ratings: Set<Rating> by reactiveProperty(emptySet())

    @ElementCollection(elementConverter = StringTagConverter::class)
    var tags: List<Tag> by reactiveProperty(emptyList())

    override val uniqueId: String get() = "$id"

    override fun clone(): ReactiveElementCollectionFixtureEntity =
        ReactiveElementCollectionFixtureEntity(id).also { copy ->
            copy.withEventsDisabled {
                copy.ratings = ratings
                copy.tags = tags
            }
        }
}
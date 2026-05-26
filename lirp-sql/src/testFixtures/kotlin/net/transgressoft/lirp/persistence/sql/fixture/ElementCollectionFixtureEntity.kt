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
 * Round-trip fixture for `@ElementCollection` — exercises both `Set<E>` with a non-`String` S
 * element converter ([IntRatingConverter]) and `List<E>` with a `String`-S element converter
 * ([StringTagConverter]).
 *
 * The `ratings` field verifies that native-Int JSON encoding produces `[1,2,3]` rather than the
 * string-coerced form. The `tags` field verifies that `List` order is preserved on read-back
 * (no terminal `.toSet()` collapse).
 *
 * Consumed by H2 unit tests and Testcontainers integration tests to assert that the KSP-generated
 * `_LirpTableDef` correctly encodes and decodes both element-collection columns across all
 * supported SQL dialects.
 */
@PersistenceMapping(name = "element_collection_fixture")
data class ElementCollectionFixtureEntity(
    override val id: Int,
    @ElementCollection(elementConverter = IntRatingConverter::class) val ratings: Set<Rating> = emptySet(),
    @ElementCollection(elementConverter = StringTagConverter::class) val tags: List<Tag> = emptyList()
) : ReactiveEntityBase<Int, ElementCollectionFixtureEntity>() {
    override val uniqueId: String get() = "$id"

    override fun clone(): ElementCollectionFixtureEntity = copy()
}
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

import net.transgressoft.lirp.persistence.ColumnConverter
import net.transgressoft.lirp.persistence.ColumnType

/**
 * A simple tag value used as the element type for the `List<Tag>` collection in
 * [ElementCollectionFixtureEntity]. The [StringTagConverter] persists each tag as its raw name
 * string, exercising the common `String`-S element-converter path.
 */
data class Tag(val name: String)

/**
 * A numeric rating value used as the element type for the `Set<Rating>` collection in
 * [ElementCollectionFixtureEntity]. The [IntRatingConverter] persists each rating as its integer
 * value, exercising the non-`String` S element-converter path (native-Int JSON encoding).
 */
data class Rating(val value: Int)

/**
 * Test fixture only — not shipped from production `lirp-*` modules.
 *
 * Converts [Tag] elements to/from their `String` name representation. Exercises the `String`-S
 * `@ElementCollection` path: JSON encoding produces a string-element array (e.g. `["rock","jazz"]`).
 */
object StringTagConverter : ColumnConverter<Tag, String> {
    override val sqlType: ColumnType = ColumnType.TextType

    override fun toSql(value: Tag): String = value.name

    override fun fromSql(raw: String): Tag = Tag(raw)
}

/**
 * Test fixture only — not shipped from production `lirp-*` modules.
 *
 * Converts [Rating] elements to/from their `Int` value. Exercises the non-`String` S
 * `@ElementCollection` path: JSON encoding produces a native-integer array (e.g. `[1,5,3]`),
 * not a string-coerced array (`["1","5","3"]`).
 */
object IntRatingConverter : ColumnConverter<Rating, Int> {
    override val sqlType: ColumnType = ColumnType.IntType

    override fun toSql(value: Rating): Int = value.value

    override fun fromSql(raw: Int): Rating = Rating(raw)
}
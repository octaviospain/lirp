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

package net.transgressoft.lirp.testing

import net.transgressoft.lirp.persistence.MutableAudioItem
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.positiveInt
import io.kotest.property.arbitrary.stringPattern

// Shared Kotest Arb builders for test entities. Centralised here so individual specs don't
// redeclare identical generators; specialised arbitraries (e.g. polymorphic customer types in
// persistence/json/JsonTestFixtures.kt) stay alongside their domain helpers.

/**
 * Builds a [MutableAudioItem] with a random positive id (up to 500,000) and a randomly generated
 * `title` using a 5+5 lowercase letter pattern. Pass [id] explicitly to pin the item to a known key.
 */
internal fun arbitraryAudioItem(id: Int = -1) =
    arbitrary {
        MutableAudioItem(
            id = if (id == -1) Arb.positiveInt(500_000).bind() else id,
            title = Arb.stringPattern("[a-z]{5} [a-z]{5}").bind()
        )
    }
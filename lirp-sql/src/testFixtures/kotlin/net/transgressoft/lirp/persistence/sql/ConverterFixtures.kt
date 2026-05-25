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

import net.transgressoft.lirp.persistence.ColumnConverter
import net.transgressoft.lirp.persistence.ColumnType
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Test fixture only — not shipped from production `lirp-*` modules.
 *
 * Persists a [Path] as its URI textual representation, allowing absolute paths to round-trip
 * across platforms without dialect-specific encoding concerns. Used by SQL round-trip tests to
 * exercise the `@PersistenceProperty(converter = …)` codegen path on non-scalar domain types.
 */
object PathConverter : ColumnConverter<Path, String> {
    override val sqlType: ColumnType = ColumnType.TextType

    override fun toSql(value: Path): String = value.toUri().toString()

    override fun fromSql(raw: String): Path = Paths.get(URI(raw))
}

/**
 * Test fixture only — not shipped from production `lirp-*` modules.
 *
 * Persists a [Duration] as a `Long` count of seconds. Used by SQL round-trip tests to
 * exercise the converter codegen path on a numeric persistence-facing scalar.
 */
object DurationConverter : ColumnConverter<Duration, Long> {
    override val sqlType: ColumnType = ColumnType.LongType

    override fun toSql(value: Duration): Long = value.toSeconds()

    override fun fromSql(raw: Long): Duration = Duration.ofSeconds(raw)
}
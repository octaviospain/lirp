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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Round-trip fixture for the built-in default `ColumnConverter`s — none of the columns below carry a
 * `@PersistenceProperty(converter = …)` annotation, so each is bound automatically by KSP from the
 * declared JDK type. Exercises non-null [Path], [Duration], [Instant], non-null [URI], and a nullable
 * [Path] to verify the generated `_LirpTableDef` routes reads and writes through the built-in
 * converters with null preserved end-to-end.
 */
@PersistenceMapping(name = "default_converter_entity")
data class DefaultConverterFixtureEntity(
    override val id: Int,
    val path: Path,
    val length: Duration,
    val recordedAt: Instant,
    val source: URI,
    val coverPath: Path?
) : ReactiveEntityBase<Int, DefaultConverterFixtureEntity>() {
    override val uniqueId: String get() = "$id"

    override fun clone(): DefaultConverterFixtureEntity = copy()
}
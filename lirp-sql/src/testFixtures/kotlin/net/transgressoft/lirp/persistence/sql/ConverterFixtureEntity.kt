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
import java.nio.file.Path
import java.time.Duration

/**
 * Round-trip fixture for `ColumnConverter` — exercises non-null [Path], non-null [Duration], and
 * nullable [Path] columns across SQL dialects.
 *
 * The entity is consumed by H2 unit tests and Testcontainers integration tests to verify that the
 * KSP-generated `_LirpTableDef` routes column reads through [PathConverter.fromSql] /
 * [DurationConverter.fromSql] and writes through the symmetric `toSql` calls, with nullable values
 * preserving `null` end-to-end.
 */
@PersistenceMapping(name = "converter_entity")
data class ConverterFixtureEntity(
    override val id: Int,
    @PersistenceProperty(converter = PathConverter::class) val path: Path,
    @PersistenceProperty(converter = DurationConverter::class) val length: Duration,
    @PersistenceProperty(converter = PathConverter::class) val coverPath: Path?
) : ReactiveEntityBase<Int, ConverterFixtureEntity>() {
    override val uniqueId: String get() = "$id"

    override fun clone(): ConverterFixtureEntity = copy()
}
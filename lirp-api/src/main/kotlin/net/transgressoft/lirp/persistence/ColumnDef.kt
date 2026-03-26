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

package net.transgressoft.lirp.persistence

/**
 * A single column definition within a [LirpTableDef] table descriptor.
 *
 * Instances are generated at compile time by the KSP `TableDefProcessor` and stored in the
 * `_LirpTableDef` object's [LirpTableDef.columns] list. At runtime, the `lirp-sql` module reads
 * these definitions to build JetBrains Exposed table declarations.
 */
data class ColumnDef(
    val name: String,
    val type: ColumnType,
    val nullable: Boolean,
    val primaryKey: Boolean
)
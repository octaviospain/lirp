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
 * Contract for KSP-generated table descriptors that describe how an entity maps to a persistence table.
 *
 * Each entity class annotated with [@PersistenceMapping][PersistenceMapping] or containing properties
 * annotated with [@PersistenceProperty][PersistenceProperty] causes the KSP `TableDefProcessor` to
 * generate a corresponding `{ClassName}_LirpTableDef` object implementing this interface.
 *
 * The descriptor is persistence-agnostic: it carries only the table name and column definitions.
 * The `lirp-sql` module interprets these descriptors via JetBrains Exposed — `fromRow()` and
 * `toParams()` are intentionally not part of this interface and belong to the SQL interpreter layer.
 *
 * @param E The entity type this table descriptor describes.
 */
interface LirpTableDef<E> {

    /** The name of the table or collection in the persistence backend. */
    val tableName: String

    /** The ordered list of column definitions for this table. */
    val columns: List<ColumnDef>
}
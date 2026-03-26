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

import net.transgressoft.lirp.persistence.LirpTableDef
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * SQL-specific extension of [LirpTableDef] that adds entity-row mapping methods
 * for use with JetBrains Exposed.
 *
 * KSP-generated `_LirpTableDef` objects implement this interface when `lirp-sql`
 * is on the classpath, providing typed `fromRow` and `toParams` conversions.
 *
 * @param E The entity type this table definition maps.
 */
interface SqlTableDef<E> : LirpTableDef<E> {

    /**
     * Converts a [ResultRow] returned by a query into an entity instance.
     *
     * @param row The result row from an Exposed query.
     * @param table The [Table] object containing column references for column lookup.
     * @return The reconstructed entity.
     */
    fun fromRow(row: ResultRow, table: Table): E

    /**
     * Converts an entity instance into a parameter map suitable for Exposed insert/update statements.
     *
     * @param entity The entity to convert.
     * @param table The [Table] object containing column references for column lookup.
     * @return A map from [Column] to the corresponding entity field value.
     */
    fun toParams(entity: E, table: Table): Map<Column<*>, Any?>
}
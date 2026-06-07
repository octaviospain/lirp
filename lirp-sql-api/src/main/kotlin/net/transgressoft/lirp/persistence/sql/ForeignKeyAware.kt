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

/**
 * Opt-in capability interface for SQL table definitions that declare single-entity foreign-key
 * constraints for scalar `@Aggregate` references.
 *
 * `SqlRepository` checks for this interface with an `as?` cast inside
 * `installEntityForeignKeys()` to install FK constraints after all tables have been created.
 * Entities without scalar `@Aggregate` references should not implement this interface.
 *
 * KSP-generated `_LirpTableDef` classes implement this interface automatically when the entity
 * declares one or more `@Aggregate` properties with a non-`NONE` cascade action — codegen
 * consumers are unaffected by the segregation. Only hand-written [SqlTableDef] implementers
 * that need FK constraints must add this interface; the compiler guides them to implement the
 * required member.
 */
interface ForeignKeyAware {

    /**
     * Returns the SQL foreign-key descriptors for the single-entity `@Aggregate` references
     * declared on this entity, in declaration order.
     *
     * @return The list of foreign-key descriptors. Must not be empty when this interface is implemented.
     */
    fun foreignKeys(): List<ForeignKeyDef>
}
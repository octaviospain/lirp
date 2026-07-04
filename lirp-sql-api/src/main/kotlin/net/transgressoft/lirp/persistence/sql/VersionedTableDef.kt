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
 * Opt-in capability interface for SQL table definitions that support optimistic locking
 * via a `@Version` column.
 *
 * `SqlRepository` checks for this interface with an `as?` cast before performing versioned
 * UPDATE and DELETE operations. Entities without a `@Version` column should not implement
 * this interface.
 *
 * KSP-generated `_LirpTableDef` classes implement this interface automatically when the
 * entity declares a `@Version` property — codegen consumers are unaffected by the segregation.
 * Only hand-written [SqlTableDef] implementers that support optimistic locking must add this
 * interface; the compiler guides them to implement the required member.
 *
 * @param E The entity type whose version column is managed.
 */
interface VersionedTableDef<E> {

    /**
     * Sets the `@Version` property of [entity] to [newVersion], bypassing reactive setters
     * so no events fire and no dirty flag is raised.
     *
     * Called by `SqlRepository` inside `entity.withEventsDisabled { ... }` after a successful
     * versioned UPDATE, to keep the in-memory version counter in sync with the database row.
     *
     * @param entity The entity whose version is being bumped in place.
     * @param newVersion The new version value (typically `expectedVersion + 1`).
     */
    fun bumpVersion(entity: E, newVersion: Long)

    /**
     * Reads the `@Version` property of [entity] directly, without materializing the entity's
     * full column parameter map.
     *
     * Called by `SqlRepository` on every versioned mutation to capture the expected version for
     * the optimistic-lock WHERE clause. Returning the field value directly avoids the O(columns)
     * cost of building a `toParams` map just to read a single cell.
     *
     * @param entity The entity whose current version is read.
     * @return The entity's current version counter.
     */
    fun versionOf(entity: E): Long
}
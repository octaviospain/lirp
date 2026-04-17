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

    /**
     * Applies a [ResultRow] into an existing [entity] instance, overwriting every mutable non-PK
     * property with the row's column values. Symmetric to [fromRow], which constructs a fresh instance.
     *
     * Used by [SqlRepository] to auto-reload canonical entity state after an optimistic-lock conflict
     * (see [net.transgressoft.lirp.event.StandardCrudEvent.Conflict]). Callers wrap this invocation
     * in `entity.withEventsDisabled { ... }` so the reassignments do not re-enqueue another UPDATE.
     *
     * Primary-key columns are skipped — they are immutable for a given entity instance and are
     * already correct because the row was SELECTed by id.
     *
     * @param entity The existing in-memory entity whose non-PK state will be overwritten.
     * @param row The Exposed result row carrying the canonical values.
     * @param table The [Table] object containing column references for column lookup.
     */
    fun applyRow(entity: E, row: ResultRow, table: Table)

    /**
     * Sets the `@Version` property of [entity] to [newVersion]. Symmetric to [applyRow] but scoped
     * to the single version column — used by [net.transgressoft.lirp.persistence.sql.SqlRepository]
     * to auto-bump the in-memory version counter after a successful versioned UPDATE (D-05).
     *
     * The default implementation is a no-op: entities without a `@Version` column simply ignore
     * the call, which preserves source compatibility for every hand-written [SqlTableDef] whose
     * entity type has no version column. The KSP `TableDefProcessor` generates a non-default
     * implementation for classes annotated with `@Version`.
     *
     * Callers MUST wrap the invocation in
     * [net.transgressoft.lirp.entity.ReactiveEntityBase.withEventsDisabled] so the assignment does
     * not re-enqueue another `PendingUpdate` or fire a [net.transgressoft.lirp.event.MutationEvent].
     *
     * @param entity The entity whose version is being bumped in place.
     * @param newVersion The new version value (typically `expectedVersion + 1`).
     */
    fun bumpVersion(entity: E, newVersion: Long) {
        // Default: no-op. Entities without @Version ignore this call.
    }
}
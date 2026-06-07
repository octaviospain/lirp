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

import net.transgressoft.lirp.persistence.LirpRawInitializer
import net.transgressoft.lirp.persistence.LirpTableDef
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * SQL-specific extension of [LirpTableDef] that adds the three mandatory entity-row mapping
 * methods for use with JetBrains Exposed.
 *
 * This interface carries only the core mapping contract: [fromRow], [toParams], [applyRow],
 * and [applyScalarRow]. Optional capabilities — optimistic locking, single-entity FK
 * constraints, and junction-table collection references — live on opt-in sub-interfaces:
 *
 * - [VersionedTableDef] — implement when the entity declares a `@Version` column.
 * - [ForeignKeyAware] — implement when the entity declares scalar `@Aggregate` FK references.
 * - [JunctionAware] — implement when the entity declares collection-typed `@Aggregate` references.
 *
 * KSP-generated `_LirpTableDef` classes implement the correct sub-interfaces automatically
 * based on the entity's annotations — codegen consumers are unaffected. Only hand-written
 * [SqlTableDef] implementers must add the relevant capability interfaces; the compiler guides
 * them to implement the required members.
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
     * Used by `SqlRepository` to auto-reload canonical entity state after an optimistic-lock conflict
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
     * Writes column values from [row] into the backing fields of an already-constructed [entity]
     * via the supplied [rawInit], bypassing reactive setters so no events fire, no dirty flag is
     * raised, and `lastDateModified` is not bumped.
     *
     * Used by `SqlRepository.loadFromStore` to materialize entities from a bulk SELECT. The caller
     * first constructs the entity via [fromRow] for primary-key and constructor-param values, then
     * invokes this method to populate the remaining `var` / reactive-backed fields directly through
     * [rawInit].entries.
     *
     * KSP-generated `_LirpTableDef` classes provide a full implementation that walks the
     * [LirpRawInitializer] entries and dispatches each silentSetter. Hand-written [SqlTableDef]
     * implementations that bypass KSP may provide a no-op body and instead populate all state
     * inside [fromRow] alone.
     *
     * @param entity The pre-constructed entity to populate. Must not be `null`.
     * @param row The Exposed result row.
     * @param table The [Table] object whose column references are looked up by name.
     * @param rawInit Compile-time-resolved entries pairing property names with silent setters.
     */
    fun applyScalarRow(entity: E, row: ResultRow, table: Table, rawInit: LirpRawInitializer<E>)
}
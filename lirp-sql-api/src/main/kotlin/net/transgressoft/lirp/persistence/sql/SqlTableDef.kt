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
     * Writes column values from [row] into the backing fields of an already-constructed [entity]
     * via the supplied [rawInit], bypassing reactive setters so no events fire, no dirty flag is
     * raised, and `lastDateModified` is not bumped.
     *
     * Used by `SqlRepository.loadFromStore` to materialize entities from a bulk SELECT. The caller
     * is expected to first construct the entity via [fromRow] for primary-key + constructor-param
     * values, then invoke this method to populate the remaining `var` / reactive-backed fields
     * directly through [rawInit].entries.
     *
     * The default implementation throws — KSP must generate an override on each `_LirpTableDef`
     * subclass mapping each `RawInitEntry.name` to its column on [table]. Hand-written
     * `SqlTableDef`s that bypass KSP can either provide their own override or call only
     * [fromRow] / [applyRow] and skip the raw-init fast path.
     *
     * @param entity The pre-constructed entity to populate. Must not be `null`.
     * @param row The Exposed result row.
     * @param table The [Table] object whose column references are looked up by name.
     * @param rawInit Compile-time-resolved entries pairing property names with silent setters.
     */
    fun applyScalarRow(entity: E, row: ResultRow, table: Table, rawInit: LirpRawInitializer<E>) {
        // Default: no-op. Hand-written SqlTableDefs that bypass KSP populate entity state inside
        // [fromRow] itself and have no separate scalar-row apply step. Generated `_LirpTableDef`
        // overrides this with a per-column dispatch driven by `rawInit.entries`. Hand-written
        // implementations may either provide their own override or rely on [fromRow] alone.
    }

    /**
     * Sets the `@Version` property of [entity] to [newVersion]. Symmetric to [applyRow] but scoped
     * to the single version column — used by [net.transgressoft.lirp.persistence.sql.SqlRepository]
     * to auto-bump the in-memory version counter after a successful versioned UPDATE.
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

    /**
     * Returns the SQL foreign-key descriptors generated by KSP for single-entity `@Aggregate`
     * references on this entity.
     *
     * The default returns an empty list, preserving source compatibility for hand-written
     * `SqlTableDef` implementations and for `_LirpTableDef` files generated by `lirp-ksp`
     * releases that predate FK support. The KSP processor overrides this method when the entity
     * declares one or more `@Aggregate` properties whose
     * [net.transgressoft.lirp.entity.CascadeAction] is not
     * [net.transgressoft.lirp.entity.CascadeAction.NONE].
     *
     * @return The list of foreign-key descriptors, in declaration order.
     */
    fun foreignKeys(): List<ForeignKeyDef> = emptyList()

    /**
     * The junction-table schema descriptors that back the `aggregateList` / `aggregateSet`
     * collection references on this entity.
     *
     * The default returns an empty list, preserving source compatibility for hand-written
     * `SqlTableDef` implementations and for `_LirpTableDef` files generated by `lirp-ksp`
     * releases that predate junction-table support. The KSP processor overrides this property when
     * the entity declares one or more `@Aggregate`-annotated collection properties.
     *
     * Each entry in the returned list is paired (by index, by reference, or by descriptor lookup)
     * with the corresponding [JunctionAccessor] in [junctionAccessors].
     */
    val junctionTableDefs: List<JunctionTableDef>
        get() = emptyList()

    /**
     * The typed accessors paired with [junctionTableDefs] for reading collection-ID state from an
     * entity instance.
     *
     * The default returns an empty list. The KSP processor overrides this property in lockstep
     * with [junctionTableDefs] for entities that declare collection-typed `@Aggregate` properties.
     *
     * Hand-written `SqlTableDef` implementations are not expected to populate this list — the
     * runtime check in `SqlRepository` surfaces a fail-loud error when [junctionTableDefs] is
     * non-empty but [junctionAccessors] is empty, preventing silent data divergence.
     */
    val junctionAccessors: List<JunctionAccessor<E>>
        get() = emptyList()

    /**
     * Applies pre-loaded junction rows to a freshly constructed [entity] during bulk load.
     *
     * Called by `SqlRepository.loadFromStore` once per junction descriptor after the bulk junction
     * `SELECT` has been grouped by parent ID. The default implementation is a no-op so that
     * hand-written `SqlTableDef`s without collection aggregates remain unaffected.
     *
     * The KSP-generated override wraps the assignment in
     * [net.transgressoft.lirp.entity.ReactiveEntityBase.withEventsDisabled] so the mutation does
     * not emit `CrudEvent.UPDATE` and does not bump `@Version`. The exact mutation path depends on
     * the entity's collection delegate: a plain `var List<K>` assignment when the property is
     * mutable, or the delegate's reconcile-time setter otherwise.
     *
     * @param entity The freshly constructed parent entity to apply junction rows to.
     * @param descriptor The junction descriptor identifying which collection ref the [ids] belong
     *   to (an entity may have multiple collection refs and therefore multiple descriptors).
     * @param ids The list of item-side primary-key values, in junction-table position order for
     *   ordered collections.
     */
    fun applyJunctionRows(entity: E, descriptor: JunctionTableDef, ids: List<Any>) {
        // Default: no-op. Hand-written defs without collection aggregates ignore this call.
    }
}
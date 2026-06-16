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
 * Opt-in capability interface for SQL table definitions that back `aggregateList` /
 * `aggregateSet` collection references via junction tables.
 *
 * `SqlRepository` checks for this interface with an `as?` cast to determine whether junction
 * tables must be created, their FK constraints installed, and their rows synchronised on each
 * flush cycle. Entities without collection-typed `@ToManyAggregates` references should not implement
 * this interface.
 *
 * Implementing this interface implies co-presence of all three members: every descriptor in
 * [junctionTableDefs] must have a corresponding accessor in [junctionAccessors], paired by
 * index and by [JunctionAccessor.descriptor] equality. The type system enforces that either
 * all members are supplied or none — eliminating the runtime size-equality check that was
 * previously needed when these members lived as defaulted no-ops on [SqlTableDef].
 *
 * KSP-generated `_LirpTableDef` classes implement this interface automatically when the entity
 * declares one or more collection-typed `@ToManyAggregates` properties — codegen consumers are
 * unaffected by the segregation. Only hand-written [SqlTableDef] implementers that use junction
 * tables must add this interface; the compiler guides them to implement all required members.
 *
 * @param E The entity type whose junction collections are managed.
 */
interface JunctionAware<E> {

    /**
     * The junction-table schema descriptors that back the `aggregateList` / `aggregateSet`
     * collection references on this entity, in declaration order.
     *
     * Each entry is paired (by index and by [JunctionAccessor.descriptor] equality) with the
     * corresponding accessor in [junctionAccessors].
     */
    val junctionTableDefs: List<JunctionTableDef>

    /**
     * The typed accessors paired with [junctionTableDefs] for reading collection-ID state from
     * an entity instance, in the same declaration order.
     *
     * Each accessor's [JunctionAccessor.descriptor] must match the entry at the same index in
     * [junctionTableDefs].
     */
    val junctionAccessors: List<JunctionAccessor<E>>

    /**
     * Applies pre-loaded junction rows to a freshly constructed [entity] during bulk load.
     *
     * Called by `SqlRepository.loadFromStore` once per junction descriptor after the bulk
     * junction SELECT has been grouped by parent ID. The implementation must wrap assignments
     * in `entity.withEventsDisabled { ... }` so the mutations do not emit a `CrudEvent.UPDATE`
     * and do not bump `@Version`.
     *
     * @param entity The freshly constructed parent entity to apply junction rows to.
     * @param descriptor The junction descriptor identifying which collection ref the [ids] belong to.
     * @param ids The list of item-side primary-key values, in junction-table position order for
     *   ordered collections.
     */
    fun applyJunctionRows(entity: E, descriptor: JunctionTableDef, ids: List<Any>)
}
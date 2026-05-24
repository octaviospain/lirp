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
 * Typed companion to [JunctionTableDef] that extracts the current collection-ID state from an
 * entity instance for junction-write.
 *
 * KSP generates one [JunctionAccessor] per `aggregateList` / `aggregateSet` collection ref. The
 * accessor is the only sanctioned bridge between the SQL layer and entity state for junction-row
 * synchronisation: `SqlRepository.writePending` reads [idsOf] for each pending entity and inserts a
 * junction row per element (with positional ordering for ordered collections).
 *
 * Hand-written `SqlTableDef` implementations are not required to provide a [JunctionAccessor]. The
 * runtime check in [SqlRepository] surfaces a fail-loud error when `junctionTableDefs.isNotEmpty()`
 * yet `junctionAccessors.isEmpty()`, preventing silent data divergence.
 *
 * @param E The entity type whose collection IDs this accessor exposes.
 */
interface JunctionAccessor<E> {

    /** Schema descriptor this accessor pairs with. */
    val descriptor: JunctionTableDef

    /**
     * Returns the collection-ID state for [entity] in declaration order. For ordered collections
     * (`aggregateList`), the iteration order corresponds to the position-column values written to
     * the junction table. For unordered collections (`aggregateSet`), iteration order is
     * irrelevant.
     *
     * @param entity The parent entity to read the collection-ID state from.
     * @return The collection-ID values, typed as [Any] so descriptor consumers can iterate without
     *   knowing the item-side primary-key type at compile time.
     */
    fun idsOf(entity: E): Collection<Any>
}
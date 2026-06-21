/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * Opt-in [SqlTableDef] variant that delegates entity construction to the entity's own module instead
 * of building the instance inside [fromRow].
 *
 * The standard [SqlTableDef.fromRow] must return a fully constructed entity, which forces the table
 * definition to call the entity's primary constructor. When the table definition lives in a different
 * Gradle module from the entity and that constructor is `internal` or `private`, `fromRow` cannot
 * compile without a public factory. Implementing this interface removes that requirement: the table
 * definition supplies only the constructor argument *values* (via [constructorParams]), and
 * `SqlRepository.loadFromStore` builds the instance through the entity's
 * `LirpRawConstructor` — resolved by appending `_LirpRawConstructor` to [entityClassName] — which is
 * co-located with the entity and therefore reaches its non-public constructor.
 *
 * Remaining `var` / reactive-backed fields are still populated by [applyScalarRow] through the
 * entity's `LirpRawInitializer`, exactly as for a [fromRow]-based table definition.
 *
 * Implementers override [entityClassName], [constructorParams], [toParams], [applyRow], and
 * [applyScalarRow]; [fromRow] must not be overridden — `SqlRepository` routes construction through
 * the constructor SPI and never calls it.
 *
 * @param E The entity type this table definition maps.
 */
interface RawConstructibleTableDef<E> : SqlTableDef<E> {

    /**
     * Binary name of the concrete entity class to construct (e.g.
     * `net.transgressoft.example.MyEntity`). `SqlRepository` appends `_LirpRawConstructor` and
     * resolves the constructor via `Class.forName`, mirroring the convention used for the entity's
     * other generated accessors.
     */
    val entityClassName: String

    /**
     * Extracts the primary-constructor argument values for one entity from [row].
     *
     * The returned map is keyed by constructor parameter name and consumed by the entity's
     * `LirpRawConstructor`. Only constructor parameters need to be present — every other field is
     * restored afterward by [applyScalarRow]. Optional constructor parameters may be omitted to fall
     * back to their declared defaults.
     *
     * @param row The Exposed result row for the entity being reconstructed.
     * @param table The [Table] whose column references are looked up by name.
     * @return constructor argument values keyed by parameter name.
     */
    fun constructorParams(row: ResultRow, table: Table): Map<String, Any?>

    /**
     * Never invoked for a [RawConstructibleTableDef] — construction is delegated to the entity's
     * `LirpRawConstructor`. Present only to satisfy the [SqlTableDef] contract.
     */
    override fun fromRow(row: ResultRow, table: Table): E =
        throw UnsupportedOperationException(
            "RawConstructibleTableDef delegates construction to the entity's LirpRawConstructor; fromRow is unused"
        )
}
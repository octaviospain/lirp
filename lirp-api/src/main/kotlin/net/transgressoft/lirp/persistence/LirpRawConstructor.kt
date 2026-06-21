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

package net.transgressoft.lirp.persistence

/**
 * Internal API consumed by KSP-generated or hand-authored code; not intended for direct use by
 * application code.
 *
 * Builds a bare entity instance from its primary-constructor argument values during SQL bulk-load.
 * Used by `SqlRepository.loadFromStore` (via a `RawConstructibleTableDef`) so the construction step
 * lives in the entity's own module — where its primary constructor is reachable even when that
 * constructor is `internal` or `private` — instead of in the persistence module that maps columns.
 * This is the construction counterpart to [LirpRawInitializer], which only populates the remaining
 * `var` / reactive-backed fields on an already-constructed instance.
 *
 * The implementation is resolved at runtime by appending the `_LirpRawConstructor` suffix to the
 * entity's binary name (the same `Class.forName` convention used for the other generated accessors),
 * so it must be a top-level class in the entity's package with a public no-arg constructor.
 *
 * JSON deserialization does not consult this SPI: `LirpEntitySerializer` constructs reactive entities
 * reflectively via their primary constructor instead.
 *
 * Implementations call the entity's primary constructor directly, mapping each required parameter from
 * [construct]'s `params` map. For example, an entity declared as
 * `internal class Foo internal constructor(val id: Int, val name: String)` is built by
 * `Foo(params["id"] as Int, params["name"] as String)`.
 *
 * @param E the entity type this constructor was generated or hand-authored for
 */
interface LirpRawConstructor<E> {

    /**
     * Builds a fresh entity from its primary-constructor argument values.
     *
     * @param params primary-constructor argument values keyed by parameter name, as produced by the
     *   matching `RawConstructibleTableDef.constructorParams`. Optional constructor parameters absent
     *   from the map fall back to their declared defaults.
     * @return the constructed entity, with only its constructor-supplied fields set; remaining fields
     *   are populated separately through [LirpRawInitializer].
     */
    fun construct(params: Map<String, Any?>): E
}
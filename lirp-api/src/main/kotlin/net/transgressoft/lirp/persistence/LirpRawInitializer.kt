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

package net.transgressoft.lirp.persistence

/**
 * Internal API consumed by KSP-generated code; not intended for direct use by application code.
 *
 * Used by `SqlRepository.loadFromStore` and `JsonFileRepository.loadFromStore` to apply per-row
 * scalar, reactive, `@ToOneAggregate`-single-ref-Id, `@Version`, and primary-key values to a freshly
 * constructed entity without going through reactive setters. The silent-setter path writes the
 * backing field directly, so no events fire, no dirty flag is raised, and `lastDateModified` is
 * not bumped during bulk load.
 *
 * Each entity class with persistable fields receives a compile-time generated implementation
 * named `{EntityName}_LirpRawInitializer` in the entity's package.
 *
 * @param T the entity type this initializer was generated for
 */
interface LirpRawInitializer<T> {

    /**
     * Pre-built entries with one silent setter per persisted field on the entity.
     */
    val entries: List<RawInitEntry<T>>
}

/**
 * Compile-time resolved descriptor for a single persisted field, pairing the property name with
 * a silent setter lambda that writes the backing field directly.
 *
 * @param T the entity type
 * @property name the property name as declared in the entity class
 * @property silentSetter writes the value into the entity without triggering events, the dirty
 *   flag, or a `lastDateModified` update — appropriate only for bulk-load from a persisted store
 */
data class RawInitEntry<T>(
    val name: String,
    val silentSetter: (T, Any?) -> Unit
)
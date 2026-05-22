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

import kotlinx.serialization.KSerializer

/**
 * Contract for KSP-generated reactive-property accessor classes.
 *
 * Each entity class containing `var x by reactiveProperty(...)`-delegated properties receives a
 * compile-time generated implementation named `{EntityName}_LirpReactivePropertyAccessor` in the
 * entity's package, discovered at runtime via a convention-based [Class.forName] lookup.
 *
 * The generated [entries] expose per-property silent setters and serializers that
 * `LirpEntitySerializer` uses to round-trip reactive-property-backed fields without reflection.
 * **This is API consumed by generated code — not intended for direct use by application code.**
 *
 * @param T the entity type this accessor was generated for
 */
interface LirpReactivePropertyAccessor<T : Any> {

    /**
     * Pre-built entries with direct get/silent-set lambdas and compile-time resolved serializers
     * for every reactive-property-backed field on the entity.
     */
    val entries: List<ReactivePropertyEntry<T>>
}

/**
 * Compile-time resolved descriptor for a single reactive-property-backed field, pairing the
 * property name with direct accessor lambdas and a pre-resolved serializer.
 *
 * @param T the entity type
 * @property name the property name as declared in the entity class
 * @property getter direct property reader compiled to a regular method call
 * @property silentSetter writes the new value via the delegate's `writeBackingDirectly` path,
 *   bypassing event emission, lastDateModified bumping, and clone comparison
 * @property serializer the [KSerializer] resolved at compile time for the property's value type
 */
data class ReactivePropertyEntry<T : Any>(
    val name: String,
    val getter: (T) -> Any?,
    val silentSetter: (T, Any?) -> Unit,
    val serializer: KSerializer<Any?>
)
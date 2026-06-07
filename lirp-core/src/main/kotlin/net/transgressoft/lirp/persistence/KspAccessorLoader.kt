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

import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Convention-based loader for KSP-generated accessor classes.
 *
 * Locates generated companions by appending a suffix to the entity class's binary name and
 * invoking [Class.forName]. Results are cached by `(className + suffix)` key so each generated
 * class is loaded at most once per JVM lifetime. Returns `null` when no generated class exists
 * (KSP not applied, anonymous entity, or no matching annotation on the entity type).
 *
 * The suffix constants mirror those in `LirpGenNames` (lirp-ksp), defined here to avoid
 * introducing a runtime dependency on the compile-only KSP module.
 */
internal object KspAccessorLoader {

    // Suffix constants that mirror LirpGenNames in lirp-ksp.
    // lirp-core must not depend on lirp-ksp at runtime, so these are defined independently here.
    internal const val INDEX_ACCESSOR_SUFFIX = "_LirpIndexAccessor"
    internal const val REF_ACCESSOR_SUFFIX = "_LirpRefAccessor"
    internal const val VIA_ACCESSOR_SUFFIX = "_LirpViaAccessor"
    internal const val RAW_INITIALIZER_SUFFIX = "_LirpRawInitializer"
    internal const val REGISTRY_INFO_SUFFIX = "_LirpRegistryInfo"

    // Uses Optional as the map value to cache both "found" and "not found" states —
    // ConcurrentHashMap does not accept null values directly.
    private val cache = ConcurrentHashMap<String, Optional<Any>>()

    /**
     * Loads a KSP-generated accessor for [entityClass] by appending [suffix] to the class binary
     * name and invoking [Class.forName], caching the result under `entityClass.name + suffix`.
     *
     * Returns `null` when [Class.forName] throws [ClassNotFoundException] — the entity either
     * has no KSP-generated companion for this suffix, is anonymous/local, or KSP was not applied.
     *
     * @param T the expected accessor type; the cast is unchecked because the generated class is
     *   known to implement the correct interface by construction
     * @param entityClass the entity's runtime class whose generated companion should be loaded
     * @param suffix the name suffix identifying the generated accessor type (e.g. [REF_ACCESSOR_SUFFIX])
     * @return the loaded and instantiated accessor, or `null` if none exists
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> load(entityClass: Class<*>, suffix: String): T? =
        cache.computeIfAbsent(entityClass.name + suffix) {
            try {
                val cls = Class.forName(entityClass.name + suffix)
                Optional.of(cls.getDeclaredConstructor().newInstance())
            } catch (_: ClassNotFoundException) {
                Optional.empty()
            } catch (e: ReflectiveOperationException) {
                // The generated companion class resolved but could not be instantiated. This is a
                // codegen/contract bug, not an absent-companion signal — surface it instead of
                // caching an empty result and re-running the failing reflection on every call.
                error(
                    "Generated accessor '${entityClass.name}$suffix' could not be instantiated; " +
                        "expected a public no-arg constructor: ${e.message}"
                )
            }
        }.orElse(null) as T?
}
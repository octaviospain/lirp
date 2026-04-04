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

import net.transgressoft.lirp.entity.IdentifiableEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Scoped container for all [Registry] instances belonging to a logical application scope.
 *
 * Replaces the global singleton pattern previously implemented via the `globalRegistries` and
 * `cascadeVisited` companion-object fields on [RegistryBase]. Repositories constructed with a
 * custom `LirpContext` register into that context rather than a shared global map, enabling
 * independent scopes for tests, plugins, or multi-tenant applications.
 *
 * **Typical usage:** Most production code relies on [LirpContext.default], which is a lazily
 * initialised global singleton equivalent to the previous companion-object state. No additional
 * configuration is needed in single-context applications.
 *
 * **Test isolation:** Tests that need independent registry state should create a fresh
 * `LirpContext()` per test and pass it to the repository constructor, rather than relying on
 * [LirpContext.default]. Alternatively, call [LirpContext.resetDefault] between tests when sharing
 * the default context.
 *
 * **Lifecycle:** Calling [close] deregisters all registered repositories and closes any that
 * implement [AutoCloseable]. After [close], no further repositories should be registered into
 * this context.
 */
class LirpContext : AutoCloseable {
    private val registriesMap: ConcurrentHashMap<Class<*>, Registry<*, *>> = ConcurrentHashMap()

    /**
     * Per-thread set of cascade keys tracking entities currently being cascade-processed.
     * Used by [RegistryBase.executeCascadeForEntity] to detect cycles in cascade graphs.
     * Scoped to this context so that concurrent contexts do not share cascade state.
     */
    internal val cascadeVisited: ThreadLocal<MutableSet<String>> = ThreadLocal.withInitial { mutableSetOf() }

    /**
     * Registers [registry] under [entityClass]. Returns `true` if the registration succeeded
     * (no prior registration for that class), `false` if [registry] is already registered.
     */
    internal fun register(entityClass: Class<*>, registry: Registry<*, *>): Boolean =
        registriesMap.putIfAbsent(entityClass, registry) == null

    /**
     * Removes [registry] from this context. Uses identity comparison so that a registry can
     * safely deregister itself without knowing the entity class key.
     */
    internal fun deregister(registry: Registry<*, *>) {
        registriesMap.entries.removeIf { (_, r) -> r === registry }
    }

    /**
     * Removes the registry registered under [entityClass] from this context.
     * Returns silently if no registry is registered for [entityClass].
     */
    internal fun deregisterByClass(entityClass: Class<*>) {
        registriesMap.remove(entityClass)
    }

    /**
     * Returns the [Registry] registered for [entityClass], or `null` if none is registered.
     *
     * @param entityClass the entity class to look up
     * @return the [Registry] registered for [entityClass], or `null` if none exists
     */
    fun registryFor(entityClass: Class<*>): Registry<*, *>? = registriesMap[entityClass]

    /**
     * Returns the [Registry] registered for entity type [E], or `null` if none is registered.
     *
     * Provides type-safe Kotlin access by delegating to [registryFor(Class)][registryFor] with
     * an unchecked cast. The cast is safe because the registry was registered under `E::class.java`,
     * so its entity type parameter matches [E] by construction.
     *
     * @param E the entity type to look up
     * @return the [Registry] for [E], cast to `Registry<*, E>?`, or `null` if none exists
     */
    inline fun <reified E : IdentifiableEntity<*>> registryFor(): Registry<*, E>? {
        @Suppress("UNCHECKED_CAST")
        return registryFor(E::class.java) as Registry<*, E>?
    }

    /**
     * Returns an immutable snapshot of the current registry map. The snapshot is consistent
     * for the duration of the caller's operation, even if registries are added or removed concurrently.
     */
    internal fun registriesSnapshot(): Map<Class<*>, Registry<*, *>> = HashMap(registriesMap)

    /**
     * Returns an immutable copy of the current registry map.
     */
    internal fun registries(): Map<Class<*>, Registry<*, *>> = HashMap(registriesMap)

    /**
     * Closes all registered repositories, then clears the registry map.
     *
     * Registries are closed in an arbitrary order. Exceptions from individual [AutoCloseable.close]
     * calls are silently swallowed to ensure all registries are attempted.
     */
    override fun close() {
        val snapshot = ArrayList(registriesMap.values)
        registriesMap.clear()
        snapshot.forEach { (it as? AutoCloseable)?.close() }
    }

    /**
     * Clears the registry map without closing any registered repositories.
     * Intended for test teardown where repositories are closed separately.
     */
    internal fun reset() {
        registriesMap.clear()
    }

    companion object {
        /**
         * The default global [LirpContext] used by repositories that are not given an explicit context.
         * Lazily initialised to avoid unnecessary allocation in tests that always supply their own context.
         */
        val default: LirpContext by lazy { LirpContext() }

        /**
         * Clears all registrations from the [default] context without closing the registered repositories.
         * Intended for test teardown when the [default] context was used implicitly.
         */
        @JvmStatic
        internal fun resetDefault() = default.reset()
    }
}
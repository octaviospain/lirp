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
import java.util.Optional
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Property delegate that implements a lazily-resolved, cached aggregate reference.
 *
 * Returned by [aggregateRef] factory function for use with Kotlin property delegation.
 * Implements both [ReactiveEntityReference] and [ReadOnlyProperty] so that the delegate
 * object itself is the reference handle — callers access `entity.refProp.resolve()` directly.
 *
 * Resolution is lazy and cached per unique ID value. The cache is stored as a
 * `(id, Optional<E>)` pair in an [AtomicReference]. On each [resolve] call the current
 * ID from [idProvider] is compared with the cached ID; if they differ the cache is
 * invalidated and the registry is queried fresh. This prevents stale resolutions after
 * an ID-change via `mutateAndPublish`.
 *
 * The delegate must be bound to a [Registry] via [bindRegistry] before [resolve] can
 * return a result. Binding happens automatically when the owning entity is first added
 * to a [VolatileRepository] through [RegistryBase.bindEntityRefs].
 *
 * Example:
 * ```kotlin
 * class Order(override val id: Long, var customerId: Int) : ReactiveEntityBase<Long, Order>() {
 *     @ReactiveEntityRef(bubbleUp = false)
 *     @Transient
 *     val customer by aggregateRef<Customer, Int> { customerId }
 * }
 *
 * // After adding the order to its repository:
 * val resolved: Optional<Customer> = order.customer.resolve()
 * ```
 *
 * @param E the referenced entity type
 * @param K the type of the referenced entity's ID
 * @param idProvider lambda that returns the current referenced entity ID from the owning entity
 */
class AggregateRefDelegate<E : IdentifiableEntity<K>, K : Comparable<K>>(
    private val idProvider: () -> K
) : ReactiveEntityReference<E, K>, ReadOnlyProperty<Any?, ReactiveEntityReference<E, K>> {

    /**
     * The bound registry used to look up the referenced entity by ID.
     * Null until [bindRegistry] is called (typically at entity add-time by [RegistryBase]).
     */
    @Volatile
    private var registry: Registry<K, E>? = null

    override val referenceId: K get() = idProvider()

    /**
     * Binds this delegate to the registry that holds the referenced entity type.
     * Called automatically by [RegistryBase.bindEntityRefs] when the owning entity
     * is added to a repository.
     */
    fun bindRegistry(registry: Registry<K, E>) {
        this.registry = registry
    }

    /**
     * Binds this delegate to an untyped registry, performing an unchecked cast internally.
     * Used by [RegistryBase.bindEntityRefs] when the registry type cannot be expressed at
     * the call site due to type erasure.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun bindRegistryUntyped(registry: Registry<*, *>) {
        this.registry = registry as Registry<K, E>
    }

    /**
     * Lazily resolves the referenced entity from the bound [Registry].
     *
     * Returns [Optional.empty] if:
     * - The delegate has not yet been bound to a registry (entity not yet added to a repository)
     * - The referenced entity does not exist in the registry
     *
     * Always performs a fresh [Registry.findById] lookup using the current [referenceId]. This
     * ensures that resolutions reflect the live state of the registry: if the referenced entity
     * was removed, [Optional.empty] is returned; if the referenced ID changed via
     * `mutateAndPublish`, the updated entity is returned on the next call.
     */
    @Suppress("UNCHECKED_CAST")
    override fun resolve(): Optional<E> {
        val reg = registry ?: return Optional.empty()
        return reg.findById(idProvider()) as Optional<E>
    }

    /**
     * Returns `this` so that the delegate object itself serves as the [ReactiveEntityReference]
     * handle — callers write `entity.refProp.resolve()` with no extra unwrapping.
     */
    override fun getValue(thisRef: Any?, property: KProperty<*>): ReactiveEntityReference<E, K> = this
}

/**
 * Creates a property delegate that declares a single typed aggregate reference by ID.
 *
 * The [idProvider] lambda is captured at entity construction time and evaluated on each
 * [ReactiveEntityReference.resolve] call to obtain the current referenced entity ID.
 * Resolution is cached and invalidated automatically when the ID value changes.
 *
 * **Requires KSP** — annotate the delegated property with [@ReactiveEntityRef][ReactiveEntityRef]
 * so the KSP processor generates the required `{ClassName}_LirpRefAccessor` class.
 *
 * Example:
 * ```kotlin
 * @ReactiveEntityRef(bubbleUp = false)
 * @Transient
 * val customer by aggregateRef<Customer, Int> { customerId }
 * ```
 *
 * @param E the referenced entity type, must extend [IdentifiableEntity]
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param idProvider lambda returning the current ID of the referenced entity
 * @return an [AggregateRefDelegate] implementing both [ReactiveEntityReference] and [ReadOnlyProperty]
 */
fun <E : IdentifiableEntity<K>, K : Comparable<K>> aggregateRef(
    idProvider: () -> K
): AggregateRefDelegate<E, K> = AggregateRefDelegate(idProvider)
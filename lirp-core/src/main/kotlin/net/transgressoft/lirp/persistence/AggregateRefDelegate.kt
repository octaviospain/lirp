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

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.AggregateMutationEvent
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import mu.KotlinLogging
import java.util.Optional
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Property delegate that implements a lazily-resolved aggregate reference with optional bubble-up
 * event propagation and configurable cascade behavior on parent entity deletion.
 *
 * Returned by [aggregateRef] factory function for use with Kotlin property delegation.
 * Implements both [ReactiveEntityReference] and [ReadOnlyProperty] so that the delegate
 * object itself is the reference handle — callers access `entity.refProp.resolve()` directly.
 *
 * Resolution is always performed fresh against the bound [Registry] — no caching is applied.
 * This ensures that resolutions correctly reflect live registry state: removed entities return
 * `Optional.empty()` and ID changes on the owning entity return the new entity on the next call.
 *
 * **Bubble-up propagation:** When enabled via `@ReactiveEntityRef(bubbleUp = true)`, the delegate
 * subscribes to the referenced entity's `changes` flow after [wireBubbleUp] is called. Each child
 * [MutationEvent] is forwarded to the parent entity's publisher as a
 * [net.transgressoft.lirp.event.StandardAggregateMutationEvent], allowing parent subscribers to
 * react to descendant state changes without subscribing to each child individually.
 * Use [cancelBubbleUp] to remove the subscription.
 *
 * **Cascade:** The [executeCascade] method executes the configured [CascadeAction] when the parent
 * entity is removed from its repository, as triggered by [VolatileRepository].
 *
 * The delegate must be bound to a [Registry] via [bindRegistry] before [resolve] can
 * return a result. Binding happens automatically when the owning entity is first added
 * to a [VolatileRepository] through [RegistryBase.bindEntityRefs].
 *
 * Example:
 * ```kotlin
 * class Order(override val id: Long, var customerId: Int) : ReactiveEntityBase<Long, Order>() {
 *     @ReactiveEntityRef(bubbleUp = true, onDelete = CascadeAction.DETACH)
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

    private val log = KotlinLogging.logger {}

    /**
     * The bound registry used to look up the referenced entity by ID.
     * Null until [bindRegistry] is called (typically at entity add-time by [RegistryBase]).
     */
    @Volatile
    private var registry: Registry<K, E>? = null

    /**
     * Active subscription to the referenced entity's changes flow for bubble-up propagation.
     * Null when bubble-up is not active or has been cancelled.
     */
    @Volatile
    private var bubbleUpSubscription: LirpEventSubscription<*, *, *>? = null

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
     * Subscribes to the referenced entity's `changes` flow to forward mutations to the parent.
     *
     * For each [MutationEvent] received from the referenced entity, a
     * [net.transgressoft.lirp.event.StandardAggregateMutationEvent] is emitted on the
     * [parentEntity]'s publisher via [ReactiveEntityBase.emitBubbleUpEvent]. The parent entity's
     * own state does not change — `newEntity` and `oldEntity` in the aggregate event both reference
     * the same parent instance.
     *
     * Guards against subscribing to a closed entity: if the referenced entity is already closed,
     * this method is a no-op. If the parent entity is not a [ReactiveEntityBase], this method is
     * also a no-op since only [ReactiveEntityBase] instances can emit bubble-up events.
     *
     * @param parentEntity the entity that owns this delegate; bubble-up events are emitted on its publisher
     * @param refName the property name of the reference, becomes the event's `refName`
     */
    @Suppress("UNCHECKED_CAST")
    fun wireBubbleUp(parentEntity: ReactiveEntity<*, *>, refName: String) {
        val referencedEntity = resolve().orElse(null) ?: return
        // Bubble-up only makes sense for ReactiveEntity children (which can emit mutation events)
        if (referencedEntity !is ReactiveEntity<*, *> || referencedEntity.isClosed) return

        // Type parameters are erased at runtime; the self-referential bound R : ReactiveEntity<K, R>
        // cannot be verified at the wildcard call site. ReactiveEntityBase.emitBubbleUpEvent() is used
        // here because it is defined on the entity class itself (which has the correctly-typed R),
        // allowing StandardAggregateMutationEvent to be constructed with the right bound at that site.
        val rawChild = referencedEntity as ReactiveEntity<Any, *>
        val subscription =
            rawChild.subscribe { childEvent ->
                // Only forward direct mutations (ReactiveMutationEvent), NOT AggregateMutationEvents.
                // This enforces single-level bubble-up: A->B->C mutates A, B notifies its subscribers,
                // but C should NOT receive A's event — only direct mutations to B would trigger C's bubble-up.
                if (!parentEntity.isClosed &&
                    parentEntity is ReactiveEntityBase<*, *> &&
                    childEvent !is AggregateMutationEvent<*, *>
                ) {
                    parentEntity.emitBubbleUpEvent(refName, childEvent)
                }
            }
        bubbleUpSubscription = subscription
    }

    /**
     * Cancels the active bubble-up subscription if one exists.
     *
     * Called by [VolatileRepository] when the parent entity is removed with
     * [CascadeAction.DETACH], and by [ReactiveEntityBase] on entity close.
     */
    fun cancelBubbleUp() {
        bubbleUpSubscription?.cancel()
        bubbleUpSubscription = null
    }

    /**
     * Executes the cascade action for this reference when the parent entity is being removed.
     *
     * - [CascadeAction.CASCADE]: resolves the referenced entity and removes it from its bound [Registry]
     *   if the registry implements [Repository]. If the registry is read-only, logs a warning and skips.
     *   If the referenced entity is already absent (e.g. removed by a prior cascade path), logs a warning
     *   and returns without throwing.
     * - [CascadeAction.RESTRICT]: scans all global registries for entities that reference the same target
     *   entity. Throws [IllegalStateException] if any entity other than [owningEntity] still references
     *   the target. Takes no action if the check passes.
     * - [CascadeAction.DETACH]: cancels the bubble-up subscription via [cancelBubbleUp]
     * - [CascadeAction.NONE]: intentional no-op
     *
     * @param cascadeAction the cascade action to execute
     * @param owningEntity the entity that owns this delegate; excluded from the RESTRICT reference check
     */
    @Suppress("UNCHECKED_CAST")
    fun executeCascade(cascadeAction: CascadeAction, owningEntity: Any) {
        when (cascadeAction) {
            CascadeAction.CASCADE -> {
                val repo = registry
                if (repo !is Repository<*, *>) {
                    log.warn { "Cannot execute CASCADE: bound registry is not a Repository for ${idProvider()}" }
                    return
                }
                val referencedEntity = resolve().orElse(null)
                if (referencedEntity == null) {
                    log.warn { "Entity(id=${idProvider()}) already removed by prior cascade" }
                    return
                }
                // Check for cycle: if the referenced entity has any CASCADE reference pointing back to
                // an entity already in the cascade-visited set on this thread, removing it would cause
                // an infinite cascade loop. Check one level ahead using the KSP-generated ref accessor.
                checkForCascadeCycle(referencedEntity)
                (repo as Repository<K, E>).remove(referencedEntity)
            }
            CascadeAction.RESTRICT -> {
                val targetId = idProvider()
                // Find which entity class this registry manages by matching against globalRegistries
                val targetClass =
                    RegistryBase.globalRegistries.entries
                        .firstOrNull { (_, reg) -> reg === registry }
                        ?.key
                        ?: return // Registry not bound or not registered — nothing to check

                @Suppress("UNCHECKED_CAST")
                for ((entityClass, otherRegistry) in RegistryBase.globalRegistriesSnapshot()) {
                    val accessor = RegistryBase.refAccessorFor(entityClass) ?: continue
                    for (entity in otherRegistry) {
                        // Exclude the entity that is triggering the cascade
                        if (entity === owningEntity) continue
                        for (entry in accessor.entries as List<RefEntry<Any, *>>) {
                            if (entry.referencedClass == targetClass && entry.idGetter(entity) == targetId) {
                                throw IllegalStateException(
                                    "Cannot cascade-delete ${targetClass.simpleName}(id=$targetId): still referenced by other entities"
                                )
                            }
                        }
                    }
                }
            }
            CascadeAction.DETACH -> cancelBubbleUp()
            CascadeAction.NONE -> { /* intentional no-op */ }
        }
    }

    /**
     * Checks whether [referencedEntity] has any CASCADE reference pointing back to an entity already
     * in the current thread-local cascade visited set. If a direct CASCADE reference from
     * [referencedEntity] targets an entity by class and ID that matches a cascade key already in the
     * visited set, an [IllegalStateException] is thrown with a "Cascade cycle detected" message.
     *
     * Uses the cascade key format `"${entityClass.name}:${entityId}"` which allows cycle detection
     * even after the entity has been removed from its repository. This is a one-level lookahead:
     * it inspects the immediate CASCADE refs of [referencedEntity] only. Deeper cycles are detected
     * transitively as the cascade chain unwinds.
     */
    @Suppress("UNCHECKED_CAST")
    private fun checkForCascadeCycle(referencedEntity: IdentifiableEntity<*>) {
        val visited = RegistryBase.cascadeVisitedGet()
        if (visited.isEmpty()) return
        val accessor = RegistryBase.refAccessorFor(referencedEntity.javaClass) ?: return
        for (entry in accessor.entries as List<RefEntry<Any, *>>) {
            if (entry.cascadeAction != CascadeAction.CASCADE) continue
            val targetId = entry.idGetter(referencedEntity)
            val targetKey = RegistryBase.cascadeKey(entry.referencedClass, targetId)
            check(targetKey !in visited) {
                "Cascade cycle detected: entity '${entry.referencedClass.simpleName}(id=$targetId)' is already being cascaded on this thread"
            }
        }
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
 * Resolution always queries the bound registry fresh — no caching is applied.
 *
 * **Requires KSP** — annotate the delegated property with [@ReactiveEntityRef][ReactiveEntityRef]
 * so the KSP processor generates the required `{ClassName}_LirpRefAccessor` class.
 *
 * Example:
 * ```kotlin
 * @ReactiveEntityRef(bubbleUp = true, onDelete = CascadeAction.DETACH)
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
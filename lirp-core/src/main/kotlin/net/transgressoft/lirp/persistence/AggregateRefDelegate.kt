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
import java.util.concurrent.atomic.AtomicReference
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
 * **Lazy re-wiring:** When the reference ID changes (e.g., via `mutateAndPublish` on the owning
 * entity), the bubble-up subscription is re-wired to the new referenced entity on the next
 * [resolve] call. If the new entity is not yet in the registry, the old subscription remains
 * active until a future [resolve] succeeds.
 *
 * **Thread safety:** [registryRef] and [bubbleUpSubscription] use [AtomicReference] for lock-free
 * visibility. The [wireBubbleUp] and [cancelBubbleUp] methods use `getAndSet` to atomically swap
 * subscriptions, preventing subscription leaks under concurrent access.
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
    private val registryRef = AtomicReference<Registry<K, E>?>(null)

    /**
     * Active subscription to the referenced entity's changes flow for bubble-up propagation.
     * Null when bubble-up is not active or has been cancelled.
     * Uses AtomicReference so [wireBubbleUp] and [cancelBubbleUp] can atomically swap the
     * subscription without holding a lock, preventing subscription leaks under concurrent access.
     */
    private val bubbleUpSubscription = AtomicReference<LirpEventSubscription<*, *, *>?>(null)

    /**
     * The reference ID of the entity that was most recently wired for bubble-up.
     * Compared against the current [idProvider] result in [resolve] to detect FK changes.
     * Reset to null by [cancelBubbleUp] to force a re-wire attempt on next [resolve].
     */
    private val lastWiredId = AtomicReference<Any?>(null)

    /**
     * The parent entity passed to [wireBubbleUp]. Non-null signals that bubble-up is configured
     * for this delegate, enabling the lazy re-wire check in [resolve].
     * Written once at wiring time; read under happens-before from [resolve].
     */
    @Volatile
    private var bubbleUpParent: ReactiveEntity<*, *>? = null

    /**
     * The reference property name passed to [wireBubbleUp], used when constructing aggregate events
     * during re-wiring in [rewireIfResolvable].
     */
    @Volatile
    private var bubbleUpRefName: String? = null

    override val referenceId: K get() = idProvider()

    /**
     * Binds this delegate to the registry that holds the referenced entity type.
     * Called automatically by [RegistryBase.bindEntityRefs] when the owning entity
     * is added to a repository.
     */
    fun bindRegistry(registry: Registry<K, E>) {
        registryRef.set(registry)
    }

    /**
     * Lazily resolves the referenced entity from the bound [Registry].
     *
     * If bubble-up is configured and the reference ID has changed since the last successful wiring,
     * attempts to re-subscribe to the new referenced entity before returning the result. If the new
     * entity is not yet in the registry, the old subscription remains active until a future [resolve]
     * call succeeds.
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
        val reg = registryRef.get() ?: return Optional.empty()
        val currentId = idProvider()
        // Lazy re-wire: if the reference ID changed since last wiring, attempt to re-subscribe.
        // bubbleUpParent != null acts as proxy for "bubble-up was configured" to skip re-wire overhead
        // for delegates that do not use bubble-up.
        if (bubbleUpParent != null && currentId != lastWiredId.get()) {
            rewireIfResolvable(currentId, reg)
        }
        return reg.findById(currentId) as Optional<E>
    }

    /**
     * Subscribes to the referenced entity's `changes` flow to forward mutations to the parent.
     *
     * Stores [parentEntity] and [refName] for use in future re-wire attempts triggered by [resolve].
     * If the referenced entity resolves at call time, wires the bubble-up subscription immediately.
     * If it does not resolve yet, records the current ID so that a future [resolve] call will
     * attempt wiring when the entity becomes available.
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
        bubbleUpParent = parentEntity
        bubbleUpRefName = refName
        val referencedEntity =
            resolve().orElse(null) ?: run {
                // Entity not found yet — record the ID so resolve() will re-wire when entity appears
                lastWiredId.set(idProvider())
                return
            }
        // Bubble-up only makes sense for ReactiveEntity children (which can emit mutation events)
        if (referencedEntity !is ReactiveEntity<*, *> || referencedEntity.isClosed) return

        val newSub = createBubbleUpSubscription(referencedEntity, parentEntity, refName)
        val oldSub = bubbleUpSubscription.getAndSet(newSub)
        oldSub?.cancel()
        lastWiredId.set(idProvider())
    }

    /**
     * Cancels the active bubble-up subscription if one exists.
     *
     * Resets [lastWiredId] to null so that a future [resolve] call will attempt re-wiring if
     * [wireBubbleUp] is called again (e.g., after the entity is re-added to its repository).
     *
     * Called by [VolatileRepository] when the parent entity is removed with
     * [CascadeAction.DETACH], and by [ReactiveEntityBase] on entity close.
     */
    fun cancelBubbleUp() {
        bubbleUpSubscription.getAndSet(null)?.cancel()
        lastWiredId.set(null)
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
            CascadeAction.CASCADE -> doCascade()
            CascadeAction.RESTRICT -> doRestrict(owningEntity)
            CascadeAction.DETACH -> cancelBubbleUp()
            CascadeAction.NONE -> { /* intentional no-op */ }
        }
    }

    private fun doCascade() {
        val repo = registryRef.get()
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

    private fun doRestrict(owningEntity: Any) {
        val targetId = idProvider()
        // Find which entity class this registry manages by matching against globalRegistries
        val targetClass =
            RegistryBase.globalRegistries.entries
                .firstOrNull { (_, reg) -> reg === registryRef.get() }
                ?.key
                ?: return // Registry not bound or not registered — nothing to check

        @Suppress("UNCHECKED_CAST")
        for ((entityClass, otherRegistry) in RegistryBase.globalRegistriesSnapshot()) {
            val accessor = RegistryBase.refAccessorFor(entityClass) ?: continue
            for (entity in otherRegistry) {
                // Exclude the entity that is triggering the cascade
                if (entity === owningEntity) continue
                for (entry in accessor.entries) {
                    check(!(entry.referencedClass == targetClass && entry.idGetter(entity) == targetId)) {
                        "Cannot cascade-delete ${targetClass.simpleName}(id=$targetId): still referenced by other entities"
                    }
                }
            }
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
        for (entry in accessor.entries) {
            if (entry.cascadeAction != CascadeAction.CASCADE) continue
            val targetId = entry.idGetter(referencedEntity)
            val targetKey = RegistryBase.cascadeKey(entry.referencedClass, targetId)
            check(targetKey !in visited) {
                "Cascade cycle detected: entity '${entry.referencedClass.simpleName}(id=$targetId)' is already being cascaded on this thread"
            }
        }
    }

    /**
     * Attempts to re-wire the bubble-up subscription to the entity with [newId] in [reg].
     *
     * If the entity is found and is a non-closed [ReactiveEntity], atomically replaces the
     * current subscription with a new one and cancels the old. Updates [lastWiredId] to [newId]
     * on success. If the entity is not found, the existing subscription is preserved and
     * [lastWiredId] is not updated, so a future [resolve] call will retry.
     */
    @Suppress("UNCHECKED_CAST")
    private fun rewireIfResolvable(newId: K, reg: Registry<K, E>) {
        val newEntity = reg.findById(newId).orElse(null) ?: return
        val parent = bubbleUpParent ?: return
        val refName = bubbleUpRefName ?: return
        if (newEntity !is ReactiveEntity<*, *> || newEntity.isClosed) return
        if (parent.isClosed) return

        val newSub = createBubbleUpSubscription(newEntity, parent, refName)
        val oldSub = bubbleUpSubscription.getAndSet(newSub)
        oldSub?.cancel()
        lastWiredId.set(newId)
    }

    /**
     * Creates a subscription on [referencedEntity] that forwards non-aggregate [MutationEvent]s
     * to [parentEntity] as [net.transgressoft.lirp.event.StandardAggregateMutationEvent] via
     * [ReactiveEntityBase.emitBubbleUpEvent].
     *
     * The [AggregateMutationEvent] filter enforces single-level bubble-up: transitive events
     * (those already wrapped in an [AggregateMutationEvent]) are not re-forwarded upward.
     */
    @Suppress("UNCHECKED_CAST")
    private fun createBubbleUpSubscription(
        referencedEntity: ReactiveEntity<*, *>,
        parentEntity: ReactiveEntity<*, *>,
        refName: String
    ): LirpEventSubscription<*, *, *> {
        // K bound on ReactiveEntity<K, R> cannot be verified at this wildcard call site;
        // the cast is safe because subscribe() only reads from the entity's publisher,
        // which is internally consistent regardless of the type parameter.
        val rawChild = referencedEntity as ReactiveEntity<Comparable<Any>, *>
        return rawChild.subscribe { childEvent ->
            // Type parameters are erased at runtime; the self-referential bound R : ReactiveEntity<K, R>
            // cannot be verified at the wildcard call site. ReactiveEntityBase.emitBubbleUpEvent() is used
            // here because it is defined on the entity class itself (which has the correctly-typed R),
            // allowing StandardAggregateMutationEvent to be constructed with the right bound at that site.
            //
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
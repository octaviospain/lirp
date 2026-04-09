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

package net.transgressoft.lirp.entity

import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.LirpEvent
import net.transgressoft.lirp.event.LirpEventPublisher
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.MutationEvent.Type.MUTATE
import net.transgressoft.lirp.event.ReactiveMutationEvent
import net.transgressoft.lirp.event.StandardAggregateMutationEvent
import net.transgressoft.lirp.persistence.AggregateRefDelegate
import net.transgressoft.lirp.persistence.FxObservableCollectionProxy
import net.transgressoft.lirp.persistence.LirpDelegate
import net.transgressoft.lirp.persistence.LirpRefAccessor
import mu.KotlinLogging
import java.time.LocalDateTime
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstract base class that provides reactive functionality for entities, enabling them to notify subscribers
 * about property changes through a reactive flow-based pattern.
 *
 * This class implements the [ReactiveEntity] interface and manages subscriptions using Kotlin Flows.
 * When properties of the entity change, all subscribers are automatically notified with both the updated
 * entity state and the previous state.
 *
 * The event publisher is lazily initialized on first subscription, minimizing overhead for unobserved entities.
 *
 * Observable properties are declared with the [reactiveProperty] delegate factory. Assigning a new value
 * to a delegate-backed property emits a [ReactiveMutationEvent] automatically — no boilerplate setters needed:
 * ```
 * var name: String by reactiveProperty("default")
 * ```
 * For `@Transient` properties in `@Serializable` entities, use the getter/setter overload:
 * ```
 * @Transient override var name: String? by reactiveProperty({ _name }, { _name = it })
 * ```
 * The block-level [mutateAndPublish] overload remains available for multi-field atomic mutations.
 *
 * Lifecycle states:
 * - **Created**: No publisher allocated; zero overhead.
 * - **Active**: Publisher exists and has at least one subscriber emitting events.
 * - **Dormant**: All subscribers cancelled; publisher is shut down and nullified. Lazily reactivates on next [subscribe] call.
 * - **Closed**: Terminal state entered via [close]. All mutating operations throw [IllegalStateException].
 *
 * @param K The type of the entity's unique identifier, which must implement [Comparable]
 * @param R The concrete type of the reactive entity that extends this class
 *
 * @see ReactiveEntity
 * @see MutationEvent
 */
abstract class ReactiveEntityBase<K, R : ReactiveEntity<K, R>>(
    private val publisherFactory: (String) -> LirpEventPublisher<MutationEvent.Type, MutationEvent<K, R>> =
        { id -> FlowEventPublisher(id, closeOnEmpty = true) }
) : ReactiveEntity<K, R> where K : Comparable<K> {
    private val log = KotlinLogging.logger {}

    /**
     * Convenience constructor that creates a default FlowEventPublisher with the entity's class name.
     */
    protected constructor() : this({ id -> FlowEventPublisher(id, closeOnEmpty = true) })

    @Volatile
    private var closed = false

    @Volatile
    @PublishedApi
    internal var eventsDisabled = false

    override val isClosed: Boolean get() = closed

    /**
     * Cached KSP-generated [LirpRefAccessor] for this entity's class, discovered lazily on first [close].
     * Null if no accessor was found (entity has no [@Aggregate][net.transgressoft.lirp.persistence.Aggregate] properties).
     */
    @Volatile
    private var _refAccessor: LirpRefAccessor<*>? = null

    /** Guards double-checked locking for [_refAccessor] initialization. */
    @Volatile
    private var _refAccessorLoaded = false

    /**
     * The lazily initialized publisher. Only created when the first subscriber registers.
     * Uses AtomicReference for lock-free visibility and CAS-based initialization.
     */
    private val publisherRef = AtomicReference<LirpEventPublisher<MutationEvent.Type, MutationEvent<K, R>>?>(null)

    /**
     * Gets the publisher, creating it lazily if needed. Thread-safe using AtomicReference CAS loop.
     * Detects a closed (dormant) publisher and recreates it transparently.
     * If two threads race to initialize, the loser closes its duplicate and retries — only one survives.
     * Throws [IllegalStateException] if the entity is permanently closed.
     */
    private val publisher: LirpEventPublisher<MutationEvent.Type, MutationEvent<K, R>>
        get() {
            while (true) {
                val current = publisherRef.get()
                if (current != null && !current.isClosed)
                    return current
                check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }

                val newPublisher = publisherFactory(this::class.java.simpleName)
                newPublisher.activateEvents(MUTATE)
                if (publisherRef.compareAndSet(current, newPublisher)) {
                    return newPublisher
                }
                // CAS failed — another thread won the race; discard our duplicate and retry
                newPublisher.close()
            }
        }

    /**
     * Determines whether events should be emitted. Returns true only if the publisher has been initialized,
     * which happens when the first subscriber registers.
     *
     * For in-process publishers like FlowEventPublisher, this provides memory optimization by avoiding
     * event emission for entities without subscribers.
     *
     * For distributed publishers (e.g., Kafka), once initialized, this will always return true since
     * we cannot determine if remote consumers exist.
     */
    private val shouldEmit: Boolean
        get() = publisherRef.get()?.let { !it.isClosed } ?: false

    /**
     * The timestamp when this entity was last modified.
     * Automatically updated whenever a property is changed via [mutateAndPublish].
     */
    override var lastDateModified: LocalDateTime = LocalDateTime.now()
        protected set

    /**
     * A flow of entity change events that collectors can observe.
     * Accessing this property will trigger lazy initialization of the publisher.
     * Throws [IllegalStateException] if the entity is permanently closed.
     */
    override val changes: SharedFlow<MutationEvent<K, R>>
        get() {
            check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
            return publisher.changes
        }

    /**
     * Permanently closes this entity and releases its publisher resources.
     *
     * Before closing the publisher, cancels all bubble-up subscriptions via the KSP-generated
     * [LirpRefAccessor] for this entity's class. This always executes DETACH-style cleanup
     * regardless of the configured [CascadeAction] — CASCADE removal from external repositories
     * only runs from repository remove/clear operations.
     *
     * Idempotent: subsequent calls are safe no-ops.
     */
    @Suppress("UNCHECKED_CAST")
    override fun close() {
        if (closed)
            return
        closed = true
        (loadRefAccessor() as? LirpRefAccessor<R>)?.cancelAllBubbleUp(this as R)
        publisherRef.getAndSet(null)?.close()
    }

    /**
     * Discovers the KSP-generated [LirpRefAccessor] for this entity's concrete class via a
     * convention-based [Class.forName] lookup (`{EntityClassName}_LirpRefAccessor`). Uses
     * double-checked locking so the lookup runs at most once per entity instance and the result
     * is visible to all threads.
     *
     * Returns `null` if no accessor was found (entity has no [@Aggregate][net.transgressoft.lirp.persistence.Aggregate]
     * properties or KSP was not applied). Entities GC'd without [close] never incur this cost —
     * consistent with the lazy publisher pattern.
     */
    private fun loadRefAccessor(): LirpRefAccessor<*>? {
        if (_refAccessorLoaded) return _refAccessor
        synchronized(this) {
            if (_refAccessorLoaded) return _refAccessor
            _refAccessor =
                try {
                    val accessorClass = Class.forName("${this.javaClass.name}_LirpRefAccessor")
                    accessorClass.getDeclaredConstructor().newInstance() as? LirpRefAccessor<*>
                } catch (_: ClassNotFoundException) {
                    null
                }
            _refAccessorLoaded = true
            return _refAccessor
        }
    }

    override fun emitAsync(event: MutationEvent<K, R>) {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        publisher.emitAsync(event)
    }

    /**
     * Creates and emits a [StandardAggregateMutationEvent] on this entity's publisher.
     *
     * Called by [AggregateRefDelegate] when a referenced child entity mutates and bubble-up
     * propagation is enabled. This method is defined on [ReactiveEntityBase] because it has
     * direct access to the correctly-typed `R` parameter, avoiding the type erasure problem
     * that arises when emitting from external (wildcard-typed) call sites.
     *
     * Accepts any [LirpEvent] as [childEvent] — both [MutationEvent] for property-level bubble-up
     * and [CollectionChangeEvent] for collection-level diffs.
     *
     * @param refName the property name of the [@Aggregate][net.transgressoft.lirp.persistence.Aggregate]
     *   annotated property that triggered the bubble-up
     * @param childEvent the original [LirpEvent] from the referenced child entity or collection
     */
    @Suppress("UNCHECKED_CAST")
    internal fun emitBubbleUpEvent(refName: String, childEvent: LirpEvent<*>) {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        val aggregateEvent =
            StandardAggregateMutationEvent(
                newEntity = this as R,
                oldEntity = this as R,
                refName = refName,
                childEvent = childEvent
            )
        publisher.emitAsync(aggregateEvent)
    }

    /**
     * Emits a [CollectionChangeEvent] wrapped in a [StandardAggregateMutationEvent] on this entity's publisher.
     *
     * Called by mutable aggregate collection delegates when items are added, removed, replaced, or cleared.
     * Unlike [emitBubbleUpEvent], this method checks [shouldEmit] to avoid unnecessary work when no
     * subscribers are registered.
     *
     * @param refName the property name of the mutable aggregate collection that changed
     * @param childEvent the [CollectionChangeEvent] describing the diff
     */
    @Suppress("UNCHECKED_CAST")
    internal fun emitCollectionChangeEvent(refName: String, childEvent: CollectionChangeEvent<*>) {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        if (eventsDisabled) return
        lastDateModified = LocalDateTime.now()
        if (!shouldEmit) return
        val aggregateEvent =
            StandardAggregateMutationEvent(
                newEntity = this as R,
                oldEntity = this as R,
                refName = refName,
                childEvent = childEvent
            )
        publisher.emitAsync(aggregateEvent)
    }

    /**
     * Emits a [ReactiveMutationEvent] triggered by a JavaFX scalar property delegate mutation.
     *
     * Called by fx scalar property delegates via a callback injected by RegistryBase. The supplied
     * [mutationBlock] contains the `super.set(newValue)` call on the underlying Simple*Property.
     * This method wraps it in a clone-before-mutation sequence: it captures the entity state before
     * [mutationBlock] executes, then emits a [ReactiveMutationEvent] after if subscribers are present.
     *
     * Respects the [eventsDisabled] flag — when events are suppressed (e.g., during [clone]),
     * the mutation still executes but no event is published.
     *
     * @param mutationBlock the lambda that performs the actual property mutation (super.set call)
     */
    @Suppress("UNCHECKED_CAST")
    internal fun emitFxScalarMutation(mutationBlock: () -> Unit) {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        if (eventsDisabled) {
            mutationBlock()
            return
        }
        val entityBeforeChange = clone()
        mutationBlock()
        lastDateModified = LocalDateTime.now()
        if (shouldEmit) {
            log.trace { "Firing fx scalar mutation event from $entityBeforeChange to $this" }
            publisher.emitAsync(ReactiveMutationEvent(this as R, entityBeforeChange as R))
        }
    }

    override fun subscribe(action: suspend (MutationEvent<K, R>) -> Unit): LirpEventSubscription<in LirpEntity, MutationEvent.Type, MutationEvent<K, R>> {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        return publisher.subscribe(action)
    }

    override fun subscribe(subscriber: Flow.Subscriber<in MutationEvent<K, R>>?) {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        publisher.subscribe(subscriber)
    }

    override fun subscribe(vararg eventTypes: MutationEvent.Type, action: Consumer<in MutationEvent<K, R>>):
        LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        require(MUTATE in eventTypes) {
            throw IllegalArgumentException("Only UPDATE event is supported for reactive entities")
        }
        return subscribe(action::accept)
    }

    /**
     * Suppresses event emission from [mutateAndPublish] and reactive property delegates.
     * Mutations still execute, but no [ReactiveMutationEvent] is published.
     *
     * Pair with [enableEvents] to restore normal emission. Designed for use in [clone]
     * implementations where property setters would otherwise trigger infinite recursion
     * through the clone-compare change detection.
     *
     * @see enableEvents
     * @see withEventsDisabled
     */
    protected fun disableEvents() {
        eventsDisabled = true
    }

    /**
     * Restores event emission after a prior [disableEvents] call.
     *
     * @see disableEvents
     * @see withEventsDisabled
     */
    protected fun enableEvents() {
        eventsDisabled = false
    }

    /**
     * Executes [action] with event emission suppressed, restoring the previous state afterward.
     *
     * Equivalent to wrapping the action between [disableEvents] and [enableEvents], but
     * guarantees restoration even if the action throws.
     *
     * ```
     * override fun clone(): MyEntity = MyEntity(id).apply {
     *     withEventsDisabled {
     *         name = this@MyEntity.name
     *         price = this@MyEntity.price
     *     }
     * }
     * ```
     *
     * @param T The return type of the action
     * @param action The block to execute with events disabled
     * @return The result of the action
     * @see disableEvents
     * @see enableEvents
     */
    protected inline fun <T> withEventsDisabled(action: () -> T): T {
        val wasDisabled = eventsDisabled
        eventsDisabled = true
        try {
            return action()
        } finally {
            eventsDisabled = wasDisabled
        }
    }

    /**
     * Creates a reactive property delegate that emits a [ReactiveMutationEvent] on value change.
     *
     * Usage: `var name: String by reactiveProperty(initialName)`
     *
     * @param T The type of the property value
     * @param initialValue The initial value for the property
     * @return A [ReadWriteProperty] delegate that tracks mutations and emits events
     */
    protected fun <T> reactiveProperty(initialValue: T): ReadWriteProperty<ReactiveEntityBase<K, R>, T> =
        ReactivePropertyDelegate(initialValue)

    /**
     * Creates a reactive property delegate backed by external getter/setter lambdas.
     *
     * Designed for `@Transient` properties in `@Serializable` entities where the actual
     * value is stored in a constructor parameter annotated with `@SerialName`:
     * ```
     * @Transient
     * override var name: String? by reactiveProperty({ _name }, { _name = it })
     * ```
     *
     * @param T The type of the property value
     * @param getter Lambda that reads the current value
     * @param setter Lambda that writes the new value
     * @return A [ReadWriteProperty] delegate that tracks mutations and emits events
     */
    protected fun <T> reactiveProperty(getter: () -> T, setter: (T) -> Unit): ReadWriteProperty<ReactiveEntityBase<K, R>, T> =
        ReactivePropertyDelegateWithAccessors(getter, setter)

    @Suppress("UNCHECKED_CAST")
    protected fun <T> mutateAndPublish(mutationAction: () -> T): T {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        if (eventsDisabled)
            return mutationAction()

        val entityBeforeChange = clone()
        val result = mutationAction()
        if (entityBeforeChange == this) {
            log.warn {
                "Attempt to publish update event from a mutation when the entity state did not change. " +
                    "Consider implementing equals() and hashCode() that reflects all mutable properties affected by the mutationAction."
            }
        } else {
            lastDateModified = LocalDateTime.now()
            if (shouldEmit) {
                log.trace { "Firing entity update event from $entityBeforeChange to $this" }
                publisher.emitAsync(ReactiveMutationEvent(this as R, entityBeforeChange as R))
            }
        }
        return result
    }

    /**
     * Public bridge exposing [withEventsDisabled] for framework-level operations like
     * [LirpEntitySerializer][net.transgressoft.lirp.persistence.json.LirpEntitySerializer]
     * deserialization and user-written clone implementations.
     *
     * Provides the same event-suppression guarantee as [withEventsDisabled] while keeping that
     * method protected for subclass use.
     *
     * @param action the block to execute with events disabled
     * @return the result of [action]
     */
    internal fun <T> withEventsDisabledForClone(action: () -> T): T = withEventsDisabled(action)

    @Volatile
    private var _delegateRegistry: Map<String, LirpDelegate>? = null

    /**
     * Lazy registry mapping property names to their [LirpDelegate] instances.
     * Built on first access by scanning this entity's member properties via kotlin-reflect
     * and filtering for LIRP delegate types.
     */
    internal val delegateRegistry: Map<String, LirpDelegate>
        get() {
            _delegateRegistry?.let { return it }
            synchronized(this) {
                _delegateRegistry?.let { return it }
                val map = mutableMapOf<String, LirpDelegate>()
                @Suppress("UNCHECKED_CAST")
                for (prop in this::class.memberProperties) {
                    val typedProp = prop as? KProperty1<ReactiveEntityBase<*, *>, *> ?: continue
                    // isAccessible is required for private entity classes (e.g. in test files).
                    // Some properties (e.g. @Transient-backed properties in data classes) may throw
                    // KotlinReflectionInternalError (an Error, not Exception) — skip them safely
                    // since they cannot be delegate-backed.
                    try {
                        typedProp.isAccessible = true
                    } catch (e: Error) {
                        if (e is VirtualMachineError || e is LinkageError) throw e
                        continue
                    } catch (_: Exception) {
                        continue
                    }
                    val delegate =
                        try {
                            typedProp.getDelegate(this)
                        } catch (e: Error) {
                            if (e is VirtualMachineError || e is LinkageError) throw e
                            continue
                        } catch (_: Exception) {
                            continue
                        }
                    if (delegate is LirpDelegate) {
                        map[prop.name] = delegate
                    } else if (delegate is FxObservableCollectionProxy) {
                        val inner = delegate.innerMutableProxy
                        require(inner is LirpDelegate) {
                            "Fx proxy delegate '${prop.name}' must expose a LirpDelegate inner proxy, got: ${inner::class.qualifiedName}"
                        }
                        map[prop.name] = inner
                    }
                }
                _delegateRegistry = map
                return map
            }
        }

    private inner class ReactivePropertyDelegate<T>(private var storedValue: T) :
        ReadWriteProperty<Any?, T>,
        LirpDelegate {

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = storedValue

        @Suppress("UNCHECKED_CAST")
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            check(!isClosed) { "Entity '${this@ReactiveEntityBase::class.java.simpleName}' is closed" }

            if (value != storedValue) {
                if (eventsDisabled) {
                    storedValue = value
                    return
                }

                val entityBeforeChange = clone()
                storedValue = value
                lastDateModified = LocalDateTime.now()
                if (shouldEmit) {
                    log.trace { "Firing entity update event from $entityBeforeChange to ${this@ReactiveEntityBase}" }
                    publisher.emitAsync(ReactiveMutationEvent(this@ReactiveEntityBase as R, entityBeforeChange as R))
                }
            }
        }
    }

    private inner class ReactivePropertyDelegateWithAccessors<T>(
        private val getter: () -> T,
        private val setter: (T) -> Unit
    ) : ReadWriteProperty<Any?, T>,
        LirpDelegate {

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = getter()

        @Suppress("UNCHECKED_CAST")
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            check(!isClosed) { "Entity '${this@ReactiveEntityBase::class.java.simpleName}' is closed" }

            val oldValue = getter()
            if (value != oldValue) {
                if (eventsDisabled) {
                    setter(value)
                    return
                }
                val entityBeforeChange = clone()
                setter(value)
                lastDateModified = LocalDateTime.now()
                if (shouldEmit) {
                    log.trace { "Firing entity update event from $entityBeforeChange to ${this@ReactiveEntityBase}" }
                    publisher.emitAsync(ReactiveMutationEvent(this@ReactiveEntityBase as R, entityBeforeChange as R))
                }
            }
        }
    }
}
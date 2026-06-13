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

import net.transgressoft.lirp.event.BatchChanged
import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.event.FieldChange
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.LirpEvent
import net.transgressoft.lirp.event.LirpEventPublisher
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.MutationEvent.Type.BATCH_CHANGED
import net.transgressoft.lirp.event.MutationEvent.Type.MUTATE
import net.transgressoft.lirp.event.MutationEvent.Type.PROPERTY_CHANGED
import net.transgressoft.lirp.event.PropertyChanged
import net.transgressoft.lirp.event.StandardAggregateMutationEvent
import net.transgressoft.lirp.persistence.AggregateRefDelegate
import net.transgressoft.lirp.persistence.FxObservableCollection
import net.transgressoft.lirp.persistence.IndexEntry
import net.transgressoft.lirp.persistence.KspAccessorLoader
import net.transgressoft.lirp.persistence.LirpDelegate
import net.transgressoft.lirp.persistence.LirpIndexAccessor
import net.transgressoft.lirp.persistence.LirpRefAccessor
import net.transgressoft.lirp.persistence.ReactivePropertyDelegate
import net.transgressoft.lirp.persistence.ReactivePropertyDelegateWithAccessors
import io.github.oshai.kotlinlogging.KotlinLogging
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
 * When a reactive property changes, subscribers are notified with a typed [PropertyChanged] event
 * carrying the old and new values — no entity clone is performed on the setter hot path.
 *
 * The event publisher is lazily initialized on first subscription, minimizing overhead for unobserved entities.
 *
 * Observable properties are declared with the [reactiveProperty] delegate factory. Assigning a new value
 * to a delegate-backed property emits a [PropertyChanged] event automatically — no boilerplate setters needed:
 * ```
 * var name: String by reactiveProperty("default")
 * ```
 * For `@Transient` properties in `@Serializable` entities, use the getter/setter overload:
 * ```
 * @Transient override var name: String? by reactiveProperty({ _name }, { _name = it })
 * ```
 * The block-level [mutateAndPublish] overload remains available for multi-field atomic mutations;
 * it emits a single [BatchChanged] event with per-field [FieldChange] entries.
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
     * Cached KSP-generated [LirpIndexAccessor] entries for this entity's class, discovered lazily
     * on first [emitPropertyChanged] call. Empty list when no accessor was found.
     */
    @Volatile
    private var _indexEntries: List<IndexEntry<R>>? = null

    /** Guards double-checked locking for [_indexEntries] initialization. */
    @Volatile
    private var _indexEntriesLoaded = false

    /**
     * Per-call accumulator for [mutateAndPublish]. Thread-local and re-entrant: each thread holds its
     * own accumulator while a block is in progress, and nested blocks save and restore the enclosing
     * accumulator so an inner block cannot drop the outer one. Captures per-field changes so a single
     * [BatchChanged] can be emitted when each block exits.
     */
    private val _batchAccumulator = ThreadLocal<MutableList<FieldChange<R, *>>?>()

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
                newPublisher.activateEvents(MUTATE, PROPERTY_CHANGED, BATCH_CHANGED)
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
     *
     * Automatically updated whenever a property is changed via [mutateAndPublish] or
     * a reactive property delegate.
     * Public setter enables KSP-generated `SqlTableDef.fromRow()` to restore the persisted timestamp
     * when loading entities from a database.
     */
    override var lastDateModified: LocalDateTime = LocalDateTime.now()

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

    /**
     * Lazily loads the KSP-generated [LirpIndexAccessor] entries for this entity's concrete class.
     * Returns an empty list when no accessor was found (entity has no [@Indexed][net.transgressoft.lirp.persistence.Indexed]
     * properties or KSP was not applied).
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadIndexEntries(): List<IndexEntry<R>> {
        if (_indexEntriesLoaded) return _indexEntries ?: emptyList()
        synchronized(this) {
            if (_indexEntriesLoaded) return _indexEntries ?: emptyList()
            _indexEntries =
                KspAccessorLoader.load<LirpIndexAccessor<R>>(this.javaClass, KspAccessorLoader.INDEX_ACCESSOR_SUFFIX)
                    ?.entries
                    ?: emptyList()
            _indexEntriesLoaded = true
            return _indexEntries ?: emptyList()
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
                entity = this as R,
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
                entity = this as R,
                refName = refName,
                childEvent = childEvent
            )
        publisher.emitAsync(aggregateEvent)
    }

    /**
     * Emits a [PropertyChanged] event for a single reactive property assignment.
     *
     * Called by reactive property delegates with the old and new values they already hold —
     * no entity clone is performed. If a [mutateAndPublish] block is in progress, the change is
     * accumulated for batching rather than emitted immediately.
     *
     * The [mutationBlock] is always executed first; the event is built from the values captured
     * before and after, then emitted. For `@Indexed` properties, the old and new index keys are
     * captured as immutable scalars to guard against drift under deferred subscriber consumption.
     *
     * @param property the property being mutated
     * @param oldValue the value immediately before the assignment
     * @param newValue the value immediately after the assignment
     * @param mutationBlock the lambda that performs the actual backing-field write
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <V> emitPropertyChanged(property: KProperty<*>, oldValue: V, newValue: V, mutationBlock: () -> Unit) {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        if (eventsDisabled) {
            mutationBlock()
            return
        }
        mutationBlock()
        lastDateModified = LocalDateTime.now()

        // If a batch accumulator is active, record this change and defer emission to mutateAndPublish.
        val accumulator = _batchAccumulator.get()
        if (accumulator != null) {
            val kprop1 = property as? KProperty1<R, V>
            if (kprop1 != null) {
                accumulator.add(FieldChange(kprop1, oldValue, newValue))
            }
            return
        }

        if (!shouldEmit) return

        val kprop1 = property as? KProperty1<R, V> ?: return
        val indexEntries = loadIndexEntries()
        val matchingIndex = indexEntries.firstOrNull { it.propertyName == property.name }
        val event =
            PropertyChanged<K, R, V>(
                entity = this as R,
                property = kprop1,
                oldValue = oldValue,
                newValue = newValue,
                versionAtMutation = null,
                oldIndexKey = if (matchingIndex != null) oldValue else null,
                newIndexKey = if (matchingIndex != null) newValue else null
            )
        log.trace { "Firing property changed event on ${this::class.java.simpleName}: ${property.name} $oldValue -> $newValue" }
        publisher.emitAsync(event)
    }

    /**
     * Entry point for FxScalar delegates: emits a [PropertyChanged] event given the property name
     * and captured old/new values. Used by [net.transgressoft.lirp.persistence.RegistryBase] when
     * wiring scalar mutation callbacks for FxScalar property delegates.
     *
     * @param propertyName the name of the property that changed
     * @param oldValue the property value before the FX setter ran
     * @param newValue the property value after the FX setter ran
     * @param mutationBlock the lambda wrapping the `super.set()` call on the FX property
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <V> emitFxScalarPropertyChanged(propertyName: String, oldValue: V, newValue: V, mutationBlock: () -> Unit) {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        if (eventsDisabled) {
            mutationBlock()
            return
        }
        mutationBlock()
        lastDateModified = LocalDateTime.now()

        // If a batch accumulator is active, defer emission.
        val accumulator = _batchAccumulator.get()
        if (accumulator != null) {
            // For FxScalar properties in a batch, we can only store a change if we can find the KProperty1.
            // Attempt a lazy lookup via the delegate registry.
            val kprop1 =
                this::class.memberProperties
                    .filterIsInstance<KProperty1<R, V>>()
                    .firstOrNull { it.name == propertyName }
            if (kprop1 != null) {
                accumulator.add(FieldChange(kprop1, oldValue, newValue))
            }
            return
        }

        if (!shouldEmit) return

        val kprop1 =
            this::class.memberProperties
                .filterIsInstance<KProperty1<R, V>>()
                .firstOrNull { it.name == propertyName } ?: return

        val indexEntries = loadIndexEntries()
        val matchingIndex = indexEntries.firstOrNull { it.propertyName == propertyName }
        val event =
            PropertyChanged<K, R, V>(
                entity = this as R,
                property = kprop1,
                oldValue = oldValue,
                newValue = newValue,
                versionAtMutation = null,
                oldIndexKey = if (matchingIndex != null) oldValue else null,
                newIndexKey = if (matchingIndex != null) newValue else null
            )
        log.trace { "Firing fx scalar property changed event on ${this::class.java.simpleName}: $propertyName $oldValue -> $newValue" }
        publisher.emitAsync(event)
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
     * Mutations still execute, but no mutation event is published.
     *
     * Pair with [enableEvents] to restore normal emission. Designed for use in [clone]
     * implementations where property setters would otherwise trigger event emission.
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

    override fun <T> withEventsDisabled(action: () -> T): T {
        val wasDisabled = eventsDisabled
        eventsDisabled = true
        try {
            return action()
        } finally {
            eventsDisabled = wasDisabled
        }
    }

    /**
     * Creates a reactive property delegate that emits a [PropertyChanged] event on value change.
     *
     * Usage: `var name: String by reactiveProperty(initialName)`
     *
     * @param T The type of the property value
     * @param initialValue The initial value for the property
     * @return A [ReadWriteProperty] delegate that tracks mutations and emits events
     */
    protected fun <T> reactiveProperty(initialValue: T): ReadWriteProperty<ReactiveEntityBase<K, R>, T> =
        ReactivePropertyDelegate(this, initialValue)

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
        ReactivePropertyDelegateWithAccessors(this, getter, setter)

    /**
     * Atomically mutates one or more reactive properties and emits a single [BatchChanged] event
     * carrying all per-field changes. If no field net-changed during the block, the event is
     * suppressed.
     *
     * All property mutations inside the block are accumulated via the delegate's [emitPropertyChanged]
     * path rather than emitted individually. This avoids spurious intermediate events when multiple
     * fields change together and lets consumers receive a coherent snapshot of the entire mutation.
     *
     * @param mutationAction the block that performs the mutations; its return value is forwarded
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <T> mutateAndPublish(mutationAction: () -> T): T {
        check(!isClosed) { "Entity '${this::class.java.simpleName}' is closed" }
        if (eventsDisabled)
            return mutationAction()

        // Install a fresh accumulator so nested emitPropertyChanged calls record into it, saving the
        // enclosing one so a nested mutateAndPublish restores (not drops) the outer block's accumulator.
        val previousAccumulator = _batchAccumulator.get()
        val accumulator = mutableListOf<FieldChange<R, *>>()
        _batchAccumulator.set(accumulator)

        val result: T
        try {
            result = mutationAction()
        } finally {
            if (previousAccumulator != null) _batchAccumulator.set(previousAccumulator) else _batchAccumulator.remove()
        }

        if (accumulator.isEmpty()) {
            log.debug {
                "No-change mutation on ${this::class.java.simpleName}(id=$id) — batch event skipped"
            }
            return result
        }

        lastDateModified = LocalDateTime.now()
        if (shouldEmit) {
            // Capture old/new index keys from the first indexed field touched, if any.
            val indexEntries = loadIndexEntries()
            var oldIndexKey: Any? = null
            var newIndexKey: Any? = null
            if (indexEntries.isNotEmpty()) {
                val indexedChange =
                    accumulator.firstOrNull { change ->
                        indexEntries.any { it.propertyName == change.property.name }
                    }
                if (indexedChange != null) {
                    oldIndexKey = indexedChange.oldValue
                    newIndexKey = indexedChange.newValue
                }
            }
            val event =
                BatchChanged<K, R>(
                    entity = this as R,
                    changes = accumulator.toList(),
                    versionAtMutation = null,
                    oldIndexKey = oldIndexKey,
                    newIndexKey = newIndexKey
                )
            log.trace { "Firing batch changed event on ${this::class.java.simpleName}(id=$id) with ${accumulator.size} field(s)" }
            publisher.emitAsync(event)
        }
        return result
    }

    /**
     * Alias for [withEventsDisabled] retained for binary compatibility with code compiled
     * against earlier LIRP versions where [withEventsDisabled] was protected.
     *
     * @param action the block to execute with events disabled
     * @return the result of [action]
     */
    fun <T> withEventsDisabledForClone(action: () -> T): T = withEventsDisabled(action)

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
                    } else if (delegate is FxObservableCollection<*, *>) {
                        val inner = delegate.innerMutableProxy
                        require(inner is LirpDelegate) {
                            "Fx collection delegate '${prop.name}' must expose a LirpDelegate inner proxy, got: ${inner::class.qualifiedName}"
                        }
                        map[prop.name] = inner
                    }
                }
                _delegateRegistry = map
                return map
            }
        }
}
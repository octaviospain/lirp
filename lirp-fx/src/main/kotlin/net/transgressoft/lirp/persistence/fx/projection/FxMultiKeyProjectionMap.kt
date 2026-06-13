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

package net.transgressoft.lirp.persistence.fx.projection

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.FxObservableCollection
import net.transgressoft.lirp.persistence.fx.FxAggregateList
import net.transgressoft.lirp.persistence.fx.FxAggregateSet
import net.transgressoft.lirp.persistence.projection.MultiKeyProjectionMap
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.FXCollections
import javafx.collections.MapChangeListener
import javafx.collections.ObservableMap
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A read-only [ObservableMap] that derives a multi-key grouped view from an existing
 * [FxObservableCollection] source (either an [FxAggregateList] or [FxAggregateSet]).
 *
 * Unlike [FxProjectionMap] (one entity per bucket), this map places each entity under every bucket
 * key that [keyExtractor] returns for it. A `MutableMultiKeyAudioItem` with genres `{Rock, Jazz}`
 * appears in both the `"Rock"` and `"Jazz"` buckets.
 *
 * Delegates all bucketing logic to a core [MultiKeyProjectionMap], wiring its `onBucketsChanged`
 * hook to a pending-flush coalescer that batches all bucket changes from one source event into
 * a single [Platform.runLater] call (dispatch mode) or one [ReactiveScope.flowScope] channel
 * action (non-dispatch mode).
 *
 * Each entity added to a bucket is subscribed to its own mutation events. When a key-relevant
 * property changes (the key set returned by [keyExtractor] differs from the previous value), the
 * core's `reconcile(entity)` method is called to perform add-before-remove re-bucketing in one
 * atomic delta. This guarantees the entity is never transiently absent from all buckets mid-move.
 * The mutation subscription is cancelled when the entity is removed from all buckets, and all
 * remaining subscriptions are cancelled on [close].
 *
 * The projection initializes lazily on the first [getValue] or [addListener] call.
 *
 * Mutation methods ([put], [remove], [putAll], [clear]) throw [UnsupportedOperationException];
 * all mutations flow through the source collection.
 *
 * **Thread safety:** Iteration of [keys], [values], [entries], plus [size], [containsKey],
 * and [get] never throws [ConcurrentModificationException], because the underlying
 * [ConcurrentSkipListMap] iterators are weakly-consistent.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type, must extend [ReactiveEntity]
 * @param sourceRef deferred reference to the source [FxObservableCollection] (resolved on first access)
 * @param keyExtractor function that extracts the set of projection keys from an entity;
 *   each returned key names one bucket the entity belongs to
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class FxMultiKeyProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E>(
    private val sourceRef: () -> FxObservableCollection<K, E>,
    private val keyExtractor: (E) -> Collection<PK>,
    val dispatchToFxThread: Boolean = true
) : ObservableMap<PK, List<E>>, AutoCloseable where E : IdentifiableEntity<K>, E : ReactiveEntity<K, E> {

    private val innerObservableMap: ObservableMap<PK, List<E>> =
        FXCollections.observableMap(ConcurrentSkipListMap<PK, List<E>>())

    @Volatile
    private var initialized = false

    private val initLock = Any()

    private val mutationChannel: Channel<() -> Unit>? =
        if (!dispatchToFxThread) Channel(Channel.UNLIMITED) else null

    private val initBarrier: CompletableDeferred<Unit>? =
        if (!dispatchToFxThread) CompletableDeferred() else null

    // Pending-flush coalescer — collects changed keys on the source-listener thread and mirrors
    // them into innerObservableMap in a single flush on the target thread.
    private val pendingKeys = Collections.synchronizedSet(LinkedHashSet<PK>())
    private val flushScheduled = AtomicBoolean(false)

    // Per-entity mutation subscriptions. Indexed by entity id. Each subscription calls
    // core.reconcile(entity) when a key-relevant property change is detected. Cleared on close().
    internal val entitySubscriptions = ConcurrentHashMap<K, LirpEventSubscription<*, *, *>>()

    // Reverse index: entity id → set of projection keys (PK) whose bucket currently holds it.
    // Updated incrementally from changedKeys in scheduleFlush, so unsubscription decisions are
    // based on locally-known bucket membership rather than a full scan of all core buckets.
    // Using a full-scan risks prematurely unsubscribing entities that are still in a bucket
    // whose snapshot returned null due to a concurrent mutation in an unrelated bucket.
    private val entityBuckets = ConcurrentHashMap<K, MutableSet<PK>>()

    // The source reference delegates through the FxAggregateList/FxAggregateSet wrapper to the
    // underlying MutableAggregateList/MutableAggregateSet. MultiKeyProjectionMap.subscribeToSource
    // checks for MutableAggregateList/MutableAggregateSet by type, not for AggregateCollectionRef in
    // general, so the inner proxy must be supplied rather than the FX wrapper for the projection
    // callback to be installed on the backing delegate.
    @Suppress("UNCHECKED_CAST")
    private val core: MultiKeyProjectionMap<K, PK, E> =
        MultiKeyProjectionMap(
            {
                when (val source = sourceRef()) {
                    is FxAggregateList<*, *> -> source.innerProxy as AggregateCollectionRef<K, E>
                    is FxAggregateSet<*, *> -> source.innerProxy as AggregateCollectionRef<K, E>
                    else -> source as AggregateCollectionRef<K, E>
                }
            },
            keyExtractor
        )

    init {
        mutationChannel?.let { channel ->
            ReactiveScope.flowScope.launch {
                initBarrier?.await()
                for (action in channel) {
                    action()
                }
            }
        }
    }

    private fun initialize() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            // Wire the coalescer so post-init bucket changes flush into innerObservableMap.
            core.addOnBucketsChangedListener(::scheduleFlush)

            // Trigger core initialization by reading size; this seeds the core from the source,
            // subscribes to source collection changes, and populates the reverse index.
            core.size

            // Seed innerObservableMap from the core's freshly built state, seed the reverse
            // index, and subscribe each initially-bucketed entity to its mutation events.
            for (key in core.keys) {
                val bucket = core.bucketSnapshot(key) ?: continue
                innerObservableMap[key] = freezeBucket(bucket)
                for (entity in bucket) {
                    entityBuckets.computeIfAbsent(entity.id) { Collections.newSetFromMap(ConcurrentHashMap()) }.add(key)
                    if (!entitySubscriptions.containsKey(entity.id)) {
                        subscribeEntity(entity)
                    }
                }
            }

            initBarrier?.complete(Unit)
            initialized = true
        }
    }

    /**
     * Registers a per-entity mutation subscription. When the entity's key set changes (as
     * detected by comparing [keyExtractor] output before and after the mutation),
     * [MultiKeyProjectionMap.reconcile] is called so the core recomputes bucket membership
     * in one atomic add-before-remove delta that fires a single [onBucketsChanged] signal.
     */
    private fun subscribeEntity(entity: E) {
        val subscription =
            entity.subscribe { event ->
                // Delegate re-bucketing to reconcile, which computes the key delta from its own
                // reverse-index (the tracked pre-mutation membership) and the current entity state.
                core.reconcile(event.entity)
            }
        entitySubscriptions[entity.id] = subscription
    }

    private fun unsubscribeEntity(id: K) {
        entitySubscriptions.remove(id)?.cancel()
    }

    /**
     * Accumulates [changedKeys] into the pending set and schedules a single flush if none is already
     * pending. After accumulating keys, checks whether any entities have entered or left all buckets
     * so that subscriptions can be established or cancelled. The flush executes on the FX Application
     * Thread ([Platform.runLater]) or on [ReactiveScope.flowScope] ([mutationChannel]), depending on
     * [dispatchToFxThread].
     */
    fun scheduleFlush(changedKeys: Set<PK>) {
        // Accumulate keys and gate the flush atomically: if the drain-and-reset in flush() is in
        // progress on another thread, the addAll must be paired with the compareAndSet under the
        // same lock, otherwise keys staged after flush() drains but before it resets the gate are
        // stranded — compareAndSet(false, true) fails while flushScheduled is still true.
        val shouldSchedule: Boolean
        synchronized(pendingKeys) {
            pendingKeys.addAll(changedKeys)
            shouldSchedule = flushScheduled.compareAndSet(false, true)
        }
        reconcileSubscriptions(changedKeys)
        if (shouldSchedule) {
            if (dispatchToFxThread) Platform.runLater(::flush)
            else mutationChannel!!.trySend(::flush)
        }
    }

    // Update the reverse index and reconcile entity subscriptions from changedKeys only.
    // A full scan of all core buckets is avoided because under concurrent mutation a key
    // present in core.keys may return null from bucketSnapshot by the time it is called,
    // making the diff falsely conclude that a still-bucketed entity is absent — triggering
    // a premature unsubscribeEntity for that entity.
    private fun reconcileSubscriptions(changedKeys: Set<PK>) {
        for (key in changedKeys) {
            val bucket = core.bucketSnapshot(key)
            if (bucket == null) onBucketEmptied(key) else onBucketUpdated(key, bucket)
        }
        val toUnsubscribe = entitySubscriptions.keys - entityBuckets.keys
        for (id in toUnsubscribe) unsubscribeEntity(id)
    }

    private fun onBucketEmptied(key: PK) {
        val iter = entityBuckets.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            entry.value.remove(key)
            if (entry.value.isEmpty()) iter.remove()
        }
    }

    private fun onBucketUpdated(key: PK, bucket: List<E>) {
        for (entity in bucket) {
            entityBuckets.computeIfAbsent(entity.id) { Collections.newSetFromMap(ConcurrentHashMap()) }.add(key)
            if (!entitySubscriptions.containsKey(entity.id)) subscribeEntity(entity)
        }
    }

    private fun flush() {
        val keys: Set<PK>
        // Drain the pending set and reset the gate atomically with scheduleFlush's staging so a
        // concurrent producer either lands its keys in this drain or successfully reschedules.
        synchronized(pendingKeys) {
            keys = LinkedHashSet(pendingKeys)
            pendingKeys.clear()
            flushScheduled.set(false)
        }
        for (key in keys) {
            val bucket = core.bucketSnapshot(key)
            if (bucket == null) innerObservableMap.remove(key)
            else innerObservableMap[key] = freezeBucket(bucket)
        }
    }

    private fun freezeBucket(elements: List<E>): List<E> = Collections.unmodifiableList(ArrayList(elements))

    /**
     * Cancels all per-entity mutation subscriptions and releases resources. Safe to call before
     * the first access (no-op when not yet initialized). After closing, in-place entity mutations
     * no longer trigger re-bucketing.
     */
    override fun close() {
        synchronized(initLock) {
            for (subscription in entitySubscriptions.values) {
                subscription.cancel()
            }
            entitySubscriptions.clear()
            entityBuckets.clear()
        }
    }

    // ObservableMap<PK, List<E>> — read operations delegate to innerObservableMap after initialization

    override val size: Int get() {
        initialize()
        return innerObservableMap.size
    }

    // Safe: ObservableMap declares MutableSet<MutableEntry> but the returned set is unmodifiable.
    // Callers cannot mutate through this view; the cast satisfies the interface contract.
    @Suppress("UNCHECKED_CAST")
    override val entries: MutableSet<MutableMap.MutableEntry<PK, List<E>>> get() {
        initialize()
        val snapshot = innerObservableMap.entries.map { java.util.AbstractMap.SimpleImmutableEntry(it.key, it.value) }.toSet()
        return Collections.unmodifiableSet(snapshot) as MutableSet<MutableMap.MutableEntry<PK, List<E>>>
    }

    // Safe: Collections.unmodifiableSet wraps the keys. The MutableSet return type is required
    // by ObservableMap's interface but throws UnsupportedOperationException on mutation attempts.
    @Suppress("UNCHECKED_CAST")
    override val keys: MutableSet<PK> get() {
        initialize()
        return Collections.unmodifiableSet(innerObservableMap.keys) as MutableSet<PK>
    }

    // Safe: Collections.unmodifiableCollection wraps the values. The MutableCollection return type
    // is required by ObservableMap's interface but is effectively immutable.
    @Suppress("UNCHECKED_CAST")
    override val values: MutableCollection<List<E>> get() {
        initialize()
        return Collections.unmodifiableCollection(innerObservableMap.values) as MutableCollection<List<E>>
    }

    override fun containsKey(key: PK): Boolean {
        initialize()
        return innerObservableMap.containsKey(key)
    }

    override fun containsValue(value: List<E>): Boolean {
        initialize()
        return innerObservableMap.containsValue(value)
    }

    override fun get(key: PK): List<E>? {
        initialize()
        return innerObservableMap[key]
    }

    override fun isEmpty(): Boolean {
        initialize()
        return innerObservableMap.isEmpty()
    }

    // Mutation methods — this projection is read-only; all mutations flow through the source collection
    override fun put(key: PK, value: List<E>): List<E> = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun remove(key: PK): List<E>? = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun putAll(from: Map<out PK, List<E>>) = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun clear() = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    companion object {
        private const val READ_ONLY_MESSAGE = "FxMultiKeyProjectionMap is read-only"
    }

    // Listener methods delegate to innerObservableMap; addListener also triggers initialization
    // so the source subscription is established before the first change fires.
    override fun addListener(listener: MapChangeListener<in PK, in List<E>>) {
        initialize()
        innerObservableMap.addListener(listener)
    }

    override fun removeListener(listener: MapChangeListener<in PK, in List<E>>) =
        innerObservableMap.removeListener(listener)

    override fun addListener(listener: InvalidationListener) {
        initialize()
        innerObservableMap.addListener(listener)
    }

    override fun removeListener(listener: InvalidationListener) =
        innerObservableMap.removeListener(listener)

    /**
     * Returns `this` projection map, initializing the source subscription on the first call.
     *
     * Implements Kotlin `by`-delegation: `val byGenre: ObservableMap<String, List<E>> by fxMultiKeyProjectionMap(...)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): FxMultiKeyProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
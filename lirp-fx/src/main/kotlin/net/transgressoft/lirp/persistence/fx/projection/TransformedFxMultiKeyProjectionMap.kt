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
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A read-only [ObservableMap] that derives a multi-key grouped and value-transformed view from an
 * [FxObservableCollection] source (either an [FxAggregateList] or [FxAggregateSet]).
 *
 * Unlike [TransformedFxProjectionMap] (one entity per bucket), this map places each entity under
 * every bucket key that [keyExtractor] returns for it. A `MutableMultiKeyAudioItem` with genres
 * `{Rock, Jazz}` appears in both the `"Rock"` and `"Jazz"` buckets. Each non-empty bucket is then
 * passed to [valueTransform] to produce the observable value `V`.
 *
 * The [valueTransform] is invoked on the **background thread** (the thread that delivers the
 * source collection's change event), not on the FX Application Thread. The computed `V` is then
 * staged and mirrored into the [ObservableMap] in a single FX pulse (one [Platform.runLater]
 * call in dispatch mode, one [ReactiveScope.flowScope] channel action otherwise). This separates
 * potentially expensive transform work from the UI-thread rendering step.
 *
 * **Important:** [valueTransform] MUST be a pure, thread-agnostic function. It must not read
 * or write any JavaFX property or node, and must not block the calling thread. Transform output
 * is computed exactly once per affected bucket per source delta.
 *
 * Each entity added to a bucket is subscribed to its own mutation events. When a key-relevant
 * property changes (the key set returned by [keyExtractor] differs from the previous value), the
 * core's `reconcile(entity)` method is called to perform add-before-remove re-bucketing in one
 * atomic delta, guaranteeing the entity is never transiently absent from all buckets mid-move.
 * The mutation subscription is cancelled when the entity is removed from all buckets, and all
 * remaining subscriptions are cancelled on [close].
 *
 * Buckets that become empty remove their key from the map (transform is not invoked for absent
 * buckets). The projection initializes lazily on the first [getValue] or [addListener] call.
 *
 * Mutation methods ([put], [remove], [putAll], [clear]) throw [UnsupportedOperationException];
 * all mutations flow through the source collection.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type, must extend [ReactiveEntity]
 * @param V the transform output type
 * @param sourceRef deferred reference to the source [FxObservableCollection] (resolved on first access)
 * @param keyExtractor function that extracts the set of projection keys from an entity;
 *   each returned key names one bucket the entity belongs to
 * @param valueTransform pure function that maps a non-empty bucket to its display value
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class TransformedFxMultiKeyProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E, V>(
    private val sourceRef: () -> FxObservableCollection<K, E>,
    private val keyExtractor: (E) -> Collection<PK>,
    private val valueTransform: (PK, List<E>) -> V,
    val dispatchToFxThread: Boolean = true
) : ObservableMap<PK, V>, AutoCloseable where E : IdentifiableEntity<K>, E : ReactiveEntity<K, E> {

    private val innerObservableMap: ObservableMap<PK, V> =
        FXCollections.observableMap(ConcurrentSkipListMap<PK, V>())

    @Volatile
    private var initialized = false

    private val initLock = Any()

    private val mutationChannel: Channel<() -> Unit>? =
        if (!dispatchToFxThread) Channel(Channel.UNLIMITED) else null

    private val initBarrier: CompletableDeferred<Unit>? =
        if (!dispatchToFxThread) CompletableDeferred() else null

    // Pending-flush coalescer — stores precomputed V values for non-empty buckets and tracks
    // keys of removed (emptied) buckets separately, then flushes both into innerObservableMap
    // in a single target-thread call per source event burst.
    // ConcurrentHashMap does not allow null values, so removals are tracked in a separate set.
    private val pendingUpdates = ConcurrentHashMap<PK, V>()
    private val pendingRemovals = CopyOnWriteArraySet<PK>()
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
                innerObservableMap[key] = valueTransform(key, bucket)
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
            entity.subscribeAsync { event ->
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
     * Accumulates [changedKeys] into the pending-update map (precomputing the transform for each
     * key on the background thread) and schedules a single flush if none is already pending.
     * After accumulating keys, checks whether any entities have entered or left all buckets
     * so that subscriptions can be established or cancelled. The [valueTransform] runs here —
     * on the calling (background) thread — never on the FX thread. The flush executes on the FX
     * Application Thread ([Platform.runLater]) or on [ReactiveScope.flowScope] ([mutationChannel]),
     * depending on [dispatchToFxThread].
     */
    fun scheduleFlush(changedKeys: Set<PK>) {
        val (newUpdates, newRemovals) = computeTransforms(changedKeys)
        reconcileSubscriptions(changedKeys)
        stageAndSchedule(newUpdates, newRemovals)
    }

    private fun computeTransforms(changedKeys: Set<PK>): Pair<Map<PK, V>, Set<PK>> {
        val updates = mutableMapOf<PK, V>()
        val removals = mutableSetOf<PK>()
        for (key in changedKeys) {
            val bucket = core.bucketSnapshot(key)
            if (bucket == null) removals += key else updates[key] = valueTransform(key, bucket)
        }
        return Pair(updates, removals)
    }

    // Reconcile entity subscriptions using the reverse index (entity id → set of bucket PKs).
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

    private fun stageAndSchedule(newUpdates: Map<PK, V>, newRemovals: Set<PK>) {
        val shouldSchedule: Boolean
        // Stage the computed results into the shared pending structures atomically with respect to
        // flush(). Without this lock, a removal written to pendingRemovals after flush() clears
        // pendingUpdates but before it clears pendingRemovals is wiped by pendingRemovals.clear()
        // while flushScheduled is still true, so compareAndSet(false,true) fails and no new
        // runLater is scheduled — the removal is silently dropped until the next event.
        synchronized(this) {
            for (key in newRemovals) {
                pendingUpdates.remove(key)
                pendingRemovals.add(key)
            }
            for ((key, value) in newUpdates) {
                pendingRemovals.remove(key)
                pendingUpdates[key] = value
            }
            shouldSchedule = flushScheduled.compareAndSet(false, true)
        }
        if (shouldSchedule) {
            if (dispatchToFxThread) Platform.runLater(::flush)
            else mutationChannel!!.trySend(::flush)
        }
    }

    private fun flush() {
        val updates: Map<PK, V>
        val removals: Set<PK>
        // Drain both pending structures and reset the gate atomically with scheduleFlush's staging.
        synchronized(this) {
            updates = HashMap(pendingUpdates)
            pendingUpdates.clear()
            removals = HashSet(pendingRemovals)
            pendingRemovals.clear()
            flushScheduled.set(false)
        }
        for (key in removals) {
            innerObservableMap.remove(key)
        }
        for ((key, value) in updates) {
            innerObservableMap[key] = value
        }
    }

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

    // ObservableMap<PK, V> — read operations delegate to innerObservableMap after initialization

    override val size: Int get() {
        initialize()
        return innerObservableMap.size
    }

    @Suppress("UNCHECKED_CAST")
    override val entries: MutableSet<MutableMap.MutableEntry<PK, V>> get() {
        initialize()
        val snapshot = innerObservableMap.entries.map { java.util.AbstractMap.SimpleImmutableEntry(it.key, it.value) }.toSet()
        return Collections.unmodifiableSet(snapshot) as MutableSet<MutableMap.MutableEntry<PK, V>>
    }

    @Suppress("UNCHECKED_CAST")
    override val keys: MutableSet<PK> get() {
        initialize()
        return Collections.unmodifiableSet(innerObservableMap.keys) as MutableSet<PK>
    }

    @Suppress("UNCHECKED_CAST")
    override val values: MutableCollection<V> get() {
        initialize()
        return Collections.unmodifiableCollection(innerObservableMap.values) as MutableCollection<V>
    }

    override fun containsKey(key: PK): Boolean {
        initialize()
        return innerObservableMap.containsKey(key)
    }

    override fun containsValue(value: V): Boolean {
        initialize()
        return innerObservableMap.containsValue(value)
    }

    override fun get(key: PK): V? {
        initialize()
        return innerObservableMap[key]
    }

    override fun isEmpty(): Boolean {
        initialize()
        return innerObservableMap.isEmpty()
    }

    // Mutation methods — this projection is read-only; all mutations flow through the source collection
    override fun put(key: PK, value: V): V = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun remove(key: PK): V? = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun putAll(from: Map<out PK, V>) = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun clear() = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    companion object {
        private const val READ_ONLY_MESSAGE = "TransformedFxMultiKeyProjectionMap is read-only"
    }

    override fun addListener(listener: MapChangeListener<in PK, in V>) {
        initialize()
        innerObservableMap.addListener(listener)
    }

    override fun removeListener(listener: MapChangeListener<in PK, in V>) =
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
     * Implements Kotlin `by`-delegation:
     * `val byGenre: ObservableMap<String, GenreStats> by fxMultiKeyProjectionMap(src, keys, transform)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): TransformedFxMultiKeyProjectionMap<K, PK, E, V> {
        initialize()
        return this
    }
}
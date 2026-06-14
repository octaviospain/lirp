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
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.Registry
import net.transgressoft.lirp.persistence.projection.RegistryProjectionMap
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.FXCollections
import javafx.collections.MapChangeListener
import javafx.collections.ObservableMap
import java.util.Collections
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A read-only [ObservableMap] projection that groups all entities from a [Registry] by a
 * secondary key, with all bucket mutations dispatched to the JavaFX Application Thread.
 *
 * Entities are grouped by [keyExtractor] into frozen [List] buckets keyed by projection key
 * type [PK]. The backing map is a [ConcurrentSkipListMap] wrapped by [FXCollections.observableMap],
 * so projection keys are always iterated in natural sorted order with CME-free iteration.
 *
 * Bucketing, soft-delete filtering, and reverse-index tracking are all delegated to a core
 * [RegistryProjectionMap] instance. Changes notified by the core engine's `onBucketsChanged`
 * hook are collected in a pending set and flushed in exactly one [Platform.runLater] call
 * (dispatch mode) or one [ReactiveScope.flowScope] channel action (non-dispatch mode),
 * ensuring all bucket changes from a single registry event land in one FX pulse.
 *
 * The projection initializes lazily on the first [getValue] or [addListener] call.
 *
 * Mutation methods ([put], [remove], [putAll], [clear]) throw [UnsupportedOperationException];
 * all mutations flow through the source registry.
 *
 * **Consistency window:** During lazy initialization, the projection seeds from [Registry.iterator]
 * and then subscribes to incremental [net.transgressoft.lirp.event.CrudEvent] notifications. Mutations
 * that occur in the narrow window between iterator exhaustion and the subscription coroutine starting its
 * collect loop will not appear in the projection until the affected entity receives a subsequent event.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable] (used as the backing [ConcurrentSkipListMap] key)
 * @param E the entity type
 * @param registry the source registry to project
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class RegistryFxProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val registry: Registry<K, E>,
    private val keyExtractor: (E) -> PK,
    val dispatchToFxThread: Boolean = true
) : ObservableMap<PK, List<E>>, AutoCloseable {
    private val innerObservableMap: ObservableMap<PK, List<E>> =
        FXCollections.observableMap(ConcurrentSkipListMap<PK, List<E>>())

    @Volatile
    private var initialized = false

    private val initLock = Any()

    private val mutationChannel: Channel<() -> Unit>? =
        if (!dispatchToFxThread) Channel(Channel.UNLIMITED) else null

    private val initBarrier: CompletableDeferred<Unit>? =
        if (!dispatchToFxThread) CompletableDeferred() else null

    // Pending-flush coalescer — two key sets for different update semantics.
    //
    // pendingBucketKeys: keys arriving via onBucketsChanged (creates, deletes, re-buckets).
    //   Flushed with a standard put-or-remove: no equality problem because the old value is
    //   either absent (create) or the new value is structurally different (re-bucket).
    //
    // pendingUpdateKeys: keys arriving via the Update subscription for in-place entity mutations.
    //   Flushed with remove-then-put to guarantee MapChangeListener fires even when the bucket
    //   list's equals() returns true (same object reference, same content).
    // All three are guarded by pendingLock so that staging keys + gating the flush, and draining +
    // resetting the gate, are each atomic with respect to one another across the producer and FX threads.
    private val pendingLock = Any()
    private val pendingBucketKeys = LinkedHashSet<PK>()
    private val pendingUpdateKeys = LinkedHashSet<PK>()
    private val flushScheduled = AtomicBoolean(false)

    private val core: RegistryProjectionMap<K, PK, E> = RegistryProjectionMap(registry, keyExtractor)

    // Subscription that forces a flush for in-place mutations where the entity is mutated
    // on the same object reference already stored in the bucket. In those cases the core's
    // equality check skips the bucket update and onBucketsChanged never fires, so the FX
    // MapChangeListener would silently receive no notification. This subscription ensures
    // that every Update event produces at least one flush regardless.
    private var updateSubscription: LirpEventSubscription<*, *, *>? = null

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
            // Trigger core initialization (seeds from registry, subscribes to CrudEvents).
            // onBucketsChanged is left unset during this phase so the initial seed does not
            // schedule a flush — the seed is applied directly to innerObservableMap below.
            core.size

            // Synchronously populate innerObservableMap from the core's freshly built buckets.
            for (key in core.keys) {
                val bucket = core.bucketSnapshot(key)
                if (bucket != null) innerObservableMap[key] = freezeBucket(bucket)
            }

            // Wire the coalescer after the initial seed so that only incremental (post-init) changes
            // go through scheduleFlush.
            core.addOnBucketsChangedListener(::scheduleFlush)

            // Subscribe to Update events to guarantee MapChangeListener fires for in-place mutations.
            // When an entity is mutated via a field assignment on the same object reference already
            // stored in the bucket, the core's equality guard in handleReplaceInBucket becomes a
            // no-op (oldEntity === newEntity), so onBucketsChanged is never called. This subscription
            // catches those cases by scheduling a force-flush (remove then re-insert) for the
            // entity's current projection key.
            updateSubscription =
                registry.subscribeAsync(CrudEvent.Type.UPDATE) { event ->
                    if (event is StandardCrudEvent.Update) {
                        val keysToFlush = event.entities.values.map(keyExtractor).toSet()
                        if (keysToFlush.isNotEmpty()) scheduleUpdateFlush(keysToFlush)
                    }
                }

            initBarrier?.complete(Unit)
            initialized = true
        }
    }

    /**
     * Accumulates [changedKeys] into the bucket-change pending set and schedules a single flush
     * if none is already pending. Called by the [RegistryProjectionMap] core via [onBucketsChanged]
     * for creates, deletes, and bucket key changes. Keys flushed from this set are written with
     * a standard put-or-remove, which is sufficient because the old value is either absent
     * (create) or structurally different (re-bucket).
     *
     * The flush executes on the FX Application Thread ([Platform.runLater]) or on
     * [ReactiveScope.flowScope] ([mutationChannel]), depending on [dispatchToFxThread].
     */
    fun scheduleFlush(changedKeys: Set<PK>) {
        val shouldSchedule: Boolean
        synchronized(pendingLock) {
            pendingBucketKeys.addAll(changedKeys)
            shouldSchedule = flushScheduled.compareAndSet(false, true)
        }
        if (shouldSchedule) dispatchFlush()
    }

    /**
     * Accumulates [keys] into the in-place-update pending set and schedules a flush.
     * Called from the Update event subscription to guarantee [MapChangeListener] notifications
     * fire even when the entity was mutated on the same object reference already held in the
     * bucket (where the core's equality check would silently skip the bucket update).
     * Keys flushed from this set are written with a remove-then-put so that JavaFX always
     * fires a change notification regardless of the previous value's equality.
     */
    private fun scheduleUpdateFlush(keys: Set<PK>) {
        val shouldSchedule: Boolean
        synchronized(pendingLock) {
            pendingUpdateKeys.addAll(keys)
            shouldSchedule = flushScheduled.compareAndSet(false, true)
        }
        if (shouldSchedule) dispatchFlush()
    }

    private fun dispatchFlush() {
        if (dispatchToFxThread) Platform.runLater(::flush)
        else mutationChannel!!.trySend(::flush)
    }

    private fun flush() {
        val bucketKeys: Set<PK>
        val updateKeys: Set<PK>
        // Drain both pending sets and reset the gate under one lock, atomically with the staging in
        // scheduleFlush / scheduleUpdateFlush. Resetting the gate outside this lock would strand any
        // keys a producer adds between the drain and the reset, since its compareAndSet would fail
        // while flushScheduled is still true and no new flush would be scheduled.
        synchronized(pendingLock) {
            bucketKeys = LinkedHashSet(pendingBucketKeys)
            pendingBucketKeys.clear()
            updateKeys = LinkedHashSet(pendingUpdateKeys)
            pendingUpdateKeys.clear()
            flushScheduled.set(false)
        }

        // Apply bucket changes (creates, deletes, re-buckets) with standard put-or-remove.
        for (key in bucketKeys) {
            val bucket = core.bucketSnapshot(key)
            if (bucket == null) innerObservableMap.remove(key)
            else innerObservableMap[key] = freezeBucket(bucket)
        }

        // Apply in-place updates with remove-then-put to guarantee MapChangeListener fires
        // even when the bucket list's equals() returns true. Skip keys already processed above
        // (re-bucket Updates that also appear in onBucketsChanged) — those are handled correctly
        // by the standard put path and don't need the extra remove.
        for (key in updateKeys) {
            if (key in bucketKeys) continue
            val bucket = core.bucketSnapshot(key)
            if (bucket == null) {
                innerObservableMap.remove(key)
            } else {
                innerObservableMap.remove(key)
                innerObservableMap[key] = freezeBucket(bucket)
            }
        }
    }

    private fun freezeBucket(elements: List<E>): List<E> =
        Collections.unmodifiableList(ArrayList(elements))

    /**
     * Cancels the registry subscription held by the core map and the in-place-update subscription,
     * releasing the projection's hold on the event stream. Idempotent and safe to call before first
     * access (no-op when not yet initialized). After closing, the projection no longer receives updates.
     */
    override fun close() {
        synchronized(initLock) {
            core.close()
            updateSubscription?.cancel()
            updateSubscription = null
        }
    }

    // ObservableMap<PK, List<E>> — read operations delegate to innerObservableMap after initialization

    override val size: Int get() {
        initialize()
        return innerObservableMap.size
    }

    // Safe: ObservableMap declares MutableSet<MutableEntry> but the returned set is unmodifiable via Collections.unmodifiableSet.
    // Callers cannot mutate through this view; the cast satisfies the interface contract without exposing true mutability.
    // Returns the live entry set backed by innerObservableMap — consistent with keys and values,
    // which also return live views. ConcurrentSkipListMap entries are CME-safe for iteration.
    @Suppress("UNCHECKED_CAST")
    override val entries: MutableSet<MutableMap.MutableEntry<PK, List<E>>> get() {
        initialize()
        return Collections.unmodifiableSet(innerObservableMap.entries) as MutableSet<MutableMap.MutableEntry<PK, List<E>>>
    }

    // Safe: same as entries — Collections.unmodifiableSet wraps the keys. The MutableSet return type is required by
    // ObservableMap's interface but the returned set throws UnsupportedOperationException on mutation attempts.
    @Suppress("UNCHECKED_CAST")
    override val keys: MutableSet<PK> get() {
        initialize()
        return Collections.unmodifiableSet(innerObservableMap.keys) as MutableSet<PK>
    }

    // Safe: same as entries/keys — Collections.unmodifiableCollection wraps the values. The MutableCollection return type
    // is required by ObservableMap's interface but the returned collection is effectively immutable.
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

    // Mutation methods — this projection is read-only; all mutations flow through the source registry
    override fun put(key: PK, value: List<E>): List<E> = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun remove(key: PK): List<E>? = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun putAll(from: Map<out PK, List<E>>) = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun clear() = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    companion object {
        private const val READ_ONLY_MESSAGE = "RegistryFxProjectionMap is read-only"
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
     * Returns `this` projection map, initializing the registry subscription on the first call.
     *
     * Implements Kotlin `by`-delegation: `val byAlbum: ObservableMap<String, List<AudioItem>> by registryFxProjectionMap(...)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): RegistryFxProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
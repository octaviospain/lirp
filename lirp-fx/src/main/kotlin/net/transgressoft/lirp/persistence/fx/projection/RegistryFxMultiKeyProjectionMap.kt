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
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.Registry
import net.transgressoft.lirp.persistence.projection.MultiKeyRegistryProjectionMap
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
 * A read-only [ObservableMap] projection that groups all entities from a [Registry] by multiple
 * secondary keys, with all bucket mutations dispatched to the JavaFX Application Thread.
 *
 * Unlike [RegistryFxProjectionMap] (one entity per bucket), this map places each entity under
 * every bucket key that [keyExtractor] returns for it. A `MutableMultiKeyAudioItem` with genres
 * `{Rock, Jazz}` appears in both the `"Rock"` and `"Jazz"` buckets.
 *
 * Delegates all bucketing and soft-delete filtering to a core [MultiKeyRegistryProjectionMap],
 * wiring its `onBucketsChanged` hook to a pending-flush coalescer that batches all bucket changes
 * from one registry event into a single [Platform.runLater] call (dispatch mode) or one
 * [ReactiveScope.flowScope] channel action (non-dispatch mode).
 *
 * In-place key-set changes (a registry Update that changes an entity's genre set) are reflected
 * natively via the core's Update path with add-before-remove ordering — no per-entity mutation
 * subscriptions are needed. The entity is never transiently absent from all buckets mid-move.
 *
 * The projection initializes lazily on the first [getValue] or [addListener] call.
 *
 * Mutation methods ([put], [remove], [putAll], [clear]) throw [UnsupportedOperationException];
 * all mutations flow through the source registry.
 *
 * **Thread safety:** Iteration of [keys], [values], [entries], plus [size], [containsKey],
 * and [get] never throws [ConcurrentModificationException], because the underlying
 * [ConcurrentSkipListMap] iterators are weakly-consistent.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param registry the source registry whose entities are projected
 * @param keyExtractor function that extracts the set of projection keys from an entity;
 *   each returned key names one bucket the entity belongs to
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class RegistryFxMultiKeyProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val registry: Registry<K, E>,
    private val keyExtractor: (E) -> Collection<PK>,
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

    // Pending-flush coalescer — collects changed keys on the registry-event thread and mirrors
    // them into innerObservableMap in a single flush on the target thread.
    private val pendingKeys = Collections.synchronizedSet(LinkedHashSet<PK>())
    private val flushScheduled = AtomicBoolean(false)

    private val core: MultiKeyRegistryProjectionMap<K, PK, E> =
        MultiKeyRegistryProjectionMap(registry, keyExtractor)

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
                val bucket = core.bucketSnapshot(key) ?: continue
                innerObservableMap[key] = freezeBucket(bucket)
            }

            // Wire the coalescer after the initial seed so that only incremental (post-init) changes
            // go through scheduleFlush.
            core.addOnBucketsChangedListener(::scheduleFlush)

            initBarrier?.complete(Unit)
            initialized = true
        }
    }

    /**
     * Accumulates [changedKeys] into the pending set and schedules a single flush if none is
     * already pending. The flush executes on the FX Application Thread ([Platform.runLater]) or
     * on [ReactiveScope.flowScope] ([mutationChannel]), depending on [dispatchToFxThread].
     */
    fun scheduleFlush(changedKeys: Set<PK>) {
        // Accumulate keys and gate the flush atomically: pairing the addAll with the compareAndSet
        // under the same lock prevents keys staged after flush() drains but before it resets the
        // gate from being stranded while flushScheduled is still true.
        val shouldSchedule: Boolean
        synchronized(pendingKeys) {
            pendingKeys.addAll(changedKeys)
            shouldSchedule = flushScheduled.compareAndSet(false, true)
        }
        if (shouldSchedule) {
            if (dispatchToFxThread) Platform.runLater(::flush)
            else mutationChannel!!.trySend(::flush)
        }
    }

    private fun flush() {
        val keys: Set<PK>
        // Drain and reset the gate atomically with scheduleFlush's staging.
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
     * Cancels the registry subscription held by the core map, releasing the projection's hold
     * on the event stream. Idempotent and safe to call before first access (no-op when not yet
     * initialized). After closing, the projection no longer receives updates.
     */
    override fun close() {
        synchronized(initLock) {
            core.close()
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
        return Collections.unmodifiableSet(innerObservableMap.entries) as MutableSet<MutableMap.MutableEntry<PK, List<E>>>
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

    // Mutation methods — this projection is read-only; all mutations flow through the source registry
    override fun put(key: PK, value: List<E>): List<E> = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun remove(key: PK): List<E>? = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun putAll(from: Map<out PK, List<E>>) = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun clear() = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    companion object {
        private const val READ_ONLY_MESSAGE = "RegistryFxMultiKeyProjectionMap is read-only"
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
     * Implements Kotlin `by`-delegation: `val byGenre: ObservableMap<String, List<E>> by registryFxMultiKeyProjectionMap(...)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): RegistryFxMultiKeyProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
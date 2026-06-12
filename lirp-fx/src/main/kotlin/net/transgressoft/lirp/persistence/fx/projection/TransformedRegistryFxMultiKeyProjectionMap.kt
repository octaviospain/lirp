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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A read-only [ObservableMap] projection that groups all entities from a [Registry] by multiple
 * secondary keys and applies a [valueTransform] to each bucket, producing `ObservableMap<PK, V>`.
 *
 * Unlike [TransformedRegistryFxProjectionMap] (one entity per bucket), this map places each entity
 * under every bucket key that [keyExtractor] returns for it. A `MutableMultiKeyAudioItem` with
 * genres `{Rock, Jazz}` appears in both the `"Rock"` and `"Jazz"` buckets; each non-empty bucket
 * is then passed to [valueTransform] to produce the observable value `V`.
 *
 * The [valueTransform] is invoked on the **background thread** (the thread delivering the
 * registry event), not on the FX Application Thread. The computed `V` is staged in a pending map
 * and applied to the [ObservableMap] in a single [Platform.runLater] call (dispatch mode) or one
 * [ReactiveScope.flowScope] channel action (non-dispatch mode), so all bucket changes from one
 * registry event land in exactly one FX pulse.
 *
 * **Important:** [valueTransform] MUST be a pure, thread-agnostic function. It must not read
 * or write any JavaFX property or node, and must not block the calling thread.
 *
 * In-place key-set changes (a registry Update that changes an entity's key set) are reflected
 * natively via the core's Update path with add-before-remove ordering — no per-entity mutation
 * subscriptions are needed. Buckets that become empty remove their key from the map. Soft-deleted
 * entities are excluded from all buckets by the core.
 *
 * The projection initializes lazily on the first [getValue] or [addListener] call.
 *
 * Mutation methods ([put], [remove], [putAll], [clear]) throw [UnsupportedOperationException];
 * all mutations flow through the source registry.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the transform output type
 * @param registry the source registry whose entities are projected
 * @param keyExtractor function that extracts the set of projection keys from an entity;
 *   each returned key names one bucket the entity belongs to
 * @param valueTransform pure function that maps a non-empty bucket to its display value
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class TransformedRegistryFxMultiKeyProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V>(
    private val registry: Registry<K, E>,
    private val keyExtractor: (E) -> Collection<PK>,
    private val valueTransform: (PK, List<E>) -> V,
    val dispatchToFxThread: Boolean = true
) : ObservableMap<PK, V>, AutoCloseable {

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

            // Synchronously seed innerObservableMap by applying the transform to each initial bucket.
            for (key in core.keys) {
                val bucket = core.bucketSnapshot(key) ?: continue
                innerObservableMap[key] = valueTransform(key, bucket)
            }

            // Wire the coalescer after the initial seed so that only incremental (post-init) changes
            // go through scheduleFlush.
            core.addOnBucketsChangedListener(::scheduleFlush)

            initBarrier?.complete(Unit)
            initialized = true
        }
    }

    /**
     * Precomputes the transformed value for each changed key on the background thread and schedules
     * a single flush if none is already pending. Called by the [MultiKeyRegistryProjectionMap] core
     * via `onBucketsChanged` for creates, deletes, and bucket key changes.
     *
     * The [valueTransform] runs here — on the calling (background) thread — never on the FX thread.
     */
    fun scheduleFlush(changedKeys: Set<PK>) {
        // Compute transforms on the calling (background) thread before acquiring the lock.
        val newUpdates = mutableMapOf<PK, V>()
        val newRemovals = mutableSetOf<PK>()
        for (key in changedKeys) {
            val bucket = core.bucketSnapshot(key)
            if (bucket == null) newRemovals += key else newUpdates[key] = valueTransform(key, bucket)
        }
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
     * Cancels the registry subscription held by the core map, releasing the projection's hold
     * on the event stream. Idempotent and safe to call before first access (no-op when not yet
     * initialized). After closing, the projection no longer receives updates.
     */
    override fun close() {
        synchronized(initLock) {
            core.close()
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

    // Mutation methods — this projection is read-only; all mutations flow through the source registry
    override fun put(key: PK, value: V): V = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun remove(key: PK): V? = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun putAll(from: Map<out PK, V>) = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun clear() = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    companion object {
        private const val READ_ONLY_MESSAGE = "TransformedRegistryFxMultiKeyProjectionMap is read-only"
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
     * Returns `this` projection map, initializing the registry subscription on the first call.
     *
     * Implements Kotlin `by`-delegation:
     * `val byGenre: ObservableMap<String, GenreStats> by registryFxMultiKeyProjectionMap(repo, keys, transform)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): TransformedRegistryFxMultiKeyProjectionMap<K, PK, E, V> {
        initialize()
        return this
    }
}
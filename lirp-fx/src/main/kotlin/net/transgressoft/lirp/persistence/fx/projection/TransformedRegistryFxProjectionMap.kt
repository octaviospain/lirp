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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A read-only [ObservableMap] projection that groups all entities from a [Registry] by a
 * secondary key and applies a [valueTransform] to each bucket, producing `ObservableMap<PK, V>`.
 *
 * Entities are grouped by [keyExtractor] into frozen `List<E>` buckets, then each non-empty
 * bucket is passed to [valueTransform] to produce the observable value `V`. The projection
 * stays in sync with the registry: creates, deletes, and key-change Updates re-bucket entities;
 * same-key Updates recompute the transform for the affected bucket.
 *
 * The [valueTransform] is invoked on the **background thread** (the thread delivering the
 * registry [CrudEvent]), not on the FX Application Thread. The computed `V` is staged in
 * a pending map and applied to the [ObservableMap] in a single [Platform.runLater] call
 * (dispatch mode) or one [ReactiveScope.flowScope] channel action (non-dispatch mode),
 * so all bucket changes from one registry event land in exactly one FX pulse.
 *
 * **Important:** [valueTransform] MUST be a pure, thread-agnostic function. It must not read
 * or write any JavaFX property or node, and must not block the calling thread.
 *
 * Buckets that become empty remove their key from the map (transform is not invoked for
 * absent buckets). Soft-deleted entities ([SoftDeletable] with non-null [deletedAt]) are
 * excluded from all buckets.
 *
 * The projection initializes lazily on the first [getValue] or [addListener] call.
 *
 * Mutation methods ([put], [remove], [putAll], [clear]) throw [UnsupportedOperationException];
 * all mutations flow through the source registry.
 *
 * **Consistency window:** During lazy initialization, the projection seeds from [Registry.iterator]
 * and then subscribes to incremental [CrudEvent] notifications. Mutations that occur in the narrow
 * window between iterator exhaustion and the subscription starting its collect loop will not
 * appear in the projection until the affected entity receives a subsequent event.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the transform output type
 * @param registry the source registry to project
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param valueTransform pure function that maps a non-empty bucket to its display value
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class TransformedRegistryFxProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V>(
    private val registry: Registry<K, E>,
    private val keyExtractor: (E) -> PK,
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

    private val core: RegistryProjectionMap<K, PK, E> = RegistryProjectionMap(registry, keyExtractor)

    // Subscription that triggers a transform recompute for in-place entity mutations where the
    // core's equality guard skips the bucket update (oldEntity === newEntity after in-place write).
    // Without this, a title-only field change would produce no flush and no MapChangeListener call.
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
            core.size

            // Seed innerObservableMap by applying the transform to each initial bucket.
            for (key in core.keys) {
                val bucket = core.bucketSnapshot(key)
                if (bucket != null) innerObservableMap[key] = valueTransform(key, bucket)
            }

            // Wire the coalescer after the initial seed.
            core.addOnBucketsChangedListener(::scheduleFlush)

            // Guarantee MapChangeListener fires for in-place entity mutations. When a field is
            // assigned on the live entity reference already stored in the bucket, the core skips
            // the bucket update (equality guard), so onBucketsChanged never fires. This subscription
            // catches those cases: it precomputes the new V on the background thread and enqueues
            // a flush so FX listeners observe the updated transform result.
            updateSubscription =
                registry.subscribeAsync(CrudEvent.Type.UPDATE) { event ->
                    if (event is StandardCrudEvent.Update) {
                        val keysToFlush = event.entities.values.map(keyExtractor).toSet()
                        if (keysToFlush.isNotEmpty()) scheduleFlushForUpdates(keysToFlush)
                    }
                }

            initBarrier?.complete(Unit)
            initialized = true
        }
    }

    /**
     * Precomputes the transformed value for each changed key on the background thread and schedules
     * a single flush if none is already pending. Called by the [RegistryProjectionMap] core via
     * `onBucketsChanged` for creates, deletes, and bucket key changes.
     *
     * The [valueTransform] runs here — on the calling (background) thread — never on the FX thread.
     */
    fun scheduleFlush(changedKeys: Set<PK>) {
        val (newUpdates, newRemovals) = computeTransforms(changedKeys)
        stageAndSchedule(newUpdates, newRemovals)
    }

    /**
     * Precomputes the transformed value for in-place Update events where `onBucketsChanged` was
     * not called because the core's equality guard detected no structural bucket change.
     * Keys already staged by a concurrent `onBucketsChanged` call are skipped inside the lock
     * to avoid overwriting a newer bucket state with a stale in-place update.
     */
    private fun scheduleFlushForUpdates(keys: Set<PK>) {
        val (candidateUpdates, candidateRemovals) = computeTransforms(keys)
        val (newUpdates, newRemovals) = filterCandidates(candidateUpdates, candidateRemovals)
        if (newUpdates.isNotEmpty() || newRemovals.isNotEmpty()) stageAndSchedule(newUpdates, newRemovals)
    }

    private fun computeTransforms(keys: Set<PK>): Pair<Map<PK, V>, Set<PK>> {
        val updates = mutableMapOf<PK, V>()
        val removals = mutableSetOf<PK>()
        for (key in keys) {
            val bucket = core.bucketSnapshot(key)
            if (bucket == null) removals += key else updates[key] = valueTransform(key, bucket)
        }
        return Pair(updates, removals)
    }

    // Inside the lock, skip keys that onBucketsChanged has already staged to avoid
    // overwriting a structurally-different bucket update with a stale in-place snapshot.
    private fun filterCandidates(candidateUpdates: Map<PK, V>, candidateRemovals: Set<PK>): Pair<Map<PK, V>, Set<PK>> {
        val newUpdates = mutableMapOf<PK, V>()
        val newRemovals = mutableSetOf<PK>()
        synchronized(this) {
            for (key in candidateRemovals) {
                if (!pendingUpdates.containsKey(key) && !pendingRemovals.contains(key)) newRemovals += key
            }
            for ((key, value) in candidateUpdates) {
                if (!pendingUpdates.containsKey(key) && !pendingRemovals.contains(key)) newUpdates[key] = value
            }
        }
        return Pair(newUpdates, newRemovals)
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
        // Drain both pending structures and reset the gate atomically so a concurrent scheduleFlush
        // arriving between the two clear() calls cannot be lost. Without this lock a removal
        // reaching pendingRemovals after pendingUpdates.clear() but before pendingRemovals.clear()
        // would be wiped while flushScheduled is still true — the caller's compareAndSet(false,true)
        // fails, so no new runLater is scheduled and the removal is silently dropped.
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

    // ObservableMap<PK, V> — read operations delegate to innerObservableMap after initialization

    override val size: Int get() {
        initialize()
        return innerObservableMap.size
    }

    @Suppress("UNCHECKED_CAST")
    override val entries: MutableSet<MutableMap.MutableEntry<PK, V>> get() {
        initialize()
        val snapshot = innerObservableMap.entries.map { java.util.AbstractMap.SimpleImmutableEntry(it.key, it.value) }.toSet()
        return java.util.Collections.unmodifiableSet(snapshot) as MutableSet<MutableMap.MutableEntry<PK, V>>
    }

    @Suppress("UNCHECKED_CAST")
    override val keys: MutableSet<PK> get() {
        initialize()
        return java.util.Collections.unmodifiableSet(innerObservableMap.keys) as MutableSet<PK>
    }

    @Suppress("UNCHECKED_CAST")
    override val values: MutableCollection<V> get() {
        initialize()
        return java.util.Collections.unmodifiableCollection(innerObservableMap.values) as MutableCollection<V>
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
        private const val READ_ONLY_MESSAGE = "TransformedRegistryFxProjectionMap is read-only"
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
     * Implements Kotlin `by`-delegation: `val byAlbum: ObservableMap<String, AlbumSet> by transformedRegistryFxProjectionMap(...)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): TransformedRegistryFxProjectionMap<K, PK, E, V> {
        initialize()
        return this
    }
}
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
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.FxObservableCollection
import net.transgressoft.lirp.persistence.fx.FxAggregateList
import net.transgressoft.lirp.persistence.fx.FxAggregateSet
import net.transgressoft.lirp.persistence.projection.ProjectionMap
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.MapChangeListener
import javafx.collections.ObservableMap
import javafx.collections.SetChangeListener
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
 * A read-only [ObservableMap] that derives a grouped and value-transformed view from an
 * [FxObservableCollection] source (either an [FxAggregateList] or [FxAggregateSet]).
 *
 * Entities from the source collection are grouped by [keyExtractor] into buckets of type
 * `List<E>`, then the [valueTransform] function maps each `(PK, List<E>)` pair to a value `V`.
 * The result is an `ObservableMap<PK, V>` whose entries stay in sync with the source.
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
 * Buckets that become empty remove their key from the map (transform is not invoked for absent
 * buckets).
 *
 * The projection initializes lazily on the first [getValue] or [addListener] call.
 *
 * Mutation methods ([put], [remove], [putAll], [clear]) throw [UnsupportedOperationException];
 * all mutations flow through the source collection.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the transform output type
 * @param sourceRef deferred reference to the source [FxObservableCollection]
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param valueTransform pure function that maps a non-empty bucket to its display value
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class TransformedFxProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V>(
    private val sourceRef: () -> FxObservableCollection<K, E>,
    private val keyExtractor: (E) -> PK,
    private val valueTransform: (PK, List<E>) -> V,
    val dispatchToFxThread: Boolean = true
) : ObservableMap<PK, V> {
    private val innerObservableMap: ObservableMap<PK, V> =
        FXCollections.observableMap(ConcurrentSkipListMap<PK, V>())

    // Thread-safe bucket state updated by source-collection listeners; the pending-flush
    // coalescer reads from this map to compute transformed values before flushing.
    private val backingMap = ConcurrentSkipListMap<PK, List<E>>()

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

    // Held core map for wiring the onBucketsChanged hook from registry-bound aggregate sources.
    @Suppress("UNCHECKED_CAST")
    private val core: ProjectionMap<K, PK, E> =
        ProjectionMap(
            { sourceRef() as AggregateCollectionRef<K, E> },
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
            core.addOnBucketsChangedListener(::scheduleFlush)

            when (val source = sourceRef()) {
                is FxAggregateList<*, *> -> subscribeToList(source)
                is FxAggregateSet<*, *> -> subscribeToSet(source)
                else ->
                    error(
                        "TransformedFxProjectionMap requires an FxObservableCollection source, " +
                            "but received: ${source::class.qualifiedName}"
                    )
            }
            initBarrier?.complete(Unit)
            initialized = true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun subscribeToList(source: FxAggregateList<*, *>) {
        val typedSource = source as FxAggregateList<K, E>
        typedSource.addListener(
            ListChangeListener { change ->
                val changedKeys = mutableSetOf<PK>()
                while (change.next()) {
                    if (change.wasAdded()) changedKeys += handleAdded(change.addedSubList as List<E>)
                    if (change.wasRemoved()) changedKeys += handleRemoved(change.removed as List<E>)
                }
                if (changedKeys.isNotEmpty()) scheduleFlush(changedKeys)
            }
        )
        val initialElements = typedSource.toList().ifEmpty { typedSource.innerProxy.resolveAll().toList() }
        populateInitialState(initialElements)
    }

    @Suppress("UNCHECKED_CAST")
    private fun subscribeToSet(source: FxAggregateSet<*, *>) {
        val typedSource = source as FxAggregateSet<K, E>
        typedSource.addListener(
            SetChangeListener { change ->
                val changedKeys = mutableSetOf<PK>()
                if (change.wasAdded()) changedKeys += handleAdded(listOf(change.elementAdded as E))
                if (change.wasRemoved()) changedKeys += handleRemoved(listOf(change.elementRemoved as E))
                if (changedKeys.isNotEmpty()) scheduleFlush(changedKeys)
            }
        )
        val initialElements = typedSource.toList().ifEmpty { typedSource.innerProxy.resolveAll().toList() }
        populateInitialState(initialElements)
    }

    private fun populateInitialState(elements: List<E>) {
        for (element in elements) {
            val key = keyExtractor(element)
            backingMap[key] = freezeBucket((backingMap[key] ?: emptyList()) + element)
        }
        for ((key, bucket) in backingMap) {
            innerObservableMap[key] = valueTransform(key, bucket)
        }
    }

    private fun handleAdded(elements: List<E>): Set<PK> {
        val changedKeys = mutableSetOf<PK>()
        for (element in elements) {
            val key = keyExtractor(element)
            val current = backingMap[key] ?: emptyList()
            if (element !in current) {
                backingMap[key] = freezeBucket(current + element)
                changedKeys += key
            }
        }
        return changedKeys
    }

    private fun handleRemoved(elements: List<E>): Set<PK> {
        val changedKeys = mutableSetOf<PK>()
        for (element in elements) {
            val key = keyExtractor(element)
            val current = backingMap[key]
            if (current != null && element in current) {
                val filtered = current.filter { it != element }
                if (filtered.isEmpty()) backingMap.remove(key)
                else backingMap[key] = freezeBucket(filtered)
                changedKeys += key
            } else {
                removeFromAnyBucket(element)?.let { changedKeys += it }
            }
        }
        return changedKeys
    }

    private fun removeFromAnyBucket(element: E): PK? {
        for (entry in backingMap.entries) {
            if (element in entry.value) {
                val filtered = entry.value.filter { it != element }
                if (filtered.isEmpty()) backingMap.remove(entry.key)
                else backingMap[entry.key] = freezeBucket(filtered)
                return entry.key
            }
        }
        return null
    }

    private fun freezeBucket(elements: List<E>): List<E> = Collections.unmodifiableList(ArrayList(elements))

    /**
     * Precomputes the transformed value for each changed key on the background thread and schedules
     * a single flush if none is already pending. Called by the [ProjectionMap] core via
     * `onBucketsChanged` and by the source-collection listeners.
     *
     * The [valueTransform] runs here — on the calling (background) thread — never on the FX thread.
     */
    fun scheduleFlush(changedKeys: Set<PK>) {
        // Compute transforms on the calling (background) thread before acquiring the lock,
        // keeping potentially expensive valueTransform calls outside of any synchronized region.
        val newUpdates = mutableMapOf<PK, V>()
        val newRemovals = mutableSetOf<PK>()
        for (key in changedKeys) {
            val bucket = backingMap[key]
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
        private const val READ_ONLY_MESSAGE = "TransformedFxProjectionMap is read-only"
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
     * Implements Kotlin `by`-delegation: `val byAlbum: ObservableMap<String, AlbumSet> by transformedFxProjectionMap(...)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): TransformedFxProjectionMap<K, PK, E, V> {
        initialize()
        return this
    }
}
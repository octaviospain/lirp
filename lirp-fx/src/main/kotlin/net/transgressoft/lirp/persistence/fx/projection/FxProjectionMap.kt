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
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A read-only [ObservableMap] that derives a grouped view from an existing
 * [FxObservableCollection] source (either an [FxAggregateList] or [FxAggregateSet]).
 *
 * Entities from the source collection are grouped by a [keyExtractor] function into
 * buckets of type `List<E>`, keyed by projection key type `PK`. The backing map is a
 * [ConcurrentSkipListMap] (wrapped by [FXCollections.observableMap]), so keys are always
 * iterated in natural sorted order with CME-free iteration under concurrent reads.
 *
 * A hold on a core [ProjectionMap] wires the `onBucketsChanged` seam to the pending-flush
 * coalescer. All bucket changes produced by a single source event are collected into a pending
 * key set and flushed to the [ObservableMap] in exactly one [Platform.runLater] call
 * (dispatch mode) or one [ReactiveScope.flowScope] channel action (non-dispatch mode).
 *
 * The projection initializes lazily on the first [getValue] or [addListener] call.
 *
 * This class implements [ObservableMap] directly — callers can add [MapChangeListener] or
 * [javafx.beans.InvalidationListener] and read map state (`size`, `get`, `keys`) directly on
 * the instance. Mutation methods (`put`, `remove`, `putAll`, `clear`) throw
 * [UnsupportedOperationException]; all mutations flow through the source collection.
 *
 * When [dispatchToFxThread] is `true` (the default), map change notifications are dispatched
 * to the JavaFX Application Thread via [Platform.runLater] when fired from a background thread.
 * When `false`, notifications are serialized through a Channel-based sequential processor on
 * [ReactiveScope.flowScope].
 *
 * **Thread safety:** Iteration of [keys], [values], [entries], plus [size], [containsKey],
 * and [get] never throws [ConcurrentModificationException], because the underlying
 * [ConcurrentSkipListMap] iterators are weakly-consistent.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable] (used as the backing [ConcurrentSkipListMap] key)
 * @param E the entity type
 * @param sourceRef deferred reference to the source [FxObservableCollection] (resolved on first [getValue] or [addListener])
 * @param keyExtractor grouping function that extracts the projection key from an entity
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class FxProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val sourceRef: () -> FxObservableCollection<K, E>,
    private val keyExtractor: (E) -> PK,
    val dispatchToFxThread: Boolean = true
) : ObservableMap<PK, List<E>> {
    private val innerObservableMap: ObservableMap<PK, List<E>> = FXCollections.observableMap(ConcurrentSkipListMap<PK, List<E>>())

    // Thread-safe bucket state updated by source-collection listeners;
    // the pending-flush coalescer reads from this map and mirrors it into innerObservableMap.
    private val backingMap = ConcurrentSkipListMap<PK, List<E>>()

    @Volatile
    private var initialized = false

    private val initLock = Any()

    private val mutationChannel: Channel<() -> Unit>? =
        if (!dispatchToFxThread) Channel(Channel.UNLIMITED) else null

    private val initBarrier: CompletableDeferred<Unit>? =
        if (!dispatchToFxThread) CompletableDeferred() else null

    // Pending-flush coalescer — collects changed keys on the source-listener thread and
    // mirrors them into innerObservableMap in a single flush on the target thread.
    private val pendingKeys = Collections.synchronizedSet(LinkedHashSet<PK>())
    private val flushScheduled = AtomicBoolean(false)

    // Held core map wiring onBucketsChanged to the pending-flush coalescer. The slot is
    // set in initialize() so only post-init deltas from registry-bound aggregate sources
    // arrive through the core engine's hook. Direct FxObservableCollection mutations are
    // handled via the FxAggregateList/FxAggregateSet listener subscriptions below.
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
            // Wire the coalescer onto the core engine's onBucketsChanged hook.
            // This activates single-pulse batching for registry-bound aggregate sources.
            core.addOnBucketsChangedListener(::scheduleFlush)

            when (val source = sourceRef()) {
                is FxAggregateList<*, *> -> subscribeToList(source)
                is FxAggregateSet<*, *> -> subscribeToSet(source)
                else ->
                    error(
                        "FxProjectionMap requires an FxObservableCollection source, " +
                            "but received: ${source::class.qualifiedName}"
                    )
            }
            initBarrier?.complete(Unit)
            initialized = true
        }
    }

    // Safe: FxProjectionMap is constructed with a source typed as FxAggregateList<K, E>.
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

    // Safe: FxProjectionMap is constructed with a source typed as FxAggregateSet<K, E>.
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

    private fun freezeBucket(elements: List<E>): List<E> = Collections.unmodifiableList(ArrayList(elements))

    private fun populateInitialState(elements: List<E>) {
        for (element in elements) {
            val key = keyExtractor(element)
            backingMap[key] = freezeBucket((backingMap[key] ?: emptyList()) + element)
        }
        for ((key, bucket) in backingMap) {
            innerObservableMap[key] = bucket
        }
    }

    /** Adds [elements] to [backingMap] and returns the set of affected keys. */
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

    /** Removes [elements] from [backingMap] and returns the set of affected keys. */
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

    /**
     * Accumulates [changedKeys] into the pending set and schedules a single flush if none is
     * already pending. The flush executes on the FX Application Thread ([Platform.runLater])
     * or on [ReactiveScope.flowScope] ([mutationChannel]), depending on [dispatchToFxThread].
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
            val bucket = backingMap[key]
            if (bucket == null) innerObservableMap.remove(key)
            else innerObservableMap[key] = bucket
        }
    }

    // ObservableMap<PK, List<E>> — read operations delegate to innerObservableMap after initialization
    override val size: Int get() {
        initialize()
        return innerObservableMap.size
    }

    // Safe: ObservableMap declares MutableSet<MutableEntry> but the returned set is unmodifiable via Collections.unmodifiableSet.
    // Callers cannot mutate through this view; the cast satisfies the interface contract without exposing true mutability.
    @Suppress("UNCHECKED_CAST")
    override val entries: MutableSet<MutableMap.MutableEntry<PK, List<E>>> get() {
        initialize()
        val snapshot = innerObservableMap.entries.map { java.util.AbstractMap.SimpleImmutableEntry(it.key, it.value) }.toSet()
        return Collections.unmodifiableSet(snapshot) as MutableSet<MutableMap.MutableEntry<PK, List<E>>>
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

    // Mutation methods — this projection is read-only; all mutations flow through the source collection
    override fun put(key: PK, value: List<E>): List<E> = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun remove(key: PK): List<E>? = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun putAll(from: Map<out PK, List<E>>) = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun clear() = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    companion object {
        private const val READ_ONLY_MESSAGE = "FxProjectionMap is read-only"
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
     * Implements Kotlin `by`-delegation: `val byAlbum: ObservableMap<String, List<AudioItem>> by fxProjection(...)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): FxProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
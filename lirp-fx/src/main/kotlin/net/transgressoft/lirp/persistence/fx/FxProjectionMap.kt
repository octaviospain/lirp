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

package net.transgressoft.lirp.persistence.fx

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.FxObservableCollection
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.MapChangeListener
import javafx.collections.ObservableMap
import javafx.collections.SetChangeListener
import java.util.Collections
import java.util.TreeMap
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * This is the JavaFX counterpart of [net.transgressoft.lirp.persistence.ProjectionMap] from `lirp-core`,
 * extending it with a JavaFX [ObservableMap] interface and reactive listener support.
 *
 * A read-only [ObservableMap] that derives a grouped view from an existing
 * [FxObservableCollection] source (either an [FxAggregateList] or [FxAggregateSet]).
 *
 * Entities from the source collection are grouped by a [keyExtractor] function into
 * buckets of type `List<E>`, keyed by projection key type `PK`. The backing map is a
 * [TreeMap], so keys are always iterated in natural sorted order.
 *
 * The projection initializes lazily: the source subscription and initial state build happen
 * on the first [getValue] call (Kotlin `by` delegation) or on the first [addListener] call,
 * not at construction time. This keeps construction cheap even if the source is not yet fully populated.
 *
 * Changes to the source collection are propagated incrementally via a [ListChangeListener]
 * (for list sources) or [SetChangeListener] (for set sources). Each add or remove fires a
 * targeted [MapChangeListener.Change] on the affected bucket key only.
 *
 * This class implements [ObservableMap] directly — callers can add [MapChangeListener] or
 * [javafx.beans.InvalidationListener] and read map state (`size`, `get`, `keys`) directly on
 * the instance. Mutation methods (`put`, `remove`, `putAll`, `clear`) throw
 * [UnsupportedOperationException]; all mutations flow through the source collection.
 *
 * When [dispatchToFxThread] is `true` (the default), map change notifications are dispatched
 * to the JavaFX Application Thread via [Platform.runLater] when fired from a background thread.
 * This is the recommended mode for thread-safe access — all mutations are marshaled to the
 * single FX Application Thread.
 *
 * When `false`, notifications and mutations are serialized through a Channel-based sequential
 * processor on [ReactiveScope.flowScope], ensuring no concurrent map access.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable] (used as [TreeMap] key)
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
    private val innerObservableMap: ObservableMap<PK, List<E>> = FXCollections.observableMap(TreeMap<PK, List<E>>())

    @Volatile
    private var initialized = false

    private val mutationChannel: Channel<() -> Unit>? =
        if (!dispatchToFxThread) Channel(Channel.UNLIMITED) else null

    private val initBarrier: CompletableDeferred<Unit>? =
        if (!dispatchToFxThread) CompletableDeferred() else null

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
        synchronized(this) {
            if (initialized) return
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

    // Safe: FxProjectionMap<PK, K, E> is constructed with a source typed as FxAggregateList<K, E> or FxAggregateSet<K, E>.
    // The wildcard erasure at the call site is a consequence of the sealed-source pattern; the concrete type matches K, E.
    @Suppress("UNCHECKED_CAST")
    private fun subscribeToList(source: FxAggregateList<*, *>) {
        val typedSource = source as FxAggregateList<K, E>
        typedSource.addListener(
            ListChangeListener { change ->
                while (change.next()) {
                    if (change.wasAdded()) handleAdded(change.addedSubList as List<E>)
                    if (change.wasRemoved()) handleRemoved(change.removed as List<E>)
                }
            }
        )
        val initialElements = typedSource.toList().ifEmpty { typedSource.innerProxy.resolveAll().toList() }
        populateInitialState(initialElements)
    }

    // Safe: same as subscribeToList — the source FxAggregateSet<*, *> was constructed with matching K, E type parameters.
    @Suppress("UNCHECKED_CAST")
    private fun subscribeToSet(source: FxAggregateSet<*, *>) {
        val typedSource = source as FxAggregateSet<K, E>
        typedSource.addListener(
            SetChangeListener { change ->
                if (change.wasAdded()) handleAdded(listOf(change.elementAdded as E))
                if (change.wasRemoved()) handleRemoved(listOf(change.elementRemoved as E))
            }
        )
        val initialElements = typedSource.toList().ifEmpty { typedSource.innerProxy.resolveAll().toList() }
        populateInitialState(initialElements)
    }

    private fun freezeBucket(elements: List<E>): List<E> = java.util.Collections.unmodifiableList(ArrayList(elements))

    // Writes directly to innerObservableMap without mutateMap dispatch — called during
    // initialize() so the initial state is available synchronously before initialized=true.
    private fun populateInitialState(elements: List<E>) {
        for (element in elements) {
            val key = keyExtractor(element)
            val updated = freezeBucket((innerObservableMap[key] ?: emptyList()) + element)
            innerObservableMap[key] = updated
        }
    }

    private fun handleAdded(elements: List<E>) {
        for (element in elements) {
            val key = keyExtractor(element)
            mutateMap {
                val current = innerObservableMap[key] ?: emptyList()
                if (element !in current) {
                    innerObservableMap[key] = freezeBucket(current + element)
                }
            }
        }
    }

    private fun handleRemoved(elements: List<E>) {
        for (element in elements) {
            val key = keyExtractor(element)
            mutateMap {
                val current = innerObservableMap[key]
                if (current != null && element in current) {
                    val filtered = current.filter { it != element }
                    if (filtered.isEmpty()) innerObservableMap.remove(key)
                    else innerObservableMap[key] = freezeBucket(filtered)
                } else {
                    removeFromAnyBucket(element)
                }
            }
        }
    }

    private fun removeFromAnyBucket(element: E) {
        val iterator = innerObservableMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (element in entry.value) {
                val filtered = entry.value.filter { it != element }
                if (filtered.isEmpty()) iterator.remove()
                else entry.setValue(freezeBucket(filtered))
                return
            }
        }
    }

    private fun mutateMap(action: () -> Unit) {
        if (dispatchToFxThread) {
            if (Platform.isFxApplicationThread()) action()
            else Platform.runLater(action)
        } else {
            mutationChannel!!.trySend(action)
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
     * Implements Kotlin `by`-delegation: `val byAlbum: ObservableMap<String, List<AudioItem>> by fxProjectionMap(...)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): FxProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
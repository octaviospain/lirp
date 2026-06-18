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

package net.transgressoft.lirp.persistence.projection

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.MutableAggregateList
import net.transgressoft.lirp.persistence.MutableAggregateSet
import kotlin.reflect.KProperty

/**
 * A read-only grouped view that derives a `Map<PK, List<E>>` from a source collection,
 * grouping entities by a secondary key via [keyExtractor].
 *
 * The projection uses a [java.util.concurrent.ConcurrentSkipListMap] for natural key ordering with CME-free iteration,
 * and supports multiple onChange and onBucketsChanged listeners via [addOnChangeListener] and
 * [addOnBucketsChangedListener]. It has no JavaFX dependency and works with
 * any JVM target including Android and server-side applications.
 *
 * The projection initializes lazily on the first [getValue] (Kotlin `by` delegation) or map
 * access call, building its initial state from the source collection's current contents. When
 * the source is a [MutableAggregateList] or [MutableAggregateSet], subsequent mutations are
 * applied incrementally and automatically via the source collection's projection callback.
 * For plain collections, the map reflects the state at initialization time only.
 *
 * The map is read-only. All mutations flow through the source collection.
 *
 * **Thread safety:** Iterating [keys], [values], [entries], or calling [size], [containsKey],
 * and [get] is CME-free under concurrent mutation because the backing map is [java.util.concurrent.ConcurrentSkipListMap].
 * Reads are weakly-consistent: entries added concurrently may or may not be visible mid-iteration,
 * but iteration always completes without error. Mutations via [addOnChangeListener], [MutableAggregateList],
 * or [MutableAggregateSet] still flow through a single source-collection mutation thread;
 * the class does not provide cross-thread atomicity for compound read-modify-write of an
 * individual bucket — that contract is unchanged.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable] (used as the backing [java.util.concurrent.ConcurrentSkipListMap] key)
 * @param E the entity type
 * @param sourceRef deferred reference to the source collection (resolved on first access)
 * @param keyExtractor grouping function that extracts the projection key from an entity
 */
class ProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val sourceRef: () -> AggregateCollectionRef<K, E>,
    private val keyExtractor: (E) -> PK
) : AbstractMap<PK, List<E>>() {
    private val core = ProjectionCore(keyExtractor)

    private val initLock = Any()

    @Volatile
    private var initialized = false

    /**
     * Registers [listener] to be invoked after each projection change with the current map state.
     * Multiple listeners may be registered; each fires on the mutating thread in registration order.
     * The returned [AutoCloseable] deregisters this listener when closed.
     *
     * This is the primary seam for adapter layers (such as the FX decorator) that need to
     * observe and react to projection changes from a separate module.
     */
    fun addOnChangeListener(listener: (Map<PK, List<E>>) -> Unit): AutoCloseable =
        core.addOnChangeListener(listener)

    /**
     * Registers [listener] to be invoked alongside onChange listeners after each non-noop bucket
     * mutation, carrying only the keys changed by the latest delta. Multiple listeners may be
     * registered; each fires on the mutating thread in registration order.
     * The returned [AutoCloseable] deregisters this listener when closed.
     *
     * Adapter layers use this hook to coalesce bucket-level changes into a single notification
     * batch without needing to diff the full map state.
     */
    fun addOnBucketsChangedListener(listener: (Set<PK>) -> Unit): AutoCloseable =
        core.addOnBucketsChangedListener(listener)

    private fun initialize() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val source = sourceRef()
            core.handleAdded(source.resolveAll().toList())
            subscribeToSource(source)
            initialized = true
        }
    }

    /**
     * Returns the current contents of the [key] bucket WITHOUT triggering lazy initialization.
     *
     * Adapter layers (such as the FX decorator) call this from their [addOnBucketsChangedListener] hook to
     * read the latest bucket contents after each delta. The hook fires only after initialization has
     * populated the core, so this method must never re-enter [initialize] (which would recurse).
     */
    fun bucketSnapshot(key: PK): List<E>? = core.readOnlyView[key]

    @Suppress("UNCHECKED_CAST")
    private fun subscribeToSource(source: AggregateCollectionRef<K, E>) {
        val callback: (CollectionChangeEvent<*>) -> Unit = { event ->
            if (event.type == CollectionChangeEvent.Type.CLEAR) {
                rebuild(source)
            } else {
                if (event.added.isNotEmpty()) core.handleAdded(event.added as List<E>)
                if (event.removed.isNotEmpty()) core.handleRemoved(event.removed as List<E>)
            }
        }
        when (source) {
            is MutableAggregateList<*, *> -> source.innerDelegate.addProjectionCallback(callback)
            is MutableAggregateSet<*, *> -> source.innerDelegate.addProjectionCallback(callback)
        }
    }

    private fun rebuild(source: AggregateCollectionRef<K, E>) {
        core.backingMap.clear()
        core.handleAdded(source.resolveAll().toList())
    }

    // Map<PK, List<E>> delegation — enables direct read access (projection.size, projection["key"])
    override val size: Int get() {
        initialize()
        return core.readOnlyView.size
    }
    override val entries: Set<Map.Entry<PK, List<E>>> get() {
        initialize()
        return core.readOnlyView.entries
    }
    override val keys: Set<PK> get() {
        initialize()
        return core.readOnlyView.keys
    }
    override val values: Collection<List<E>> get() {
        initialize()
        return core.readOnlyView.values
    }

    override fun containsKey(key: PK): Boolean {
        initialize()
        return core.readOnlyView.containsKey(key)
    }

    override fun containsValue(value: List<E>): Boolean {
        initialize()
        return core.readOnlyView.containsValue(value)
    }

    override fun get(key: PK): List<E>? {
        initialize()
        return core.readOnlyView[key]
    }

    override fun isEmpty(): Boolean {
        initialize()
        return core.readOnlyView.isEmpty()
    }

    /**
     * Returns `this` projection map, initializing the source state on the first call.
     *
     * Implements Kotlin `by`-delegation: `val grouped by projection(::items) { it.key }`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): ProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
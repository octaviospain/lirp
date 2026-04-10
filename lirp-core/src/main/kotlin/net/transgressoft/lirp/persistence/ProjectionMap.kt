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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.event.CollectionChangeEvent
import java.util.Collections
import java.util.TreeMap
import kotlin.reflect.KProperty

/**
 * A read-only grouped view that derives a `Map<PK, List<E>>` from a source collection,
 * grouping entities by a secondary key via [keyExtractor].
 *
 * The projection uses a [TreeMap] for natural key ordering and fires an optional [onChange]
 * callback when the projection state changes. It has no JavaFX dependency and works with
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
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable] (used as [TreeMap] key)
 * @param E the entity type
 * @param sourceRef deferred reference to the source collection (resolved on first access)
 * @param keyExtractor grouping function that extracts the projection key from an entity
 */
class ProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>>(
    private val sourceRef: () -> AggregateCollectionRef<K, E>,
    private val keyExtractor: (E) -> PK
) : AbstractMap<PK, List<E>>() {
    private val backingMap = TreeMap<PK, List<E>>()
    private val readOnlyView: Map<PK, List<E>> = Collections.unmodifiableMap(backingMap)

    @Volatile
    private var initialized = false

    /**
     * Optional callback invoked after each projection change with the current map state.
     * Fires after every incremental update that results in at least one addition or removal.
     */
    internal var onChange: ((Map<PK, List<E>>) -> Unit)? = null

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val source = sourceRef()
            handleAdded(source.resolveAll().toList())
            subscribeToSource(source)
            initialized = true
        }
    }

    private fun freezeBucket(elements: List<E>): List<E> = java.util.Collections.unmodifiableList(ArrayList(elements))

    @Suppress("UNCHECKED_CAST")
    private fun subscribeToSource(source: AggregateCollectionRef<K, E>) {
        val callback: (CollectionChangeEvent<*>) -> Unit = { event ->
            if (event.type == CollectionChangeEvent.Type.CLEAR) {
                rebuild(source)
            } else {
                if (event.added.isNotEmpty()) handleAdded(event.added as List<E>)
                if (event.removed.isNotEmpty()) handleRemoved(event.removed as List<E>)
            }
        }
        when (source) {
            is MutableAggregateList<*, *> -> source.innerDelegate.addProjectionCallback(callback)
            is MutableAggregateSet<*, *> -> source.innerDelegate.addProjectionCallback(callback)
        }
    }

    private fun rebuild(source: AggregateCollectionRef<K, E>) {
        backingMap.clear()
        handleAdded(source.resolveAll().toList())
    }

    private fun handleAdded(elements: List<E>) {
        var changed = false
        for (element in elements) {
            val key = keyExtractor(element)
            backingMap[key] = freezeBucket((backingMap[key] ?: emptyList()) + element)
            changed = true
        }
        if (changed) onChange?.invoke(readOnlyView)
    }

    private fun handleRemoved(elements: List<E>) {
        var changed = false
        for (element in elements) {
            val key = keyExtractor(element)
            val bucket = backingMap[key]
            if (bucket != null && element in bucket) {
                val filtered = bucket.filter { it != element }
                if (filtered.isEmpty()) backingMap.remove(key)
                else backingMap[key] = freezeBucket(filtered)
                changed = true
            } else {
                changed = removeFromAnyBucket(element) || changed
            }
        }
        if (changed) onChange?.invoke(readOnlyView)
    }

    private fun removeFromAnyBucket(element: E): Boolean {
        val iterator = backingMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (element in entry.value) {
                val filtered = entry.value.filter { it != element }
                if (filtered.isEmpty()) iterator.remove()
                else entry.setValue(freezeBucket(filtered))
                return true
            }
        }
        return false
    }

    // Map<PK, List<E>> delegation — enables direct read access (projection.size, projection["key"])
    override val size: Int get() {
        initialize()
        return readOnlyView.size
    }
    override val entries: Set<Map.Entry<PK, List<E>>> get() {
        initialize()
        return readOnlyView.entries
    }
    override val keys: Set<PK> get() {
        initialize()
        return readOnlyView.keys
    }
    override val values: Collection<List<E>> get() {
        initialize()
        return readOnlyView.values
    }

    override fun containsKey(key: PK): Boolean {
        initialize()
        return readOnlyView.containsKey(key)
    }

    override fun containsValue(value: List<E>): Boolean {
        initialize()
        return readOnlyView.containsValue(value)
    }

    override fun get(key: PK): List<E>? {
        initialize()
        return readOnlyView[key]
    }

    override fun isEmpty(): Boolean {
        initialize()
        return readOnlyView.isEmpty()
    }

    /**
     * Returns `this` projection map, initializing the source state on the first call.
     *
     * Implements Kotlin `by`-delegation: `val grouped by projectionMap(::items) { it.key }`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): ProjectionMap<K, PK, E> {
        initialize()
        return this
    }
}
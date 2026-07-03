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
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.FxObservableCollection
import net.transgressoft.lirp.persistence.MutableAggregateList
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KProperty
import kotlinx.coroutines.launch

/**
 * JavaFX-observable list that wraps a [MutableAggregateList] and implements both
 * [ObservableList] and [AggregateCollectionRef].
 *
 * Mutations to this list fire [ListChangeListener.Change] notifications automatically.
 * Single-element operations produce one Change each; batch operations (`addAll`, `removeAll`,
 * `retainAll`, `clear`) produce a single Change per batch, matching standard JavaFX
 * `ObservableList` semantics.
 *
 * When [dispatchToFxThread] is `true` (the default), listener notifications are automatically
 * dispatched to the JavaFX Application Thread via [Platform.runLater] if the mutation occurs
 * on a background thread. When `false`, listeners fire asynchronously on [ReactiveScope.flowScope],
 * consistent with how lirp events are dispatched.
 *
 * When [lazySnapshot] is `true`, the local element cache (`localElements`) is never populated.
 * Instead, all structural access (`size`, `get`, iteration) delegates to [innerProxy], which
 * resolves entities through the registry on demand. This eliminates the memory duplication of
 * maintaining a parallel entity reference list, making it suitable for large (10k+) collections.
 * Precondition: lazy-snapshot mode requires registry binding before any structural access;
 * attempting `get(index)` before the registry is bound throws [NoSuchElementException].
 *
 * @param K the entity ID type
 * @param E the entity type
 * @param innerProxy the wrapped lirp mutable aggregate list
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 * @param lazySnapshot when `true`, structural access resolves from the registry on demand instead of
 *   maintaining a local element cache; reduces memory for large collections; defaults to `false`
 */
class FxAggregateList<K : Comparable<K>, E : IdentifiableEntity<K>>(
    val innerProxy: MutableAggregateList<K, E>,
    val dispatchToFxThread: Boolean = true,
    val lazySnapshot: Boolean = false
) : AbstractMutableList<E>(), ObservableList<E>, AggregateCollectionRef<K, E> by innerProxy, FxObservableCollection<K, E> {

    override val innerMutableProxy: Any get() = innerProxy

    private val listChangeListeners = CopyOnWriteArrayList<ListChangeListener<in E>>()
    private val invalidationListeners = CopyOnWriteArrayList<InvalidationListener>()

    // Local element cache maintained in parallel with the inner proxy's backing IDs.
    // Enables snapshotting for JavaFX Change notifications without requiring registry resolution.
    // When lazySnapshot is true, this is never populated (zero allocation placeholder).
    private val localElements = if (lazySnapshot) ArrayList(0) else ArrayList<E>()

    // Guards every read and write of localElements. The listener cascade may read this list
    // on the FX thread while a mutation runs on flowScope (see fireChange's mixed dispatch), so
    // a defensive copy such as ArrayList(localElements) must be atomic with respect to structural
    // modification — otherwise the array construction races the mutation and throws
    // ArrayIndexOutOfBoundsException. fireChange is invoked outside this lock so reentrant listener
    // cascades and cross-thread notification never block on it.
    private val cacheLock = Any()

    override fun syncLocalCache() {
        if (lazySnapshot) return
        synchronized(cacheLock) {
            localElements.clear()
            for (i in 0 until innerProxy.size) {
                localElements.add(innerProxy[i])
            }
        }
    }

    override fun get(index: Int): E =
        if (lazySnapshot) innerProxy[index] else synchronized(cacheLock) { localElements[index] }

    override val size: Int get() = if (lazySnapshot) innerProxy.size else synchronized(cacheLock) { localElements.size }

    /**
     * Returns an iterator over a stable snapshot of the current elements.
     *
     * The default [AbstractMutableList] iterator resolves elements lazily through `get(index)`
     * against the live size, so a concurrent structural modification during iteration can throw
     * [IndexOutOfBoundsException]. Snapshotting under [cacheLock] makes iteration atomic with
     * respect to mutation, mirroring the guarantee provided by the sibling observable set.
     */
    override fun iterator(): MutableIterator<E> {
        val snapshot =
            if (lazySnapshot) ArrayList(innerProxy.resolveAll()) else synchronized(cacheLock) { ArrayList(localElements) }
        return object : MutableIterator<E> {
            private val delegate = snapshot.iterator()
            private var lastReturned: E? = null

            override fun hasNext() = delegate.hasNext()

            override fun next(): E = delegate.next().also { lastReturned = it }

            override fun remove() {
                val element = lastReturned ?: throw IllegalStateException("next() not yet called or already removed")
                this@FxAggregateList.remove(element)
                lastReturned = null
            }
        }
    }

    override fun add(index: Int, element: E) {
        innerProxy.add(index, element)
        if (!lazySnapshot) synchronized(cacheLock) { localElements.add(index, element) }
        modCount++
        fireChange(AddChange(this, index, index + 1))
    }

    override fun set(index: Int, element: E): E {
        // Resolve old element BEFORE any inner proxy mutation to ensure it is still accessible
        val old = if (lazySnapshot) innerProxy[index] else synchronized(cacheLock) { localElements[index] }
        innerProxy.removeAll(listOf(old))
        innerProxy.add(index, element)
        if (!lazySnapshot) synchronized(cacheLock) { localElements[index] = element }
        fireChange(SetChange(this, index, old))
        return old
    }

    override fun removeAt(index: Int): E {
        // In lazy mode, resolve element BEFORE removal; in eager mode, remove from local cache first
        val removed = if (lazySnapshot) innerProxy[index] else synchronized(cacheLock) { localElements.removeAt(index) }
        innerProxy.removeAll(listOf(removed))
        modCount++
        fireChange(RemoveChange(this, index, listOf(removed)))
        return removed
    }

    // Explicit single-element removal. The default AbstractMutableCollection.remove drives removal
    // through iterator().remove(); because iterator() is overridden to return a detached snapshot,
    // that path cannot mutate the backing cache and would recurse into this method. Resolving the
    // index and delegating to removeAt keeps removal correct and snapshot-safe.
    override fun remove(element: E): Boolean {
        val index =
            if (lazySnapshot) {
                innerProxy.referenceIds.indexOf(element.id)
            } else {
                synchronized(cacheLock) { localElements.indexOf(element) }
            }
        if (index < 0) return false
        removeAt(index)
        return true
    }

    override fun addAll(elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        val from = if (lazySnapshot) innerProxy.size else synchronized(cacheLock) { localElements.size }
        val changed = innerProxy.addAll(elements)
        if (changed) {
            if (!lazySnapshot) synchronized(cacheLock) { localElements.addAll(elements) }
            modCount++
            fireChange(AddChange(this, from, from + elements.size))
        }
        return changed
    }

    override fun addAll(index: Int, elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        val changed = innerProxy.addAll(index, elements)
        if (changed) {
            if (!lazySnapshot) synchronized(cacheLock) { localElements.addAll(index, elements) }
            modCount++
            fireChange(AddChange(this, index, index + elements.size))
        }
        return changed
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        if (lazySnapshot) {
            val referenceIds = innerProxy.referenceIds
            val removedEntries =
                elements.mapNotNull { element ->
                    val idx = referenceIds.indexOf(element.id)
                    if (idx >= 0) idx to element else null
                }.sortedBy { it.first }
            if (removedEntries.isEmpty()) return false
            val changed = innerProxy.removeAll(elements)
            if (changed) {
                modCount++
                val adjustedRemovals =
                    removedEntries.mapIndexed { step, (originalIdx, element) ->
                        (originalIdx - step) to element
                    }
                fireChange(MultiRemoveChange(this, adjustedRemovals))
            }
            return changed
        }
        // Build removals from localElements, not from the input collection: iterating the cache once
        // yields each cached index exactly once (in ascending order), so a duplicated input element
        // cannot map the same index twice and corrupt the descending-order removal below.
        val elementsSet = elements.toSet()
        val removedEntries =
            synchronized(cacheLock) {
                localElements.withIndex()
                    .filter { (_, element) -> element in elementsSet }
                    .map { (idx, element) -> idx to element }
            }
        if (removedEntries.isEmpty()) return false

        val changed = innerProxy.removeAll(elements)
        if (changed) {
            // Remove from localElements in descending order to preserve indices during removal
            synchronized(cacheLock) {
                removedEntries.sortedByDescending { it.first }.forEach { (idx, _) -> localElements.removeAt(idx) }
            }
            modCount++
            // Adjust indices for Change: each removal at position i shifts subsequent positions down by 1
            val adjustedRemovals =
                removedEntries.mapIndexed { step, (originalIdx, element) ->
                    (originalIdx - step) to element
                }
            fireChange(MultiRemoveChange(this, adjustedRemovals))
        }
        return changed
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val elementsSet = elements.toSet()
        val toRemove =
            if (lazySnapshot) {
                innerProxy.resolveAll().filter { it !in elementsSet }
            } else {
                synchronized(cacheLock) { localElements.filter { it !in elementsSet } }
            }
        if (toRemove.isEmpty()) return false
        return removeAll(toRemove)
    }

    override fun clear() {
        if (lazySnapshot) {
            if (innerProxy.isEmpty()) return
            val snapshot = innerProxy.resolveAll().toList()
            innerProxy.clear()
            modCount++
            fireChange(RemoveChange(this, 0, snapshot))
        } else {
            val snapshot =
                synchronized(cacheLock) {
                    if (localElements.isEmpty()) return
                    ArrayList(localElements).also { localElements.clear() }
                }
            innerProxy.clear()
            modCount++
            fireChange(RemoveChange(this, 0, snapshot))
        }
    }

    override fun setAll(vararg elements: E): Boolean = setAll(elements.toList())

    override fun setAll(col: Collection<E>): Boolean {
        if (lazySnapshot) {
            val snapshot = innerProxy.resolveAll().toList()
            innerProxy.clear()
            val added = innerProxy.addAll(col)
            if (added || snapshot.isNotEmpty()) {
                modCount++
                fireChange(ReplaceAllChange(this, snapshot, innerProxy.size))
                return true
            }
            return false
        }
        val snapshot = synchronized(cacheLock) { ArrayList(localElements).also { localElements.clear() } }
        innerProxy.clear()
        val added = innerProxy.addAll(col)
        val newSize =
            synchronized(cacheLock) {
                if (added) localElements.addAll(col)
                localElements.size
            }
        if (added || snapshot.isNotEmpty()) {
            modCount++
            fireChange(ReplaceAllChange(this, snapshot, newSize))
            return true
        }
        return false
    }

    override fun addAll(vararg elements: E): Boolean = addAll(elements.toList())

    override fun removeAll(vararg elements: E): Boolean = removeAll(elements.toList())

    override fun retainAll(vararg elements: E): Boolean = retainAll(elements.toList())

    override fun remove(from: Int, to: Int) {
        if (lazySnapshot) {
            val removed = (from until to).map { innerProxy[it] }
            innerProxy.removeAll(removed)
            modCount++
            fireChange(RemoveChange(this, from, removed))
        } else {
            val removed =
                synchronized(cacheLock) {
                    ArrayList(localElements.subList(from, to)).also { localElements.subList(from, to).clear() }
                }
            innerProxy.removeAll(removed)
            modCount++
            fireChange(RemoveChange(this, from, removed))
        }
    }

    override fun addListener(listener: ListChangeListener<in E>) {
        listChangeListeners.add(listener)
    }

    override fun removeListener(listener: ListChangeListener<in E>) {
        listChangeListeners.remove(listener)
    }

    override fun addListener(listener: InvalidationListener) {
        invalidationListeners.add(listener)
    }

    override fun removeListener(listener: InvalidationListener) {
        invalidationListeners.remove(listener)
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): FxAggregateList<K, E> = this

    private fun fireChange(change: ListChangeListener.Change<E>) {
        val notify = {
            listChangeListeners.forEach { it.onChanged(change) }
            invalidationListeners.forEach { it.invalidated(this) }
        }
        if (dispatchToFxThread) {
            if (Platform.isFxApplicationThread()) {
                notify()
            } else {
                Platform.runLater(notify)
            }
        } else {
            ReactiveScope.flowScope.launch { notify() }
        }
    }

    /**
     * Change object representing an addition of elements at a contiguous range.
     */
    private class AddChange<E>(
        list: ObservableList<E>,
        private val from: Int,
        private val to: Int
    ) : ListChangeListener.Change<E>(list) {
        private var invalid = true

        override fun next(): Boolean {
            if (invalid) {
                invalid = false
                return true
            }
            return false
        }

        override fun reset() {
            invalid = true
        }

        override fun getFrom() = from

        override fun getTo() = to

        override fun getRemoved(): List<E> = emptyList()

        override fun getPermutation() = IntArray(0)

        override fun wasAdded() = true
    }

    /**
     * Change object representing a single element replacement.
     */
    private class SetChange<E>(
        list: ObservableList<E>,
        private val index: Int,
        private val oldValue: E
    ) : ListChangeListener.Change<E>(list) {
        private var invalid = true

        override fun next(): Boolean {
            if (invalid) {
                invalid = false
                return true
            }
            return false
        }

        override fun reset() {
            invalid = true
        }

        override fun getFrom() = index

        override fun getTo() = index + 1

        override fun getRemoved(): List<E> = listOf(oldValue)

        override fun getPermutation() = IntArray(0)

        override fun wasReplaced() = true
    }

    /**
     * Change object representing removal of elements from a given index.
     */
    private class RemoveChange<E>(
        list: ObservableList<E>,
        private val from: Int,
        private val removedElements: List<E>
    ) : ListChangeListener.Change<E>(list) {
        private var invalid = true

        override fun next(): Boolean {
            if (invalid) {
                invalid = false
                return true
            }
            return false
        }

        override fun reset() {
            invalid = true
        }

        override fun getFrom() = from

        override fun getTo() = from

        override fun getRemoved(): List<E> = removedElements

        override fun getPermutation() = IntArray(0)

        override fun wasRemoved() = true
    }

    /**
     * Change object representing multiple non-contiguous removals.
     */
    private class MultiRemoveChange<E>(
        list: ObservableList<E>,
        private val removals: List<Pair<Int, E>>
    ) : ListChangeListener.Change<E>(list) {
        private var cursor = -1

        override fun next(): Boolean {
            cursor++
            return cursor < removals.size
        }

        override fun reset() {
            cursor = -1
        }

        override fun getFrom() = removals[cursor].first

        override fun getTo() = removals[cursor].first

        override fun getRemoved(): List<E> = listOf(removals[cursor].second)

        override fun getPermutation() = IntArray(0)

        override fun wasRemoved() = true
    }

    /**
     * Change object representing a full list replacement via setAll.
     */
    private class ReplaceAllChange<E>(
        list: ObservableList<E>,
        private val removedElements: List<E>,
        private val newSize: Int
    ) : ListChangeListener.Change<E>(list) {
        private var invalid = true

        override fun next(): Boolean {
            if (invalid) {
                invalid = false
                return true
            }
            return false
        }

        override fun reset() {
            invalid = true
        }

        override fun getFrom() = 0

        override fun getTo() = newSize

        override fun getRemoved(): List<E> = removedElements

        override fun getPermutation() = IntArray(0)

        override fun wasReplaced() = removedElements.isNotEmpty() && newSize > 0
    }
}
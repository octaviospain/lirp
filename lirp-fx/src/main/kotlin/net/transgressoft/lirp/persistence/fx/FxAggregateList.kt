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
 * @param K the entity ID type
 * @param E the entity type
 * @param innerProxy the wrapped lirp mutable aggregate list
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class FxAggregateList<K : Comparable<K>, E : IdentifiableEntity<K>>(
    val innerProxy: MutableAggregateList<K, E>,
    val dispatchToFxThread: Boolean = true
) : AbstractMutableList<E>(), ObservableList<E>, AggregateCollectionRef<K, E> by innerProxy, FxObservableCollection<K, E> {

    override val innerMutableProxy: Any get() = innerProxy

    private val listChangeListeners = CopyOnWriteArrayList<ListChangeListener<in E>>()
    private val invalidationListeners = CopyOnWriteArrayList<InvalidationListener>()

    // Local element cache maintained in parallel with the inner proxy's backing IDs.
    // Enables snapshotting for JavaFX Change notifications without requiring registry resolution.
    private val localElements = ArrayList<E>()

    override fun syncLocalCache() {
        localElements.clear()
        for (i in 0 until innerProxy.size) {
            localElements.add(innerProxy[i])
        }
    }

    override fun get(index: Int): E = localElements[index]

    override val size: Int get() = localElements.size

    override fun add(index: Int, element: E) {
        innerProxy.add(index, element)
        localElements.add(index, element)
        modCount++
        fireChange(AddChange(this, index, index + 1))
    }

    override fun set(index: Int, element: E): E {
        val old = localElements[index]
        innerProxy.removeAll(listOf(old))
        innerProxy.add(index, element)
        localElements[index] = element
        fireChange(SetChange(this, index, old))
        return old
    }

    override fun removeAt(index: Int): E {
        val removed = localElements.removeAt(index)
        innerProxy.removeAll(listOf(removed))
        modCount++
        fireChange(RemoveChange(this, index, listOf(removed)))
        return removed
    }

    override fun addAll(elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        val from = localElements.size
        val changed = innerProxy.addAll(elements)
        if (changed) {
            localElements.addAll(elements)
            modCount++
            fireChange(AddChange(this, from, localElements.size))
        }
        return changed
    }

    override fun addAll(index: Int, elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        val changed = innerProxy.addAll(index, elements)
        if (changed) {
            localElements.addAll(index, elements)
            modCount++
            fireChange(AddChange(this, index, index + elements.size))
        }
        return changed
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        if (elements.isEmpty()) return false
        val toRemove = elements.filter { localElements.contains(it) }
        if (toRemove.isEmpty()) return false

        val removedEntries =
            toRemove.mapNotNull { element ->
                val idx = localElements.indexOf(element)
                if (idx >= 0) idx to element else null
            }.sortedBy { it.first }

        val changed = innerProxy.removeAll(elements)
        if (changed) {
            // Remove from localElements in descending order to preserve indices during removal
            removedEntries.sortedByDescending { it.first }.forEach { (idx, _) -> localElements.removeAt(idx) }
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
        val toRemove = localElements.filter { it !in elementsSet }
        if (toRemove.isEmpty()) return false
        return removeAll(toRemove)
    }

    override fun clear() {
        if (localElements.isEmpty()) return
        val snapshot = ArrayList(localElements)
        innerProxy.clear()
        localElements.clear()
        modCount++
        fireChange(RemoveChange(this, 0, snapshot))
    }

    override fun setAll(vararg elements: E): Boolean = setAll(elements.toList())

    override fun setAll(col: Collection<E>): Boolean {
        val snapshot = ArrayList(localElements)
        innerProxy.clear()
        localElements.clear()
        val added = innerProxy.addAll(col)
        if (added) localElements.addAll(col)
        if (added || snapshot.isNotEmpty()) {
            modCount++
            fireChange(ReplaceAllChange(this, snapshot, localElements.size))
            return true
        }
        return false
    }

    override fun addAll(vararg elements: E): Boolean = addAll(elements.toList())

    override fun removeAll(vararg elements: E): Boolean = removeAll(elements.toList())

    override fun retainAll(vararg elements: E): Boolean = retainAll(elements.toList())

    override fun remove(from: Int, to: Int) {
        val removed = ArrayList(localElements.subList(from, to))
        innerProxy.removeAll(removed)
        localElements.subList(from, to).clear()
        modCount++
        fireChange(RemoveChange(this, from, removed))
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
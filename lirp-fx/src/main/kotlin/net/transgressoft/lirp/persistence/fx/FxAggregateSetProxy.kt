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
import net.transgressoft.lirp.persistence.FxObservableCollectionProxy
import net.transgressoft.lirp.persistence.MutableAggregateSetProxy
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.ObservableSet
import javafx.collections.SetChangeListener
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KProperty
import kotlinx.coroutines.launch

/**
 * JavaFX-observable proxy that wraps a [MutableAggregateSetProxy] and implements both
 * [ObservableSet] and [AggregateCollectionRef].
 *
 * Mutations to this set fire [SetChangeListener.Change] notifications automatically.
 * Per the JavaFX [SetChangeListener] contract, each element mutation fires exactly one
 * [SetChangeListener.Change] event: `addAll([a, b, c])` produces three separate Change
 * notifications (one per element).
 *
 * When [dispatchToFxThread] is `true` (the default), listener notifications are automatically
 * dispatched to the JavaFX Application Thread via [Platform.runLater] if the mutation occurs
 * on a background thread. When `false`, listeners fire asynchronously on [ReactiveScope.flowScope],
 * consistent with how lirp events are dispatched.
 *
 * @param K the entity ID type
 * @param E the entity type
 * @param innerProxy the wrapped lirp mutable aggregate set proxy
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 */
class FxAggregateSetProxy<K : Comparable<K>, E : IdentifiableEntity<K>>(
    val innerProxy: MutableAggregateSetProxy<K, E>,
    val dispatchToFxThread: Boolean = true
) : AbstractMutableSet<E>(), ObservableSet<E>, AggregateCollectionRef<K, E> by innerProxy, FxObservableCollectionProxy {

    override val innerMutableProxy: Any get() = innerProxy

    private val invalidationListeners = CopyOnWriteArrayList<InvalidationListener>()
    private val setChangeListeners = CopyOnWriteArrayList<SetChangeListener<in E>>()

    // Local element cache maintained in parallel with the inner proxy's backing IDs.
    // Enables iteration and snapshotting without requiring registry resolution.
    private val localElements = LinkedHashSet<E>()

    override val size: Int get() = localElements.size

    override fun iterator(): MutableIterator<E> {
        val snapshot = ArrayList(localElements)
        return object : MutableIterator<E> {
            private val delegate = snapshot.iterator()
            private var lastReturned: E? = null

            override fun hasNext() = delegate.hasNext()

            override fun next(): E = delegate.next().also { lastReturned = it }

            override fun remove() {
                val element = lastReturned ?: throw IllegalStateException("next() not yet called or already removed")
                this@FxAggregateSetProxy.remove(element)
                lastReturned = null
            }
        }
    }

    override fun contains(element: E): Boolean = element in localElements

    override fun add(element: E): Boolean {
        val changed = innerProxy.add(element)
        if (changed) {
            localElements.add(element)
            fireAdded(element)
        }
        return changed
    }

    override fun remove(element: E): Boolean {
        val changed = innerProxy.remove(element)
        if (changed) {
            localElements.remove(element)
            fireRemoved(element)
        }
        return changed
    }

    override fun addAll(elements: Collection<E>): Boolean {
        var anyChanged = false
        for (element in elements) {
            val changed = innerProxy.add(element)
            if (changed) {
                localElements.add(element)
                fireAdded(element)
                anyChanged = true
            }
        }
        return anyChanged
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        var anyChanged = false
        for (element in elements) {
            val changed = innerProxy.remove(element)
            if (changed) {
                localElements.remove(element)
                fireRemoved(element)
                anyChanged = true
            }
        }
        return anyChanged
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val toRemove = localElements.filter { it !in elements }
        if (toRemove.isEmpty()) return false
        return removeAll(toRemove)
    }

    override fun clear() {
        val snapshot = ArrayList(localElements)
        if (snapshot.isEmpty()) return
        innerProxy.clear()
        localElements.clear()
        for (element in snapshot) {
            fireRemoved(element)
        }
    }

    override fun addListener(listener: InvalidationListener) {
        invalidationListeners.add(listener)
    }

    override fun removeListener(listener: InvalidationListener) {
        invalidationListeners.remove(listener)
    }

    override fun addListener(listener: SetChangeListener<in E>) {
        setChangeListeners.add(listener)
    }

    override fun removeListener(listener: SetChangeListener<in E>) {
        setChangeListeners.remove(listener)
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): FxAggregateSetProxy<K, E> = this

    private fun fireChange(change: SetChangeListener.Change<E>) {
        val notify = {
            setChangeListeners.forEach { it.onChanged(change) }
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

    private fun fireAdded(element: E) {
        val change =
            object : SetChangeListener.Change<E>(this) {
                override fun wasAdded() = true

                override fun wasRemoved() = false

                override fun getElementAdded() = element

                override fun getElementRemoved(): E = throw UnsupportedOperationException("No element removed")
            }
        fireChange(change)
    }

    private fun fireRemoved(element: E) {
        val change =
            object : SetChangeListener.Change<E>(this) {
                override fun wasAdded() = false

                override fun wasRemoved() = true

                override fun getElementAdded(): E = throw UnsupportedOperationException("No element added")

                override fun getElementRemoved() = element
            }
        fireChange(change)
    }
}
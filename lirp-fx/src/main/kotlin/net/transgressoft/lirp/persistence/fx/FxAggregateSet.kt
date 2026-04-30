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
import net.transgressoft.lirp.persistence.MutableAggregateSet
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.ObservableSet
import javafx.collections.SetChangeListener
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KProperty
import kotlinx.coroutines.launch

/**
 * JavaFX-observable set that wraps a [MutableAggregateSet] and implements both
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
 * When [lazySnapshot] is `true`, the local element cache (`localElements`) is never populated.
 * Instead, all structural access (`size`, `iterator`, `contains`) delegates to [innerProxy], which
 * resolves entities through the registry on demand. This eliminates the memory duplication of
 * maintaining a parallel entity reference set, making it suitable for large (10k+) collections.
 * Precondition: lazy-snapshot mode requires registry binding before any structural access;
 * attempting iteration before the registry is bound throws [NoSuchElementException].
 *
 * @param K the entity ID type
 * @param E the entity type
 * @param innerProxy the wrapped lirp mutable aggregate set
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 * @param lazySnapshot when `true`, structural access resolves from the registry on demand instead of
 *   maintaining a local element cache; reduces memory for large collections; defaults to `false`
 */
class FxAggregateSet<K : Comparable<K>, E : IdentifiableEntity<K>>(
    val innerProxy: MutableAggregateSet<K, E>,
    val dispatchToFxThread: Boolean = true,
    val lazySnapshot: Boolean = false
) : AbstractMutableSet<E>(), ObservableSet<E>, AggregateCollectionRef<K, E> by innerProxy, FxObservableCollection<K, E> {

    override val innerMutableProxy: Any get() = innerProxy

    private val invalidationListeners = CopyOnWriteArrayList<InvalidationListener>()
    private val setChangeListeners = CopyOnWriteArrayList<SetChangeListener<in E>>()

    // Local element cache maintained in parallel with the inner proxy's backing IDs.
    // Enables iteration and snapshotting without requiring registry resolution.
    // When lazySnapshot is true, this is never populated (zero allocation placeholder).
    private val localElements = if (lazySnapshot) LinkedHashSet<E>(0) else LinkedHashSet<E>()

    override fun syncLocalCache() {
        if (lazySnapshot) return
        localElements.clear()
        localElements.addAll(innerProxy.resolveAll())
    }

    override val size: Int get() = if (lazySnapshot) innerProxy.size else localElements.size

    override fun iterator(): MutableIterator<E> {
        if (lazySnapshot) {
            val snapshot = ArrayList(innerProxy.resolveAll())
            return object : MutableIterator<E> {
                private val delegate = snapshot.iterator()
                private var lastReturned: E? = null

                override fun hasNext() = delegate.hasNext()

                override fun next(): E = delegate.next().also { lastReturned = it }

                override fun remove() {
                    val element = lastReturned ?: throw IllegalStateException("next() not yet called or already removed")
                    this@FxAggregateSet.remove(element)
                    lastReturned = null
                }
            }
        }
        val snapshot = ArrayList(localElements)
        return object : MutableIterator<E> {
            private val delegate = snapshot.iterator()
            private var lastReturned: E? = null

            override fun hasNext() = delegate.hasNext()

            override fun next(): E = delegate.next().also { lastReturned = it }

            override fun remove() {
                val element = lastReturned ?: throw IllegalStateException("next() not yet called or already removed")
                this@FxAggregateSet.remove(element)
                lastReturned = null
            }
        }
    }

    override fun contains(element: E): Boolean = if (lazySnapshot) innerProxy.contains(element) else element in localElements

    override fun add(element: E): Boolean {
        val changed = innerProxy.add(element)
        if (changed) {
            if (!lazySnapshot) localElements.add(element)
            fireAdded(element)
        }
        return changed
    }

    override fun remove(element: E): Boolean {
        val changed = innerProxy.remove(element)
        if (changed) {
            if (!lazySnapshot) localElements.remove(element)
            fireRemoved(element)
        }
        return changed
    }

    override fun addAll(elements: Collection<E>): Boolean {
        var anyChanged = false
        for (element in elements) {
            val changed = innerProxy.add(element)
            if (changed) {
                if (!lazySnapshot) localElements.add(element)
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
                if (!lazySnapshot) localElements.remove(element)
                fireRemoved(element)
                anyChanged = true
            }
        }
        return anyChanged
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        val toRemove = if (lazySnapshot) innerProxy.resolveAll().filter { it !in elements } else localElements.filter { it !in elements }
        if (toRemove.isEmpty()) return false
        return removeAll(toRemove)
    }

    override fun clear() {
        if (lazySnapshot) {
            val snapshot = ArrayList(innerProxy.resolveAll())
            if (snapshot.isEmpty()) return
            innerProxy.clear()
            for (element in snapshot) {
                fireRemoved(element)
            }
        } else {
            val snapshot = ArrayList(localElements)
            if (snapshot.isEmpty()) return
            innerProxy.clear()
            localElements.clear()
            for (element in snapshot) {
                fireRemoved(element)
            }
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

    operator fun getValue(thisRef: Any?, property: KProperty<*>): FxAggregateSet<K, E> = this

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
        // Returns null (not throws) from getElementRemoved on a pure addition. JavaFX's
        // SetExpressionHelper$SimpleChange copy constructor unconditionally reads both
        // sides when forwarding a change to listeners attached to a SimpleSetProperty
        // wrapping this set; throwing here crashed the FX thread with
        // `UnsupportedOperationException: No element removed`.
        val change =
            object : SetChangeListener.Change<E>(this) {
                override fun wasAdded() = true

                override fun wasRemoved() = false

                override fun getElementAdded() = element

                override fun getElementRemoved(): E? = null
            }
        fireChange(change)
    }

    private fun fireRemoved(element: E) {
        // Mirror of fireAdded — returns null from getElementAdded on a pure removal so
        // SetExpressionHelper$SimpleChange can copy the change without throwing.
        val change =
            object : SetChangeListener.Change<E>(this) {
                override fun wasAdded() = false

                override fun wasRemoved() = true

                override fun getElementAdded(): E? = null

                override fun getElementRemoved() = element
            }
        fireChange(change)
    }
}
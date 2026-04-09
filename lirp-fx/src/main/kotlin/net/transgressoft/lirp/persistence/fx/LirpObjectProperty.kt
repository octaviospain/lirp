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

import net.transgressoft.lirp.persistence.FxScalarPropertyDelegate
import net.transgressoft.lirp.persistence.LirpDelegate
import javafx.beans.property.SimpleObjectProperty
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty

/**
 * JavaFX [SimpleObjectProperty] delegate that participates in lirp's reactive mutation event system.
 *
 * Supports nullable type parameters, making it suitable for optional object-typed properties.
 * When registered in a [net.transgressoft.lirp.entity.ReactiveEntityBase] subclass and wired by
 * RegistryBase, each call to [set] emits a [net.transgressoft.lirp.event.ReactiveMutationEvent]
 * using the clone-before-mutation pattern. Use [fxObject] to create instances as property delegates.
 *
 * @param T the type of the wrapped object; nullable (`T?`) is supported
 * @param initialValue the initial value; defaults to `null`
 * @param dispatchToFxThread when `true` (default), RegistryBase may dispatch notifications to the
 *   JavaFX Application Thread; when `false`, dispatches on
 *   [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @see FxScalarPropertyDelegate
 * @see fxObject
 */
class LirpObjectProperty<T>(initialValue: T? = null, val dispatchToFxThread: Boolean = true) :
    SimpleObjectProperty<T?>(initialValue),
    LirpDelegate,
    FxScalarPropertyDelegate {

    private val mutationCallback = AtomicReference<((() -> Unit) -> Unit)?>(null)

    override fun bindMutationCallback(callback: (() -> Unit) -> Unit) {
        check(mutationCallback.compareAndSet(null, callback)) {
            "Mutation callback already bound. FxScalarPropertyDelegate supports a single binding."
        }
    }

    override fun set(newValue: T?) {
        if (isBound) {
            super.set(newValue)
            return
        }
        if (get() === newValue) return
        val callback = mutationCallback.get()
        if (callback != null) {
            callback { super.set(newValue) }
        } else {
            super.set(newValue)
        }
    }

    /** Returns this instance for use as a Kotlin property delegate via `by`. */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): LirpObjectProperty<T> = this
}
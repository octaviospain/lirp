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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Reactive property delegate that stores a value in its own backing field and emits a
 * [net.transgressoft.lirp.event.ReactiveMutationEvent] on assignment when the value changes.
 *
 * Promoted from a private inner class of [ReactiveEntityBase] to a top-level `internal` class so
 * KSP-generated code can write its backing field directly via [writeBackingDirectly] without
 * incurring reflection. The owning entity is injected via constructor and used to delegate the
 * emit/clone/timestamp control flow to [ReactiveEntityBase.emitReactiveMutation].
 *
 * @param T the property value type
 */
internal class ReactivePropertyDelegate<T>(
    private val entity: ReactiveEntityBase<*, *>,
    initialValue: T
) : LirpDelegate, ReadWriteProperty<Any?, T> {

    internal var storedValue: T = initialValue

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = storedValue

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        check(!entity.isClosed) { "Entity '${entity::class.java.simpleName}' is closed" }
        if (value != storedValue) {
            entity.emitReactiveMutation { storedValue = value }
        }
    }

    /**
     * Writes [value] into the backing field without triggering event emission, the clone
     * comparison, the lastDateModified bump, or any other reactive side effect.
     *
     * Consumed by KSP-generated accessors to bulk-init entities from persisted rows.
     */
    internal fun writeBackingDirectly(value: T) {
        storedValue = value
    }
}

/**
 * Reactive property delegate backed by external getter/setter lambdas, used for `@Transient`
 * properties in `@Serializable` entities where the actual value lives in a constructor parameter.
 *
 * Mirrors [ReactivePropertyDelegate] semantics, but reads/writes through the supplied [getter] and
 * [setter] rather than an internal field. [writeBackingDirectly] resolves to a plain setter call,
 * which IS the backing-write path for these properties.
 *
 * @param T the property value type
 */
internal class ReactivePropertyDelegateWithAccessors<T>(
    private val entity: ReactiveEntityBase<*, *>,
    private val getter: () -> T,
    private val setter: (T) -> Unit
) : LirpDelegate, ReadWriteProperty<Any?, T> {

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = getter()

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        check(!entity.isClosed) { "Entity '${entity::class.java.simpleName}' is closed" }
        if (value != getter()) {
            entity.emitReactiveMutation { setter(value) }
        }
    }

    /**
     * Writes [value] through the supplied [setter] without triggering event emission, the clone
     * comparison, the lastDateModified bump, or any other reactive side effect. For
     * `@Transient`-backed reactive properties, the setter lambda is the backing-write path.
     */
    internal fun writeBackingDirectly(value: T) {
        setter(value)
    }
}

/**
 * Writes [value] directly into the backing field of the reactive-property delegate registered
 * on [entity] under [propertyName], bypassing event emission, lastDateModified updates, and
 * clone-based change detection.
 *
 * Consumed by KSP-generated `LirpReactivePropertyAccessor` and `LirpRawInitializer` implementations
 * to bulk-load entities from persisted rows without triggering the reactive pipeline. The
 * delegate is resolved through the entity's lazy delegateRegistry; first call per entity pays a
 * single kotlin-reflect scan, subsequent calls are pure HashMap lookups.
 *
 * Throws if no delegate is registered under [propertyName], or if the registered delegate is
 * not a reactive-property delegate — both indicate misconfigured generated code, not a
 * recoverable condition.
 *
 * **This is API consumed by generated code — not intended for direct use by application code.**
 */
fun <T> writeReactivePropertyBackingField(
    entity: net.transgressoft.lirp.entity.ReactiveEntityBase<*, *>,
    propertyName: String,
    value: T
) {
    val delegate =
        entity.delegateRegistry[propertyName]
            ?: error("No delegate registered for property '$propertyName' on ${entity::class.qualifiedName}")
    when (delegate) {
        is ReactivePropertyDelegate<*> -> {
            @Suppress("UNCHECKED_CAST")
            (delegate as ReactivePropertyDelegate<T>).writeBackingDirectly(value)
        }
        is ReactivePropertyDelegateWithAccessors<*> -> {
            @Suppress("UNCHECKED_CAST")
            (delegate as ReactivePropertyDelegateWithAccessors<T>).writeBackingDirectly(value)
        }
        else -> error("Delegate registered under '$propertyName' is not a reactive-property delegate: ${delegate::class}")
    }
}
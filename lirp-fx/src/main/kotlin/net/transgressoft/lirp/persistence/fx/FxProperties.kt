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
import net.transgressoft.lirp.persistence.mutableAggregateList
import net.transgressoft.lirp.persistence.mutableAggregateSet

/**
 * Java-friendly static factory methods for all lirp-fx delegate types.
 *
 * Kotlin callers prefer the top-level `fxString()`, `fxInteger()`, `fxAggregateList()` etc. functions.
 * Java callers use this object's `@JvmStatic` methods to avoid Kotlin top-level function call syntax.
 *
 * Method names mirror the top-level Kotlin factories with the `fx` prefix, so both Kotlin and Java
 * callers use a consistent naming convention: `FxProperties.fxString(...)`, `FxProperties.fxDouble(...)`.
 */
object FxProperties {

    /** Creates a [LirpStringProperty] with the given initial value. */
    @JvmStatic
    @JvmOverloads
    fun fxString(initialValue: String = "", dispatchToFxThread: Boolean = true): LirpStringProperty =
        LirpStringProperty(initialValue, dispatchToFxThread)

    /** Creates a [LirpIntegerProperty] with the given initial value. */
    @JvmStatic
    @JvmOverloads
    fun fxInteger(initialValue: Int = 0, dispatchToFxThread: Boolean = true): LirpIntegerProperty =
        LirpIntegerProperty(initialValue, dispatchToFxThread)

    /** Creates a [LirpDoubleProperty] with the given initial value. */
    @JvmStatic
    @JvmOverloads
    fun fxDouble(initialValue: Double = 0.0, dispatchToFxThread: Boolean = true): LirpDoubleProperty =
        LirpDoubleProperty(initialValue, dispatchToFxThread)

    /** Creates a [LirpFloatProperty] with the given initial value. */
    @JvmStatic
    @JvmOverloads
    fun fxFloat(initialValue: Float = 0.0f, dispatchToFxThread: Boolean = true): LirpFloatProperty =
        LirpFloatProperty(initialValue, dispatchToFxThread)

    /** Creates a [LirpLongProperty] with the given initial value. */
    @JvmStatic
    @JvmOverloads
    fun fxLong(initialValue: Long = 0L, dispatchToFxThread: Boolean = true): LirpLongProperty =
        LirpLongProperty(initialValue, dispatchToFxThread)

    /** Creates a [LirpBooleanProperty] with the given initial value. */
    @JvmStatic
    @JvmOverloads
    fun fxBoolean(initialValue: Boolean = false, dispatchToFxThread: Boolean = true): LirpBooleanProperty =
        LirpBooleanProperty(initialValue, dispatchToFxThread)

    /** Creates a [LirpObjectProperty] with the given nullable initial value. */
    @JvmStatic
    @JvmOverloads
    fun <T> fxObject(initialValue: T? = null, dispatchToFxThread: Boolean = true): LirpObjectProperty<T> =
        LirpObjectProperty(initialValue, dispatchToFxThread)

    /** Creates an [FxAggregateListProxy] for a JavaFX-observable mutable ordered aggregate collection. */
    @JvmStatic
    @JvmOverloads
    fun <K : Comparable<K>, E : IdentifiableEntity<K>> fxAggregateList(
        initialIds: List<K> = emptyList(),
        dispatchToFxThread: Boolean = true
    ): FxAggregateListProxy<K, E> = FxAggregateListProxy(mutableAggregateList(initialIds), dispatchToFxThread)

    /** Creates an [FxAggregateSetProxy] for a JavaFX-observable mutable unique-set aggregate collection. */
    @JvmStatic
    @JvmOverloads
    fun <K : Comparable<K>, E : IdentifiableEntity<K>> fxAggregateSet(
        initialIds: Set<K> = emptySet(),
        dispatchToFxThread: Boolean = true
    ): FxAggregateSetProxy<K, E> = FxAggregateSetProxy(mutableAggregateSet(initialIds), dispatchToFxThread)
}
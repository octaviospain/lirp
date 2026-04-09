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
 * Creates a property delegate for a JavaFX-observable mutable ordered aggregate collection reference.
 *
 * Returns an [FxAggregateListProxy] that implements both [javafx.collections.ObservableList] and
 * lirp's [net.transgressoft.lirp.persistence.AggregateCollectionRef]. Mutations fire
 * [javafx.collections.ListChangeListener.Change] notifications automatically.
 *
 * @param K the entity ID type
 * @param E the entity type
 * @param initialIds initial referenced entity IDs
 * @param dispatchToFxThread when `true` (default), dispatches listener notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an ObservableList property delegate backed by a lirp mutable aggregate list
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> fxAggregateList(
    initialIds: List<K> = emptyList(),
    dispatchToFxThread: Boolean = true
): FxAggregateListProxy<K, E> =
    FxAggregateListProxy(mutableAggregateList(initialIds), dispatchToFxThread)

/**
 * Creates a property delegate for a JavaFX-observable mutable unique-set aggregate collection reference.
 *
 * Returns an [FxAggregateSetProxy] that implements both [javafx.collections.ObservableSet] and
 * lirp's [net.transgressoft.lirp.persistence.AggregateCollectionRef]. Mutations fire
 * [javafx.collections.SetChangeListener.Change] notifications automatically, with one Change
 * per element per the JavaFX [javafx.collections.SetChangeListener] contract.
 *
 * @param K the entity ID type
 * @param E the entity type
 * @param initialIds initial referenced entity IDs
 * @param dispatchToFxThread when `true` (default), dispatches listener notifications to the FX Application Thread;
 *   when `false`, dispatches on [net.transgressoft.lirp.event.ReactiveScope.flowScope]
 * @return an ObservableSet property delegate backed by a lirp mutable aggregate set
 */
fun <K : Comparable<K>, E : IdentifiableEntity<K>> fxAggregateSet(
    initialIds: Set<K> = emptySet(),
    dispatchToFxThread: Boolean = true
): FxAggregateSetProxy<K, E> =
    FxAggregateSetProxy(mutableAggregateSet(initialIds), dispatchToFxThread)

/**
 * Creates a [LirpStringProperty] delegate with an optional initial value.
 *
 * @param initialValue the initial string value; defaults to `""`
 * @param dispatchToFxThread when `true` (default), RegistryBase dispatches mutation notifications
 *   to the JavaFX Application Thread
 */
fun fxString(initialValue: String = "", dispatchToFxThread: Boolean = true): LirpStringProperty =
    LirpStringProperty(initialValue, dispatchToFxThread)

/**
 * Creates a [LirpIntegerProperty] delegate with an optional initial value.
 *
 * @param initialValue the initial integer value; defaults to `0`
 * @param dispatchToFxThread when `true` (default), RegistryBase dispatches mutation notifications
 *   to the JavaFX Application Thread
 */
fun fxInteger(initialValue: Int = 0, dispatchToFxThread: Boolean = true): LirpIntegerProperty =
    LirpIntegerProperty(initialValue, dispatchToFxThread)

/**
 * Creates a [LirpDoubleProperty] delegate with an optional initial value.
 *
 * @param initialValue the initial double value; defaults to `0.0`
 * @param dispatchToFxThread when `true` (default), RegistryBase dispatches mutation notifications
 *   to the JavaFX Application Thread
 */
fun fxDouble(initialValue: Double = 0.0, dispatchToFxThread: Boolean = true): LirpDoubleProperty =
    LirpDoubleProperty(initialValue, dispatchToFxThread)

/**
 * Creates a [LirpFloatProperty] delegate with an optional initial value.
 *
 * @param initialValue the initial float value; defaults to `0.0f`
 * @param dispatchToFxThread when `true` (default), RegistryBase dispatches mutation notifications
 *   to the JavaFX Application Thread
 */
fun fxFloat(initialValue: Float = 0.0f, dispatchToFxThread: Boolean = true): LirpFloatProperty =
    LirpFloatProperty(initialValue, dispatchToFxThread)

/**
 * Creates a [LirpLongProperty] delegate with an optional initial value.
 *
 * @param initialValue the initial long value; defaults to `0L`
 * @param dispatchToFxThread when `true` (default), RegistryBase dispatches mutation notifications
 *   to the JavaFX Application Thread
 */
fun fxLong(initialValue: Long = 0L, dispatchToFxThread: Boolean = true): LirpLongProperty =
    LirpLongProperty(initialValue, dispatchToFxThread)

/**
 * Creates a [LirpBooleanProperty] delegate with an optional initial value.
 *
 * @param initialValue the initial boolean value; defaults to `false`
 * @param dispatchToFxThread when `true` (default), RegistryBase dispatches mutation notifications
 *   to the JavaFX Application Thread
 */
fun fxBoolean(initialValue: Boolean = false, dispatchToFxThread: Boolean = true): LirpBooleanProperty =
    LirpBooleanProperty(initialValue, dispatchToFxThread)

/**
 * Creates a [LirpObjectProperty] delegate with an optional nullable initial value.
 *
 * @param T the type of the wrapped object; nullable is supported
 * @param initialValue the initial value; defaults to `null`
 * @param dispatchToFxThread when `true` (default), RegistryBase dispatches mutation notifications
 *   to the JavaFX Application Thread
 */
fun <T> fxObject(initialValue: T? = null, dispatchToFxThread: Boolean = true): LirpObjectProperty<T> =
    LirpObjectProperty(initialValue, dispatchToFxThread)
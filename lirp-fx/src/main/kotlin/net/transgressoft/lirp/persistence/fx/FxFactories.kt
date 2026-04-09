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
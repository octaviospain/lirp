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

package net.transgressoft.lirp.entity

import net.transgressoft.lirp.event.AggregateMutationEvent
import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.ReactiveMutationEvent
import java.util.function.Consumer

// Extension functions providing a 3-tier subscription API for ReactiveEntity:
// - subscribe — all events (property mutations, collection changes, bubble-up)
// - subscribeToMutations — direct property mutation events only (ReactiveMutationEvent)
// - subscribeToCollectionChanges — collection diff events only (CollectionChangeEvent), optionally filtered by property name
// Java consumers can use the Consumer-based overloads, callable as static methods on CollectionChangeEventExtensionsKt.

/**
 * Subscribes to collection change events on this entity, optionally filtered by collection property name.
 *
 * Filters the entity's event stream to [AggregateMutationEvent] instances whose [childEvent][AggregateMutationEvent.childEvent]
 * is a [CollectionChangeEvent]. When [refName] is provided, only events for that specific collection are delivered.
 *
 * @param refName optional collection property name to filter on (e.g., "audioItems"). If null, receives events from all collections.
 * @param action the suspend function invoked for each matching collection change event
 * @return a subscription handle that can be cancelled to stop receiving events
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>> ReactiveEntity<K, R>.subscribeToCollectionChanges(
    refName: String? = null,
    action: suspend (CollectionChangeEvent<*>) -> Unit
): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
    subscribe { event ->
        if (event is AggregateMutationEvent<*, *> &&
            event.childEvent is CollectionChangeEvent<*> &&
            (refName == null || event.refName == refName)
        ) {
            @Suppress("UNCHECKED_CAST")
            action(event.childEvent as CollectionChangeEvent<*>)
        }
    }

/**
 * Java-friendly overload of [subscribeToCollectionChanges] using a [Consumer] callback.
 *
 * @param refName optional collection property name to filter on. Pass null for all collections.
 * @param action the consumer invoked for each matching collection change event
 * @return a subscription handle that can be cancelled to stop receiving events
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>> ReactiveEntity<K, R>.subscribeToCollectionChanges(
    refName: String?,
    action: Consumer<CollectionChangeEvent<*>>
): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
    subscribeToCollectionChanges(refName) { event -> action.accept(event) }

/**
 * Subscribes to direct property mutation events on this entity, excluding aggregate/collection events.
 *
 * Filters the entity's event stream to [ReactiveMutationEvent] instances only, excluding
 * [AggregateMutationEvent] (which wraps bubble-up or collection change events).
 *
 * @param action the suspend function invoked for each property mutation event
 * @return a subscription handle that can be cancelled to stop receiving events
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>> ReactiveEntity<K, R>.subscribeToMutations(
    action: suspend (ReactiveMutationEvent<K, R>) -> Unit
): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
    subscribe { event ->
        if (event is ReactiveMutationEvent<*, *>) {
            @Suppress("UNCHECKED_CAST")
            action(event as ReactiveMutationEvent<K, R>)
        }
    }

/**
 * Java-friendly overload of [subscribeToMutations] using a [Consumer] callback.
 *
 * @param action the consumer invoked for each property mutation event
 * @return a subscription handle that can be cancelled to stop receiving events
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>> ReactiveEntity<K, R>.subscribeToMutations(
    action: Consumer<ReactiveMutationEvent<K, R>>
): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
    subscribeToMutations { event -> action.accept(event) }
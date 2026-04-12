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
import kotlin.reflect.KClass

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
 * Only events whose elements are instances of [elementType] are delivered, providing runtime type safety.
 *
 * ```kotlin
 * playlist.subscribeToCollectionChanges(AudioItem::class, "audioItems") { event ->
 *     for (added in event.added) { /* added is AudioItem — no cast needed */ }
 * }
 *
 * playlist.subscribeToCollectionChanges(MutableAudioPlaylist::class, "playlists") { event ->
 *     for (added in event.added) { /* added is MutableAudioPlaylist */ }
 * }
 * ```
 *
 * @param E the element type of the collection change event
 * @param elementType the element class for type-safe event delivery and runtime filtering
 * @param refName optional collection property name to filter on (e.g., "audioItems"). If null, receives events from all collections.
 * @param action the suspend function invoked for each matching collection change event
 * @return a subscription handle that can be cancelled to stop receiving events
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>, E : Any> ReactiveEntity<K, R>.subscribeToCollectionChanges(
    elementType: KClass<E>,
    refName: String? = null,
    action: suspend (CollectionChangeEvent<E>) -> Unit
): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
    subscribe { event ->
        if (event is AggregateMutationEvent<*, *> &&
            event.childEvent is CollectionChangeEvent<*> &&
            (refName == null || event.refName == refName)
        ) {
            val child = event.childEvent as CollectionChangeEvent<*>
            val matchesElementType =
                child.added.all { elementType.isInstance(it) } &&
                    child.removed.all { elementType.isInstance(it) }
            if (matchesElementType) {
                @Suppress("UNCHECKED_CAST") // Safe: both added and removed verified via isInstance() above
                action(child as CollectionChangeEvent<E>)
            }
        }
    }

/**
 * Java-friendly overload of [subscribeToCollectionChanges] using a [Consumer] callback and [Class] instead of [KClass].
 *
 * ```java
 * CollectionChangeEventExtensionsKt.subscribeToCollectionChanges(playlist, AudioItem.class, "audioItems",
 *     event -> { for (AudioItem added : event.getAdded()) { ... } });
 * ```
 *
 * @param E the element type of the collection change event
 * @param elementType the Java class for type-safe event delivery and runtime filtering
 * @param refName optional collection property name to filter on. Pass null for all collections.
 * @param action the consumer invoked for each matching collection change event
 * @return a subscription handle that can be cancelled to stop receiving events
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>, E : Any> ReactiveEntity<K, R>.subscribeToCollectionChanges(
    elementType: Class<E>,
    refName: String?,
    action: Consumer<CollectionChangeEvent<E>>
): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
    subscribeToCollectionChanges(elementType.kotlin, refName) { event -> action.accept(event) }

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
            // Safe: the event is verified to be ReactiveMutationEvent<*, *> by the enclosing is-check.
            // K and R are bound by the receiver's ReactiveEntity<K, R> type, so the cast is type-safe.
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
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

package net.transgressoft.lirp.event

import net.transgressoft.lirp.entity.TransEntity
import java.util.concurrent.Flow
import java.util.function.Consumer

/**
 * A subscriber to [TransEvent]s that implements the reactive streams [Flow.Subscriber] interface
 * with additional capabilities for registering event-specific actions.
 *
 * This interface defines a subscriber that can react to different event types with
 * custom actions, providing a flexible way to respond to events in the system.
 *
 * @param T The type of entities contained in the events this subscriber processes
 * @param E The specific type of [TransEvent] this subscriber consumes
 *
 * @see [TransEventPublisher]
 * @see [TransEventSubscription]
 */
interface TransEventSubscriber<T : TransEntity, ET: EventType, E : TransEvent<ET>> : Flow.Subscriber<E> {

    /**
     * Adds an action to be executed when [Flow.Subscriber.onSubscribe] is called.
     *
     * @param action The action to be executed, providing the [TransEventSubscription] as parameter
     */
    fun addOnSubscribeEventAction(action: Consumer<TransEventSubscription<T, ET, E>>)

    /**
     * Adds an action to be executed when [Flow.Subscriber.onNext] is called.
     *
     * @param eventTypes The types of events that will trigger the action
     * @param action The action to be executed, providing the event as a parameter
     */
    fun addOnNextEventAction(vararg eventTypes: EventType, action: Consumer<E>)

    /**
     * Adds an action to be executed when [Flow.Subscriber.onError] is called.
     *
     * @param action The action to be executed, providing the [Throwable] as parameter
     */
    fun addOnErrorEventAction(action: Consumer<Throwable>)

    /**
     * Adds an action to be executed when [Flow.Subscriber.onComplete] is called.
     *
     * @param action The action to be executed
     */
    fun addOnCompleteEventAction(action: Runnable)

    /**
     * Clears all the actions added to be executed when an event is received.
     *
     * This is useful for resetting the subscriber's behavior or preparing it
     * for a new set of actions.
     */
    fun clearSubscriptionActions()
}
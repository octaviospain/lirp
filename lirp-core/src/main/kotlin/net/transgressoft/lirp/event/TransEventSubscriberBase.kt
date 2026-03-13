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
import mu.KotlinLogging
import java.util.concurrent.Flow
import java.util.function.Consumer

/**
 * Base implementation of [TransEventSubscriber] that provides common functionality
 * for all event subscribers in the system.
 *
 * This class manages collections of actions to be executed in response to different
 * event types and lifecycle events. It implements the core logic for event handling
 * while allowing concrete subclasses to focus on specific use cases.
 *
 * @param T The type of entities contained in the events this subscriber processes
 * @param E The specific type of [TransEvent] this subscriber consumes
 * @param name A descriptive name for this subscriber, used in logging and debugging
 *
 * @see [TransEventPublisher]
 * @see [TransEventSubscription]
 */
abstract class TransEventSubscriberBase<T : TransEntity, ET: EventType, E : TransEvent<ET>>(protected val name: String) : TransEventSubscriber<T, ET, E> {

    private val log = KotlinLogging.logger {}

    private val onSubscribeEventActions: MutableList<Consumer<TransEventSubscription<T, ET, E>>> = mutableListOf()
    private val onNextEventActions: MutableMap<EventType, MutableList<Consumer<E>>> = mutableMapOf()
    private val onErrorEventActions: MutableList<Consumer<Throwable>> = mutableListOf()
    private val onCompleteEventActions: MutableList<Runnable> = mutableListOf()

    protected var subscription: TransEventSubscription<T, ET, E>? = null

    init {
        addOnSubscribeEventAction {
            subscription = it
            log.info { "$name subscribed to ${it.source}" }
        }
    }

    final override fun addOnSubscribeEventAction(action: Consumer<TransEventSubscription<T, ET, E>>) {
        onSubscribeEventActions.add(action)
        log.trace { "onSubscribe event action added to $name" }
    }

    final override fun addOnNextEventAction(vararg eventTypes: EventType, action: Consumer<E>) {
        eventTypes.forEach {
            if (it !in onNextEventActions) {
                onNextEventActions[it] = mutableListOf(action)
            } else {
                onNextEventActions[it]?.add(action)
            }
            log.trace { "onNext event action added to $name" }
        }
    }

    final override fun addOnErrorEventAction(action: Consumer<Throwable>) {
        onErrorEventActions.add(action)
        log.trace { "onError event action added to $name" }
    }

    final override fun addOnCompleteEventAction(action: Runnable) {
        onCompleteEventActions.add(action)
        log.trace { "onComplete event action added to $name" }
    }

    final override fun clearSubscriptionActions() {
        onSubscribeEventActions.clear()
        onNextEventActions.clear()
        onErrorEventActions.clear()
        onCompleteEventActions.clear()
        log.trace { "Event actions cleared" }
    }

    @Suppress("UNCHECKED_CAST")
    final override fun onSubscribe(subscription: Flow.Subscription) {
        if (subscription is TransEventSubscription<*, *, *>) {
            subscription as TransEventSubscription<T, ET, E>
            onSubscribeEventActions.forEach {
                log.trace { "$name registered a subscription from ${subscription.source}" }
                it.accept(subscription)
            }
            log.trace { "$this subscribed to changes from reactive entity ${subscription.source}" }
        }
    }

    final override fun onError(throwable: Throwable) {
        onErrorEventActions.forEach {
            log.trace { "onError event received: $throwable" }
            it.accept(throwable)
        }
    }

    final override fun onComplete() {
        onCompleteEventActions.forEach {
            log.trace { "onComplete event received" }
            it.run()
        }
    }

    final override fun onNext(item: E) {
        onNextEventActions[item.type]?.forEach {
            log.trace { "onNext event $item received" }
            it.accept(item)
        }
    }
}
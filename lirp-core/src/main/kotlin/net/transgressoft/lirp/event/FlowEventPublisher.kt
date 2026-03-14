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
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Configuration for FlowEventPublisher behavior.
 *
 * The defaults are suitable for most use cases. Only modify these if you
 * understand the implications.
 *
 * @property replay Number of events to replay to new subscribers. Default 0 means
 *   new subscribers only see events after they subscribe.
 * @property extraBufferCapacity Buffer size for events when subscribers are slow.
 *   Larger values use more memory but handle burst traffic better.
 * @property onBufferOverflow What happens when buffer is full:
 *   - SUSPEND (default): Emitter waits - guarantees delivery but can slow producers
 *   - DROP_OLDEST: Drops old events - never blocks but may lose events
 *   - DROP_LATEST: Drops new events - never blocks but may lose events
 */
data class PublisherConfig(
    val replay: Int = 0,
    val extraBufferCapacity: Int = 5120,
    val onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND
) {
    init {
        require(replay >= 0) { "replay must be non-negative" }
        require(extraBufferCapacity >= 0) { "extraBufferCapacity must be non-negative" }
    }

    companion object {
        /** Default configuration suitable for most use cases */
        val DEFAULT = PublisherConfig()

        /**
         * Configuration optimized for real-time scenarios where freshness
         * matters more than completeness. Never blocks the emitter.
         */
        val REAL_TIME =
            PublisherConfig(
                replay = 0,
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )

        /**
         * Configuration for memory-constrained environments.
         * Smaller buffer, suspends on overflow.
         */
        val LOW_MEMORY =
            PublisherConfig(
                replay = 0,
                extraBufferCapacity = 128,
                onBufferOverflow = BufferOverflow.SUSPEND
            )

        /**
         * Configuration that replays the last event to new subscribers.
         * Useful when subscribers need to know the current state on the subscription.
         */
        fun withReplay(count: Int = 1) =
            PublisherConfig(
                replay = count,
                extraBufferCapacity = 5120,
                onBufferOverflow = BufferOverflow.SUSPEND
            )
    }
}

/**
 * Class that provides reactive event publishing capabilities using Kotlin coroutine flows.
 *
 * `FlowEventPublisher` implements the core functionality needed for reactive programming by:
 * 1. Managing a [MutableSharedFlow] to broadcast events to multiple subscribers
 * 2. Providing compatibility with both Java's [Flow.Subscriber] API and Kotlin's flow-based approach
 * 3. Supporting suspending functions for asynchronous event handling
 * 4. Implementing [AutoCloseable] for deterministic resource cleanup
 * 5. Tracking the number of active subscribers via atomic operations
 *
 * This class serves as a foundational layer for both entity-level reactivity (property changes)
 * and collection-level reactivity (CRUD operations) within the reactive architecture.
 *
 * Key features:
 * - Thread-safe event publication with backpressure handling
 * - Support for both traditional subscribers and modern flow collectors
 * - Coroutine-based asynchronous event processing
 * - Selective event publishing based on event type activation
 * - Lifecycle management via [close]/[isClosed] — a closed publisher rejects new operations
 * - Subscriber count visibility via [subscriberCount]
 * - Optional self-close when all subscribers cancel via [closeOnEmpty]
 *
 * @param E The specific type of [TransEvent] this publisher will emit
 *
 * @see [TransEventPublisher]
 * @see [SharedFlow]
 */
class FlowEventPublisher<ET : EventType, E: TransEvent<ET>>
    @JvmOverloads
    constructor(
        private val id: String,
        // SharedFlow for entity change events with sufficient buffer and SUSPEND policy to ensure no events are lost
        config: PublisherConfig = PublisherConfig.DEFAULT,
        private val closeOnEmpty: Boolean = false
    ): TransEventPublisher<ET, E> {

        private val log = KotlinLogging.logger {}

        /**
         * Channel for processing events with unlimited buffer capacity.
         *
         * This unlimited buffer ensures that events are never dropped during high-traffic
         * periods or bursts of activity. When the processing rate catches up after a burst,
         * the memory used by processed events becomes eligible for garbage collection.
         *
         * This approach prioritizes reliable event delivery over fixed memory constraints.
         */
        private val eventChannel = Channel<E>(Channel.UNLIMITED)

        private val changesFlow = MutableSharedFlow<E>(config.replay, config.extraBufferCapacity, config.onBufferOverflow)

        override val changes: SharedFlow<E> = changesFlow.asSharedFlow()

        /**
         * The coroutine scope used for emitting change events.
         */
        private val flowScope = ReactiveScope.flowScope

        private var activatedEventTypes: MutableSet<EventType> = ConcurrentSkipListSet()

        @Volatile
        private var closed = false

        override val isClosed: Boolean get() = closed

        private val _subscriberCount = AtomicInteger(0)

        override val subscriberCount: Int get() = _subscriberCount.get()

        init {
            log.trace { "FlowEventPublisher created: $id" }

            // Create a single persistent coroutine to handle all emissions for a fire and forget approach
            flowScope.launch {
                for (event in eventChannel) {
                    try {
                        changesFlow.emit(event) // This suspends if needed
                    } catch (exception: Exception) {
                        log.error(exception) { "Unexpected error during event emission: $event" }
                    }
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            eventChannel.close()
            log.trace { "$this closed" }
        }

        override fun emitAsync(event: E) {
            check(!isClosed) { "Publisher '$id' is closed" }
            if (event.type in activatedEventTypes) {
                // Use trySend so we don't block the caller
                // If the channel is full, this will return the closed/failed result
                val result = eventChannel.trySend(event)
                if (!result.isSuccess) {
                    log.warn { "Could not send event to channel, buffer full or closed: $event" }
                }
            }
        }

        /**
         * Legacy compatibility method to support the existing [Flow.Subscriber] interface.
         * Consider migrating to the Kotlin Flow-based subscription method instead.
         */
        override fun subscribe(subscriber: Flow.Subscriber<in E>) {
            check(!isClosed) { "Publisher '$id' is closed" }
            log.trace { "Subscription registered to $subscriber" }

            _subscriberCount.incrementAndGet()

            val job =
                flowScope.launch {
                    changesFlow.collectLatest { event ->
                        subscriber.onNext(event)
                    }
                }

            job.invokeOnCompletion {
                _subscriberCount.decrementAndGet()
                if (_subscriberCount.get() == 0 && closeOnEmpty) close()
            }

            subscriber.onSubscribe(ReactiveSubscription<TransEntity>(this, job))
        }

        /**
         * Subscribes to entity change events by providing an action to execute when changes occur.
         *
         * @param action The action to execute when the entity changes
         * @return A subscription that can be used to unsubscribe
         */
        override fun subscribe(action: suspend (E) -> Unit): TransEventSubscription<in TransEntity, ET, E> {
            check(!isClosed) { "Publisher '$id' is closed" }
            log.trace { "Anonymous subscription registered to $id" }

            _subscriberCount.incrementAndGet()

            // Each subscription requires its own collection coroutine to handle events independently
            // This is a deliberate design pattern for reactive subscriptions
            @Suppress("kotlin:S6311")
            val job =
                flowScope.launch {
                    changesFlow.collectLatest { event ->
                        action(event)
                    }
                }

            job.invokeOnCompletion {
                _subscriberCount.decrementAndGet()
                if (_subscriberCount.get() == 0 && closeOnEmpty) close()
            }

            return ReactiveSubscription(this, job)
        }

        override fun subscribe(vararg eventTypes: ET, action: suspend (E) -> Unit): TransEventSubscription<in TransEntity, ET, E> {
            check(!isClosed) { "Publisher '$id' is closed" }
            log.trace { "Subscription registered to $id for event types: ${eventTypes.joinToString()}" }

            _subscriberCount.incrementAndGet()

            // Each subscription requires its own collection coroutine to handle events independently
            // This is a deliberate design pattern for reactive subscriptions
            @Suppress("kotlin:S6311")
            val job =
                flowScope.launch {
                    changesFlow.collectLatest { event ->
                        if (event.type in eventTypes) {
                            action(event)
                        }
                    }
                }

            job.invokeOnCompletion {
                _subscriberCount.decrementAndGet()
                if (_subscriberCount.get() == 0 && closeOnEmpty) close()
            }

            return ReactiveSubscription(this, job)
        }

        override fun disableEvents(vararg types: ET) {
            types.toSet().let {
                activatedEventTypes.removeAll(it)
                log.trace { "Enabled event types from $id: $activatedEventTypes" }
            }
        }

        override fun activateEvents(vararg types: ET) {
            types.toSet().let {
                activatedEventTypes.addAll(it)
                log.trace { "Enabled event types from $id: $activatedEventTypes" }
            }
        }

        override fun toString() = "FlowEventPublisher(id=$id, activatedEventTypes=$activatedEventTypes)"

        inner class ReactiveSubscription<T: TransEntity>(override val source: TransEventPublisher<ET, E>, private val job: Job)
        : TransEventSubscription<T, ET, E> {

            override fun request(n: Long) {
                error("Events cannot be requested on demand")
            }

            override fun cancel() {
                job.cancel()
            }
        }
    }
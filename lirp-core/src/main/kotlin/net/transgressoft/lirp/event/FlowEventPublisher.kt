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

import net.transgressoft.lirp.entity.LirpEntity
import mu.KotlinLogging
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean
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
 * Configuration for [FlowEventPublisher] behavior.
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
 * @property channelCapacity Capacity of the internal event channel that buffers events before they
 *   reach the SharedFlow. Defaults to [Channel.UNLIMITED].
 *   Use a bounded value (e.g., 64 or 128) to cap memory usage under sustained high-frequency
 *   mutations with slow subscribers.
 */
data class PublisherConfig(
    val replay: Int = 0,
    val extraBufferCapacity: Int = 5120,
    val onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    val channelCapacity: Int = Channel.UNLIMITED
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
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
                channelCapacity = 64
            )

        /**
         * Configuration for memory-constrained environments.
         * Smaller buffer, suspends on overflow.
         */
        val LOW_MEMORY =
            PublisherConfig(
                replay = 0,
                extraBufferCapacity = 128,
                onBufferOverflow = BufferOverflow.SUSPEND,
                channelCapacity = 128
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
 * @param E The specific type of [LirpEvent] this publisher will emit
 *
 * @see [LirpEventPublisher]
 * @see [SharedFlow]
 */
class FlowEventPublisher<ET : EventType, E: LirpEvent<ET>>
    @JvmOverloads
    constructor(
        private val id: String,
        // SharedFlow for entity change events with sufficient buffer and SUSPEND policy to ensure no events are lost
        private val config: PublisherConfig = PublisherConfig.DEFAULT,
        /**
         * When true, the publisher closes itself when the last subscriber cancels and no subscribe
         * call is currently in-flight.
         *
         * Race-condition protection: an atomic "in-flight" counter is incremented before the
         * subscription coroutine job is launched and decremented after the job is registered.
         * The [invokeOnCompletion] handler only triggers close if both the subscriber count and the
         * in-flight counter are zero at the same time, preventing premature shutdown when
         * subscribers are rapidly subscribing and cancelling concurrently.
         *
         * Lifecycle notification: immediately before the close is triggered, the callback
         * registered via [onCloseOnEmpty] is invoked so observers can react to the imminent shutdown.
         */
        private val closeOnEmpty: Boolean = false
    ): LirpEventPublisher<ET, E> {

        private val log = KotlinLogging.logger {}

        /**
         * Channel for processing events with configurable buffer capacity.
         *
         * The capacity is determined by [PublisherConfig.channelCapacity], defaulting to
         * [Channel.UNLIMITED]. Use a bounded capacity to cap
         * memory usage under sustained high-frequency mutations with slow subscribers.
         */
        private val eventChannel = Channel<E>(config.channelCapacity)

        private val changesFlow = MutableSharedFlow<E>(config.replay, config.extraBufferCapacity, config.onBufferOverflow)

        override val changes: SharedFlow<E> = changesFlow.asSharedFlow()

        /**
         * The coroutine scope used for emitting change events.
         */
        private val flowScope = ReactiveScope.flowScope

        // Immutable snapshot replaced atomically on activate/disable — reads need no copying or locking
        @Volatile
        private var activatedEventTypes: Set<EventType> = emptySet()

        private val closedFlag = AtomicBoolean(false)

        override val isClosed: Boolean get() = closedFlag.get()

        private val _subscriberCount = AtomicInteger(0)

        override val subscriberCount: Int get() = _subscriberCount.get()

        /**
         * Tracks subscribe() calls that have incremented [_subscriberCount] but whose coroutine
         * job has not yet been registered with [invokeOnCompletion]. While this counter is
         * non-zero, [closeOnEmpty] must not trigger close() even if [_subscriberCount] reaches
         * zero, because a concurrent subscriber is still being set up.
         */
        private val _inFlightSubscribes = AtomicInteger(0)

        /** Optional callback invoked once, just before a closeOnEmpty-triggered [close] call. */
        @Volatile
        private var onCloseOnEmptyCallback: (() -> Unit)? = null

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

        /**
         * Permanently closes this publisher.
         *
         * Uses [AtomicBoolean.compareAndSet] to ensure the close logic runs exactly once even
         * under concurrent calls. After closing, [emitAsync] and all [subscribe] overloads throw
         * [IllegalStateException]. Idempotent: subsequent calls are safe no-ops.
         */
        override fun close() {
            if (closedFlag.compareAndSet(false, true)) {
                eventChannel.close()
                log.trace { "$this closed" }
            }
        }

        override fun emitAsync(event: E) {
            check(!isClosed) { "Publisher '$id' is closed" }
            // Read the volatile reference once to get a consistent snapshot; a concurrent disableEvents()
            // replacing the reference after this read will not affect the check or the send
            val activeTypes = activatedEventTypes
            if (event.type in activeTypes) {
                // Use trySend so we don't block the caller
                // If the channel is full, this will return the closed/failed result
                val result = eventChannel.trySend(event)
                if (!result.isSuccess) {
                    log.warn { "Failed to send event to channel (capacity=${config.channelCapacity}): $event" }
                }
            }
        }

        /**
         * Registers a callback to be invoked once immediately before a [closeOnEmpty]-triggered
         * [close] call. This provides a lifecycle notification hook for observers that need to
         * react before the publisher shuts down.
         *
         * Only the most recently registered callback is retained.
         */
        fun onCloseOnEmpty(callback: () -> Unit) {
            onCloseOnEmptyCallback = callback
        }

        /**
         * Shared [invokeOnCompletion] handler used by all three subscribe() overloads.
         *
         * Decrements [_subscriberCount]. If both [_subscriberCount] and [_inFlightSubscribes]
         * reach zero and [closeOnEmpty] is enabled, fires the [onCloseOnEmptyCallback] and
         * then closes this publisher. The double-zero check prevents premature close during
         * concurrent subscribe/cancel cycles where a new subscriber is still being registered.
         */
        private fun Job.registerCompletionHandler() {
            invokeOnCompletion {
                _subscriberCount.decrementAndGet()
                if (closeOnEmpty && _subscriberCount.get() == 0 && _inFlightSubscribes.get() == 0) {
                    onCloseOnEmptyCallback?.invoke()
                    close()
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
            _inFlightSubscribes.incrementAndGet()

            val job =
                flowScope.launch {
                    changesFlow.collectLatest { event ->
                        subscriber.onNext(event)
                    }
                }

            _inFlightSubscribes.decrementAndGet()
            job.registerCompletionHandler()

            subscriber.onSubscribe(ReactiveSubscription<LirpEntity>(this, job))
        }

        /**
         * Subscribes to entity change events by providing an action to execute when changes occur.
         *
         * @param action The action to execute when the entity changes
         * @return A subscription that can be used to unsubscribe
         */
        override fun subscribe(action: suspend (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, E> {
            check(!isClosed) { "Publisher '$id' is closed" }
            log.trace { "Anonymous subscription registered to $id" }

            _subscriberCount.incrementAndGet()
            _inFlightSubscribes.incrementAndGet()

            // Each subscription requires its own collection coroutine to handle events independently
            // This is a deliberate design pattern for reactive subscriptions
            @Suppress("kotlin:S6311")
            val job =
                flowScope.launch {
                    changesFlow.collectLatest { event ->
                        action(event)
                    }
                }

            _inFlightSubscribes.decrementAndGet()
            job.registerCompletionHandler()

            return ReactiveSubscription(this, job)
        }

        override fun subscribe(vararg eventTypes: ET, action: suspend (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, E> {
            check(!isClosed) { "Publisher '$id' is closed" }
            log.trace { "Subscription registered to $id for event types: ${eventTypes.joinToString()}" }

            _subscriberCount.incrementAndGet()
            _inFlightSubscribes.incrementAndGet()

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

            _inFlightSubscribes.decrementAndGet()
            job.registerCompletionHandler()

            return ReactiveSubscription(this, job)
        }

        override fun disableEvents(vararg types: ET) {
            activatedEventTypes = activatedEventTypes - types.toSet()
            log.trace { "Enabled event types from $id: $activatedEventTypes" }
        }

        override fun activateEvents(vararg types: ET) {
            activatedEventTypes = activatedEventTypes + types.toSet()
            log.trace { "Enabled event types from $id: $activatedEventTypes" }
        }

        override fun toString() = "FlowEventPublisher(id=$id, activatedEventTypes=$activatedEventTypes)"

        inner class ReactiveSubscription<T: LirpEntity>(override val source: LirpEventPublisher<ET, E>, private val job: Job)
        : LirpEventSubscription<T, ET, E> {

            override fun request(n: Long) {
                error("Events cannot be requested on demand")
            }

            override fun cancel() {
                job.cancel()
            }
        }
    }
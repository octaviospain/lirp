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
import net.transgressoft.lirp.event.LirpErrorContext
import net.transgressoft.lirp.event.LirpErrorHandler
import net.transgressoft.lirp.event.LirpOperation
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext

// MDC keys for async event emission log correlation
private const val MDC_KEY_REPOSITORY = "lirp.repository"
private const val MDC_KEY_OPERATION = "lirp.operation"

/**
 * Configuration for [FlowEventPublisher] behavior.
 *
 * The defaults are suitable for most use cases. Only modify these if you
 * understand the implications.
 *
 * @property replay Number of events to replay to new subscribers. Default 0 means
 *   new subscribers only see events after they subscribe.
 *   Note: replay buffering begins only after the async bridge is first armed (by accessing
 *   [FlowEventPublisher.changes] or calling [FlowEventPublisher.subscribeAsync]). Events emitted
 *   before the bridge is armed are not buffered. To buffer from startup: access `publisher.changes`
 *   once at initialization.
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
 * Class that provides reactive event publishing with both synchronous callbacks and
 * Kotlin coroutine flows.
 *
 * `FlowEventPublisher` supports two subscription transports:
 * - **Synchronous** (`subscribe`): callbacks are stored in a [CopyOnWriteArrayList] and invoked
 *   inline on the emitting thread before any async delivery. Zero coroutine overhead. Ideal for
 *   fast in-process work (cache updates, index maintenance) that must be complete by the time
 *   [emitAsync] returns. Reentrancy on the same publisher from the same thread is handled via a
 *   per-publisher `ThreadLocal` trampoline that defers reentrant events breadth-first.
 * - **Asynchronous** (`subscribeAsync`): actions run in coroutines on a background dispatcher via a
 *   lazily constructed `Channel`/`MutableSharedFlow` bridge. The bridge is initialized on the first
 *   call to [subscribeAsync], [changes], or [subscribe] with a [Flow.Subscriber]; a publisher with
 *   only sync subscribers allocates no coroutine machinery.
 *
 * [closeOnEmpty] fires only when both the sync callback list and the async subscriber count
 * reach zero simultaneously.
 *
 * @param E The specific type of [LirpEvent] this publisher will emit
 *
 * @see [LirpEventPublisher]
 * @see [SharedFlow]
 */
class FlowEventPublisher<ET : EventType, E : LirpEvent<ET>>
    @JvmOverloads
    constructor(
        private val id: String,
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
        private val closeOnEmpty: Boolean = false,
        /**
         * Optional handler invoked after the existing error log when the async drain loop catches
         * an exception during `flow.emit`. The framework logs first, then notifies the handler.
         * The handler observes the failure but does not alter control flow (notify-only). When
         * `null`, behavior is log-only — identical to not configuring a handler.
         */
        private val onError: LirpErrorHandler? = null
    ) : LirpEventPublisher<ET, E> {

        private val log = KotlinLogging.logger {}

        /**
         * Lazily initialized async bridge holding the Channel, MutableSharedFlow, and drain coroutine.
         * Armed only on the first async entry point; never armed by sync subscribe overloads.
         */
        private inner class Bridge {
            val channel: Channel<E> = Channel(config.channelCapacity)

            @Suppress("kotlin:S6305") // Exposing mutable flow is ok here for a private class
            val flow: MutableSharedFlow<E> = MutableSharedFlow(config.replay, config.extraBufferCapacity, config.onBufferOverflow)
            val changes: SharedFlow<E> = flow.asSharedFlow()

            init {
                MDC.put(MDC_KEY_REPOSITORY, id)
                MDC.put(MDC_KEY_OPERATION, LirpOperation.EMIT.name)
                try {
                    flowScope.launch(MDCContext()) {
                        for (event in channel) {
                            try {
                                flow.emit(event)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (exception: Exception) {
                                log.error(exception) { "Unexpected error during event emission: $event" }
                                // Notify after logging — handler observes but does not alter control flow
                                try {
                                    onError?.invoke(exception, LirpErrorContext(LirpOperation.EMIT, emptyList<Any>(), id))
                                } catch (handlerEx: Throwable) {
                                    log.error(handlerEx) { "LirpErrorHandler threw; exception swallowed to preserve drain loop" }
                                }
                            }
                        }
                    }
                } finally {
                    MDC.remove(MDC_KEY_REPOSITORY)
                    MDC.remove(MDC_KEY_OPERATION)
                }
            }
        }

        private val _bridgeHolder: Lazy<Bridge> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Bridge() }

        private fun armBridge(): Bridge = _bridgeHolder.value

        // Returns null (non-blocking) if the bridge is not yet initialized — safe for the emitAsync fast path
        private val bridge: Bridge? get() = if (_bridgeHolder.isInitialized()) _bridgeHolder.value else null

        /**
         * Diagnostic read-only flag for testing: `true` only after the async bridge has been armed
         * (on first call to [subscribeAsync], [changes], or [subscribe] with a [Flow.Subscriber]).
         * A publisher with only sync [subscribe] callbacks must keep this `false`.
         */
        internal val isBridgeInitialized: Boolean get() = _bridgeHolder.isInitialized()

        override val changes: SharedFlow<E>
            get() {
                check(!isClosed) { "Publisher '$id' is closed" }
                return armBridge().changes
            }

        /**
         * The coroutine scope used for emitting change events.
         */
        private val flowScope = ReactiveScope.flowScope

        // Immutable snapshot replaced atomically on activate/disable — reads need no copying or locking
        @Volatile
        private var activatedEventTypes: Set<EventType> = emptySet()

        private val closedFlag = AtomicBoolean(false)

        override val isClosed: Boolean get() = closedFlag.get()

        private val _syncSubscriberCount = AtomicInteger(0)
        private val _asyncSubscriberCount = AtomicInteger(0)

        override val subscriberCount: Int get() = _syncSubscriberCount.get() + _asyncSubscriberCount.get()

        /**
         * Tracks subscribeAsync() calls that have incremented [_asyncSubscriberCount] but whose coroutine
         * job has not yet been registered with [invokeOnCompletion]. While this counter is non-zero,
         * [closeOnEmpty] must not trigger [close] even if [subscriberCount] reaches zero, because a
         * concurrent subscriber is still being set up.
         */
        private val _inFlightSubscribes = AtomicInteger(0)

        /** Optional callback invoked once, just before a closeOnEmpty-triggered [close] call. */
        @Volatile
        private var onCloseOnEmptyCallback: (() -> Unit)? = null

        /**
         * Holds sync callbacks registered via [subscribe]. [CopyOnWriteArrayList] provides
         * snapshot-safe iteration: concurrent subscribe/cancel during dispatch affects the
         * next iteration only, never the current one.
         */
        private val directCallbacks = CopyOnWriteArrayList<(E) -> Unit>()

        // Per-publisher reentrancy trampoline (modeled on Guava EventBus PerThreadQueuedDispatcher)
        private val _isDispatching: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        private val _pendingEvents: ThreadLocal<ArrayDeque<E>> = ThreadLocal.withInitial { ArrayDeque() }

        init {
            log.trace { "FlowEventPublisher created: $id" }
        }

        /**
         * Permanently closes this publisher.
         *
         * Uses [AtomicBoolean.compareAndSet] to ensure the close logic runs exactly once even
         * under concurrent calls. After closing, [emitAsync] and all [subscribe]/[subscribeAsync]
         * overloads throw [IllegalStateException]. Idempotent: subsequent calls are safe no-ops.
         */
        override fun close() {
            if (closedFlag.compareAndSet(false, true)) {
                bridge?.channel?.close()
                directCallbacks.clear()
                log.trace { "$this closed" }
            }
        }

        override fun emitAsync(event: E) {
            check(!isClosed) { "Publisher '$id' is closed" }
            // Short-circuit when nobody is listening and no replay is configured.
            if (subscriberCount == 0 && config.replay == 0) return

            // Read activatedEventTypes once; both dispatch paths share this snapshot.
            val activeTypes = activatedEventTypes

            // [1] Sync dispatch: when callbacks are registered and the event type is active
            if (directCallbacks.isNotEmpty() && event.type in activeTypes) {
                dispatchSync(event)
            }

            // [2] Async bridge dispatch: only if the bridge has been armed
            val b = bridge
            if (b != null && event.type in activeTypes) {
                val result = b.channel.trySend(event)
                if (!result.isSuccess) {
                    log.warn { "Failed to send event to channel (capacity=${config.channelCapacity}): $event" }
                }
            }
        }

        /**
         * Dispatches [event] to all sync callbacks using a per-publisher trampoline to handle
         * reentrancy. When a sync callback triggers another [emitAsync] on the same publisher from
         * the same thread, the reentrant event is queued and drained breadth-first after the current
         * dispatch completes. Each callback is wrapped in try/catch(Exception) so a throwing callback
         * does not prevent delivery to remaining callbacks. Error propagates as a bug detector.
         */
        private fun dispatchSync(event: E) {
            if (_isDispatching.get()) {
                _pendingEvents.get().addLast(event)
                return
            }
            _isDispatching.set(true)
            try {
                var current: E? = event
                while (current != null) {
                    for (callback in directCallbacks) {
                        try {
                            callback(current)
                        } catch (e: Exception) {
                            log.error(e) { "Exception in sync callback for publisher '$id'" }
                        }
                    }
                    current = _pendingEvents.get().removeFirstOrNull()
                }
            } finally {
                _isDispatching.set(false)
                // If a callback propagated a Throwable out of the drain loop, queued reentrant events
                // remain; clear them so they are not silently replayed on the next dispatch on this thread.
                _pendingEvents.get().clear()
            }
        }

        /**
         * Fires [closeOnEmpty] if both sync and async subscriber counts are zero and no async
         * subscribe call is currently in-flight.
         */
        private fun maybeCloseOnEmpty() {
            if (closeOnEmpty &&
                _syncSubscriberCount.get() == 0 &&
                _asyncSubscriberCount.get() == 0 &&
                _inFlightSubscribes.get() == 0
            ) {
                onCloseOnEmptyCallback?.invoke()
                close()
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
         * Shared [invokeOnCompletion] handler used by async subscribe overloads. Decrements
         * [_asyncSubscriberCount] and delegates to [maybeCloseOnEmpty].
         */
        private fun Job.registerCompletionHandler() {
            invokeOnCompletion {
                _asyncSubscriberCount.decrementAndGet()
                maybeCloseOnEmpty()
            }
        }

        override fun subscribe(callback: (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, E> {
            // Bracket registration with the in-flight counter so a concurrent last-subscriber cancel
            // cannot closeOnEmpty between the isClosed check and the directCallbacks add — that would
            // leave a live handle on a closed publisher and corrupt the subscriber count.
            _inFlightSubscribes.incrementAndGet()
            try {
                if (isClosed) {
                    if (closeOnEmpty) return cancelledSyncSubscription()
                    error("Publisher '$id' is closed")
                }
                directCallbacks.add(callback)
                _syncSubscriberCount.incrementAndGet()
                log.trace { "Sync subscription registered to $id" }
                return SyncSubscription(this, callback)
            } finally {
                _inFlightSubscribes.decrementAndGet()
            }
        }

        override fun subscribe(vararg eventTypes: ET, callback: (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, E> {
            _inFlightSubscribes.incrementAndGet()
            try {
                if (isClosed) {
                    if (closeOnEmpty) return cancelledSyncSubscription()
                    error("Publisher '$id' is closed")
                }
                // Store the wrapper lambda (not the original callback) so cancel() can remove it by identity
                val wrapper: (E) -> Unit = { event -> if (event.type in eventTypes) callback(event) }
                directCallbacks.add(wrapper)
                _syncSubscriberCount.incrementAndGet()
                log.trace { "Filtered sync subscription registered to $id for event types: ${eventTypes.joinToString()}" }
                return SyncSubscription(this, wrapper)
            } finally {
                _inFlightSubscribes.decrementAndGet()
            }
        }

        /**
         * Legacy compatibility method to support the existing [Flow.Subscriber] interface.
         * Consider migrating to the Kotlin Flow-based subscription method instead.
         */
        override fun subscribe(subscriber: Flow.Subscriber<in E>) {
            _inFlightSubscribes.incrementAndGet()
            if (isClosed) {
                _inFlightSubscribes.decrementAndGet()
                if (closeOnEmpty)
                    return
                error("Publisher '$id' is closed")
            }
            log.trace { "Subscription registered to $subscriber" }

            _asyncSubscriberCount.incrementAndGet()

            // Arm the bridge synchronously, before launching the collect coroutine. Otherwise an
            // emitAsync between this call returning and the coroutine being scheduled would see a
            // null bridge and silently drop the event; the channel must exist to buffer it.
            val armed = armBridge()
            // A concurrent external close() may have fired after the isClosed check above but before
            // arming; arming would otherwise leak a Channel + drain coroutine that is never closed.
            // Tear the freshly-armed bridge down on that race.
            if (isClosed) {
                armed.channel.close()
                _asyncSubscriberCount.decrementAndGet()
                _inFlightSubscribes.decrementAndGet()
                if (closeOnEmpty) return
                error("Publisher '$id' is closed")
            }
            val job =
                flowScope.launch {
                    armed.flow.collect { event ->
                        subscriber.onNext(event)
                    }
                }

            _inFlightSubscribes.decrementAndGet()
            job.registerCompletionHandler()

            subscriber.onSubscribe(ReactiveSubscription<LirpEntity>(this, job))
        }

        /**
         * Subscribes asynchronously with a per-subscription error handler.
         *
         * When [action] throws, the exception is logged at ERROR level and [onError] is invoked
         * with operation [LirpOperation.EMIT] and this publisher's id. The failure does not
         * consult any repository-level handler — per-subscription and repository-level handlers
         * are independent. Omitting [onError] (i.e. calling the single-arg overload) keeps
         * the existing log-only behavior.
         *
         * @param action The suspend action to execute when events are emitted
         * @param onError Handler invoked after logging when [action] throws
         * @return A subscription that can be used to unsubscribe
         */
        override fun subscribeAsync(
            action: suspend (E) -> Unit,
            onError: LirpErrorHandler
        ): LirpEventSubscription<in LirpEntity, ET, E> =
            subscribeAsync { event ->
                try {
                    action(event)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    log.error(e) { "Async subscriber action failed for publisher '$id'" }
                    try {
                        onError(e, LirpErrorContext(LirpOperation.EMIT, emptyList<Any>(), id))
                    } catch (handlerEx: Throwable) {
                        log.error(handlerEx) { "LirpErrorHandler threw; exception swallowed to preserve subscriber coroutine" }
                    }
                }
            }

        /**
         * Subscribes asynchronously to entity change events by providing a suspending action.
         *
         * @param action The suspend action to execute when events are emitted
         * @return A subscription that can be used to unsubscribe
         */
        override fun subscribeAsync(action: suspend (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, E> {
            _inFlightSubscribes.incrementAndGet()
            if (isClosed) {
                _inFlightSubscribes.decrementAndGet()
                if (closeOnEmpty)
                    return cancelledSubscription()
                error("Publisher '$id' is closed")
            }
            log.trace { "Async subscription registered to $id" }

            _asyncSubscriberCount.incrementAndGet()

            // Arm the bridge synchronously, before launching the collect coroutine, so an emitAsync
            // racing this subscription is buffered by the channel rather than dropped against a null bridge.
            val armed = armBridge()
            // Tear down the freshly-armed bridge if an external close() raced in after the isClosed
            // check above, otherwise the Channel + drain coroutine would leak on the process-lived scope.
            if (isClosed) {
                armed.channel.close()
                _asyncSubscriberCount.decrementAndGet()
                _inFlightSubscribes.decrementAndGet()
                if (closeOnEmpty) return cancelledSubscription()
                error("Publisher '$id' is closed")
            }

            // Each subscription requires its own collection coroutine to handle events independently
            // This is a deliberate design pattern for reactive subscriptions
            @Suppress("kotlin:S6311")
            val job =
                flowScope.launch {
                    armed.flow.collect { event ->
                        action(event)
                    }
                }

            _inFlightSubscribes.decrementAndGet()
            job.registerCompletionHandler()

            return ReactiveSubscription(this, job)
        }

        override fun subscribeAsync(vararg eventTypes: ET, action: suspend (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, E> {
            _inFlightSubscribes.incrementAndGet()
            if (isClosed) {
                _inFlightSubscribes.decrementAndGet()
                if (closeOnEmpty) return cancelledSubscription()
                error("Publisher '$id' is closed")
            }
            log.trace { "Async filtered subscription registered to $id for event types: ${eventTypes.joinToString()}" }

            _asyncSubscriberCount.incrementAndGet()

            // Arm the bridge synchronously, before launching the collect coroutine, so an emitAsync
            // racing this subscription is buffered by the channel rather than dropped against a null bridge.
            val armed = armBridge()
            // Tear down the freshly-armed bridge if an external close() raced in after the isClosed
            // check above, otherwise the Channel + drain coroutine would leak on the process-lived scope.
            if (isClosed) {
                armed.channel.close()
                _asyncSubscriberCount.decrementAndGet()
                _inFlightSubscribes.decrementAndGet()
                if (closeOnEmpty) return cancelledSubscription()
                error("Publisher '$id' is closed")
            }

            // Each subscription requires its own collection coroutine to handle events independently
            // This is a deliberate design pattern for reactive subscriptions
            @Suppress("kotlin:S6311")
            val job =
                flowScope.launch {
                    armed.flow.collect { event ->
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
            log.trace { "Active event types after disable from $id: $activatedEventTypes" }
        }

        override fun activateEvents(vararg types: ET) {
            activatedEventTypes = activatedEventTypes + types.toSet()
            log.trace { "Enabled event types from $id: $activatedEventTypes" }
        }

        override fun isEventActive(type: ET): Boolean = type in activatedEventTypes

        override fun toString() = "FlowEventPublisher(id=$id, activatedEventTypes=$activatedEventTypes)"

        private fun cancelledSubscription(): LirpEventSubscription<in LirpEntity, ET, E> =
            ReactiveSubscription(this, Job().apply { cancel() })

        private fun cancelledSyncSubscription(): LirpEventSubscription<in LirpEntity, ET, E> =
            SyncSubscription(this, {})

        inner class ReactiveSubscription<T : LirpEntity>(override val source: LirpEventPublisher<ET, E>, private val job: Job)
        : LirpEventSubscription<T, ET, E> {

            override fun request(n: Long) {
                error("Events cannot be requested on demand")
            }

            override fun cancel() {
                job.cancel()
            }
        }

        /**
         * Subscription handle for synchronous callbacks registered via [subscribe].
         *
         * Cancellation removes the stored callback from [directCallbacks] and decrements the sync
         * subscriber count. For filtered subscriptions, the stored callback is the wrapper lambda
         * that performs the type check, not the original user-provided callback.
         */
        inner class SyncSubscription<T : LirpEntity>(override val source: LirpEventPublisher<ET, E>, private val storedCallback: (E) -> Unit)
        : LirpEventSubscription<T, ET, E> {

            override fun request(n: Long) {
                error("Events cannot be requested on demand")
            }

            override fun cancel() {
                if (directCallbacks.remove(storedCallback)) {
                    _syncSubscriberCount.decrementAndGet()
                    maybeCloseOnEmpty()
                }
            }
        }
    }
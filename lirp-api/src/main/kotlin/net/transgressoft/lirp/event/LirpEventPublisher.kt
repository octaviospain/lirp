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
import java.util.concurrent.Flow
import java.util.function.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow

/**
 * A publisher of [LirpEvent]s that implements the reactive streams [Flow.Publisher] interface
 * and [AutoCloseable] for deterministic resource cleanup.
 *
 * This interface represents the source of events in the reactive stream, publishing
 * events to interested subscribers. It serves as a bridge between the standard
 * Java Flow API and lirp event system.
 *
 * Subscription transports:
 * - **Synchronous** (`subscribe`): the callback is invoked inline on the emitting thread, before
 *   any async delivery. Zero coroutine overhead; ideal for fast in-process work (cache updates,
 *   audit appends) that must be visible by the time the mutation returns.
 * - **Asynchronous** (`subscribeAsync`): the action runs in a coroutine on a background dispatcher.
 *   Suitable for slow, blocking, or fan-out work that must not block the emitting thread.
 *
 * A publisher can be permanently closed via [close]. Once closed, it rejects new subscriptions
 * and event emissions. The [subscriberCount] property allows observing the number of active subscribers,
 * counting both sync and async registrations.
 *
 * @param ET The specific type of [EventType] associated with this publisher
 * @param E The specific type of [LirpEvent] published by this publisher
 */
interface LirpEventPublisher<ET : EventType, out E : LirpEvent<ET>> : Flow.Publisher<@UnsafeVariance E>, AutoCloseable {

    /**
     * A flow of entity change events that collectors can observe asynchronously.
     *
     * Accessing this property arms the internal async bridge if it has not been armed yet.
     */
    val changes: SharedFlow<E>

    /**
     * Whether this publisher has been permanently closed.
     *
     * A closed publisher rejects new subscriptions and event emissions with [IllegalStateException].
     */
    val isClosed: Boolean

    /**
     * The current number of active subscribers, counting both synchronous and asynchronous registrations.
     */
    val subscriberCount: Int

    /**
     * Publishes an event to all subscribers, asynchronously.
     */
    fun emitAsync(event: @UnsafeVariance E)

    /**
     * Subscribes synchronously to all events emitted by this publisher.
     *
     * The callback is invoked inline on the emitting thread before any async delivery.
     *
     * @param callback The function invoked for each emitted event on the emitting thread
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribe(callback: (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, @UnsafeVariance E>

    /**
     * Subscribes synchronously to events of the specified types only.
     *
     * The callback is invoked inline on the emitting thread for matching events only.
     *
     * @param eventTypes The event types to filter on; events of other types are ignored
     * @param callback The function invoked for each matching event on the emitting thread
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribe(vararg eventTypes: ET, callback: (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, @UnsafeVariance E>

    /**
     * Subscribes asynchronously to all events emitted by this publisher.
     *
     * Events are delivered on a coroutine; the action does not run on the emitting thread.
     *
     * @param action The suspend function invoked for each emitted event
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribeAsync(action: suspend (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, @UnsafeVariance E>

    /**
     * Java-interop async subscription via [Consumer]; delegates to [subscribeAsync].
     *
     * @param action The consumer invoked for each emitted event in a coroutine
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribeAsync(action: Consumer<in E>): LirpEventSubscription<in LirpEntity, ET, @UnsafeVariance E> = subscribeAsync(action::accept)

    /**
     * Subscribes asynchronously with a per-subscription error handler.
     *
     * When [action] throws, the exception is caught and [onError] is invoked with operation
     * [LirpOperation.EMIT]. The per-subscription handler is independent of any repository-level
     * handler — omitting [onError] (using the single-arg overload) keeps log-only behavior and
     * does not consult any repository-level handler.
     *
     * The default body wraps [action] in a try/catch that calls [onError] on any exception;
     * implementations may override for richer context (e.g. pre-logging before notifying the handler).
     * Coroutine cancellation is rethrown rather than routed to [onError], and any exception thrown by
     * [onError] itself is swallowed so the handler cannot alter control flow (notify-only contract).
     *
     * @param action The suspend function invoked for each emitted event
     * @param onError Handler invoked when [action] throws; the exception is swallowed after notification
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribeAsync(
        action: suspend (E) -> Unit,
        onError: LirpErrorHandler
    ): LirpEventSubscription<in LirpEntity, ET, @UnsafeVariance E> =
        subscribeAsync { event ->
            try {
                action(event)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                try {
                    onError(e, LirpErrorContext(LirpOperation.EMIT, emptyList(), this::class.qualifiedName ?: "unknown"))
                } catch (_: Throwable) {
                    // notify-only: a throwing handler must not alter control flow
                }
            }
        }

    /**
     * Subscribes asynchronously to events of the specified types only.
     *
     * Events are delivered on a coroutine; the action does not run on the emitting thread.
     *
     * @param eventTypes The event types to filter on; events of other types are ignored
     * @param action The suspend function invoked for each matching event
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribeAsync(vararg eventTypes: ET, action: suspend (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, @UnsafeVariance E>

    /**
     * Activates emission for the given event types. Events of non-activated types are silently dropped.
     *
     * @param types The event types to activate
     */
    fun activateEvents(vararg types: @UnsafeVariance ET)

    /**
     * Disables emission for the given event types. Events of disabled types are silently dropped until re-activated.
     *
     * @param types The event types to disable
     */
    fun disableEvents(vararg types: @UnsafeVariance ET)

    /**
     * Returns `true` if the given event type is currently activated for emission.
     *
     * @param type the event type to check
     */
    fun isEventActive(type: @UnsafeVariance ET): Boolean

    /**
     * Permanently closes this publisher.
     *
     * After closing, [emitAsync] and all [subscribe]/[subscribeAsync] overloads throw [IllegalStateException].
     * Idempotent: subsequent calls are safe no-ops.
     */
    override fun close()
}
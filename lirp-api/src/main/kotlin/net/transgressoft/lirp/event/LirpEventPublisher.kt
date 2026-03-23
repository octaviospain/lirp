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
import kotlinx.coroutines.flow.SharedFlow

/**
 * A publisher of [LirpEvent]s that implements the reactive streams [Flow.Publisher] interface
 * and [AutoCloseable] for deterministic resource cleanup.
 *
 * This interface represents the source of events in the reactive stream, publishing
 * events to interested subscribers. It serves as a bridge between the standard
 * Java Flow API and lirp event system.
 *
 * A publisher can be permanently closed via [close]. Once closed, it rejects new subscriptions
 * and event emissions. The [subscriberCount] property allows observing the number of active subscribers.
 *
 * @param ET The specific type of [EventType] associated with this publisher
 * @param E The specific type of [LirpEvent] published by this publisher
 */
interface LirpEventPublisher<ET : EventType, out E : LirpEvent<ET>> : Flow.Publisher<@UnsafeVariance E>, AutoCloseable {

    /**
     * A flow of entity change events that collectors can observe.
     */
    val changes: SharedFlow<E>

    /**
     * Whether this publisher has been permanently closed.
     *
     * A closed publisher rejects new subscriptions and event emissions with [IllegalStateException].
     */
    val isClosed: Boolean

    /**
     * The current number of active subscribers.
     */
    val subscriberCount: Int

    /**
     * Publishes an event to all subscribers, asynchronously.
     */
    fun emitAsync(event: @UnsafeVariance E)

    /**
     * Subscribes to all events emitted by this publisher.
     *
     * @param action The suspend function invoked for each emitted event
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribe(action: suspend (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, @UnsafeVariance E>

    /**
     * Legacy compatibility method for Java-style Consumer subscriptions.
     * Consider migrating to the Kotlin Flow-based subscription method instead.
     */
    fun subscribe(action: Consumer<in E>): LirpEventSubscription<in LirpEntity, ET, @UnsafeVariance E> = subscribe(action::accept)

    /**
     * Subscribes to events of the specified types only.
     *
     * @param eventTypes The event types to filter on; events of other types are ignored
     * @param action The suspend function invoked for each matching event
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribe(vararg eventTypes: ET, action: suspend (E) -> Unit): LirpEventSubscription<in LirpEntity, ET, @UnsafeVariance E>

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
     * Permanently closes this publisher.
     *
     * After closing, [emitAsync] and all [subscribe] overloads throw [IllegalStateException].
     * Idempotent: subsequent calls are safe no-ops.
     */
    override fun close()
}
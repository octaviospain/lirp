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

import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import java.time.LocalDateTime
import java.util.concurrent.Flow
import java.util.function.Consumer
import kotlinx.coroutines.flow.SharedFlow

/**
 * Represents an entity that can be reactive to changes in its properties. Reactive in the way that
 * regarding its internal logic, it can create a logic reaction on the subscribed entities.
 *
 * An entity transitions through well-defined lifecycle states:
 * - **Created**: Initial state. No publisher allocated; zero overhead.
 * - **Active**: At least one subscriber registered; publisher exists and emits events.
 * - **Dormant**: All subscribers cancelled; publisher shut down and nullified. Reactivates lazily on next subscription.
 * - **Closed**: Terminal state. All operations that mutate or subscribe throw [IllegalStateException].
 *
 * Subscription transports:
 * - **Synchronous** (`subscribe`): the callback is invoked inline on the emitting thread, before
 *   any async delivery. Ideal for fast in-process work that must be visible immediately after the mutation.
 * - **Asynchronous** (`subscribeAsync`): the action runs in a coroutine on a background dispatcher.
 *   Suitable for slow, blocking, or fan-out work that must not block the emitting thread.
 *
 * @param K the type of the entity's id.
 * @param R the type of the entity.
 */
interface ReactiveEntity<K, R : ReactiveEntity<K, R>> :
    IdentifiableEntity<K>,
    Flow.Publisher<MutationEvent<K, R>>,
    AutoCloseable where K : Comparable<K> {

    /**
     * The date and time of the most recent property mutation on this entity.
     */
    val lastDateModified: LocalDateTime

    /**
     * Whether this entity has been permanently closed.
     */
    val isClosed: Boolean

    /**
     * A flow of entity change events that can be observed by collectors asynchronously.
     */
    val changes: SharedFlow<MutationEvent<K, R>>

    /**
     * Publishes an event to all subscribers, asynchronously.
     */
    fun emitAsync(event: MutationEvent<K, R>)

    /**
     * Subscribes synchronously to all mutation events on this entity.
     *
     * The callback is invoked inline on the emitting thread before any async delivery.
     *
     * @param callback The function invoked for each mutation event on the emitting thread
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribe(callback: (MutationEvent<K, R>) -> Unit): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>

    /**
     * Subscribes synchronously to mutation events of the specified types only.
     *
     * The callback is invoked inline on the emitting thread for matching events only.
     *
     * @param eventTypes The mutation event types to filter on
     * @param callback The function invoked for each matching event on the emitting thread
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribe(vararg eventTypes: MutationEvent.Type, callback: (MutationEvent<K, R>) -> Unit):
        LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>

    /**
     * Subscribes asynchronously to all mutation events on this entity.
     *
     * Events are delivered on a coroutine; the action does not run on the emitting thread.
     *
     * @param action The suspend function invoked for each mutation event
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribeAsync(action: suspend (MutationEvent<K, R>) -> Unit): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>

    /**
     * Java-interop async subscription via [Consumer]; delegates to [subscribeAsync].
     *
     * @param action The consumer invoked for each mutation event in a coroutine
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribeAsync(action: Consumer<in MutationEvent<K, R>>): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
        subscribeAsync(action::accept)

    /**
     * Subscribes asynchronously to mutation events of the specified types, using a Java [Consumer].
     *
     * Events are delivered on a coroutine for the matching types only.
     *
     * @param eventTypes The mutation event types to filter on
     * @param action The consumer invoked for each matching event in a coroutine
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribeAsync(vararg eventTypes: MutationEvent.Type, action: Consumer<in MutationEvent<K, R>>):
        LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>

    /**
     * Executes [action] with event emission suppressed, restoring the previous state afterward.
     *
     * Equivalent to wrapping the action between disabling and re-enabling event publishing, but
     * guarantees restoration even if the action throws. Common use cases:
     *
     * - Clone implementations that copy all properties without triggering mutation events
     * - Batch property initialization from deserialized or database-loaded state
     * - Framework-level operations that need to set multiple properties atomically
     *
     * ```
     * override fun clone(): MyEntity = MyEntity(id).apply {
     *     withEventsDisabled {
     *         name = this@MyEntity.name
     *         price = this@MyEntity.price
     *     }
     * }
     * ```
     *
     * @param T The return type of the action
     * @param action The block to execute with events disabled
     * @return The result of the action
     */
    fun <T> withEventsDisabled(action: () -> T): T

    /**
     * Permanently closes this entity and releases its publisher resources.
     *
     * After closing [subscribe] and [subscribeAsync] throw [IllegalStateException]. Idempotent: subsequent calls are safe no-ops.
     */
    override fun close()

    /**
     * Creates a deep copy of this entity. Used internally to capture pre-mutation state for event payloads.
     */
    override fun clone(): ReactiveEntity<K, R>
}
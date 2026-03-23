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
     * A flow of entity change events that can be observed by collectors.
     */
    val changes: SharedFlow<MutationEvent<K, R>>

    /**
     * Publishes an event to all subscribers, asynchronously.
     */
    fun emitAsync(event: MutationEvent<K, R>)

    /**
     * Subscribes to all mutation events on this entity.
     *
     * @param action The suspend function invoked for each mutation event
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribe(action: suspend (MutationEvent<K, R>) -> Unit): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>

    /**
     * Subscribes to all mutation events on this entity using a Java [Consumer].
     *
     * @param action The consumer invoked for each mutation event
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribe(action: Consumer<in MutationEvent<K, R>>): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
        subscribe(action::accept)

    /**
     * Subscribes to mutation events of the specified types, using a Java [Consumer].
     *
     * @param eventTypes The mutation event types to filter on
     * @param action The consumer invoked for each matching event
     * @return A subscription handle that can be cancelled to stop receiving events
     */
    fun subscribe(vararg eventTypes: MutationEvent.Type, action: Consumer<in MutationEvent<K, R>>):
        LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>

    /**
     * Permanently closes this entity and releases its publisher resources.
     *
     * After closing [subscribe] throw [IllegalStateException]. Idempotent: subsequent calls are safe no-ops.
     */
    override fun close()

    /**
     * Creates a deep copy of this entity. Used internally to capture pre-mutation state for event payloads.
     */
    override fun clone(): ReactiveEntity<K, R>
}
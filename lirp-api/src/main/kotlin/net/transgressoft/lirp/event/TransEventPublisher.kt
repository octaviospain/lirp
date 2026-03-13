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
import kotlinx.coroutines.flow.SharedFlow

/**
 * A publisher of [TransEvent]s that implements the reactive streams [Flow.Publisher] interface.
 *
 * This interface represents the source of events in the reactive stream, publishing
 * events to interested subscribers. It serves as a bridge between the standard
 * Java Flow API and lirp event system.
 *
 * @param ET The specific type of [EventType] associated with this publisher
 * @param E The specific type of [TransEvent] published by this publisher
 */
interface TransEventPublisher<ET : EventType, out E : TransEvent<ET>> : Flow.Publisher<@UnsafeVariance E> {

    /**
     * A flow of entity change events that collectors can observe.
     */
    val changes: SharedFlow<E>

    /**
     * Publishes an event to all subscribers, asynchronously.
     */
    fun emitAsync(event: @UnsafeVariance E)

    fun subscribe(action: suspend (E) -> Unit): TransEventSubscription<in TransEntity, ET, @UnsafeVariance E>

    /**
     * Legacy compatibility method for Java-style Consumer subscriptions.
     * Consider migrating to the Kotlin Flow-based subscription method instead.
     */
    fun subscribe(action: Consumer<in E>): TransEventSubscription<in TransEntity, ET, @UnsafeVariance E> = subscribe(action::accept)

    fun subscribe(vararg eventTypes: ET, action: suspend (E) -> Unit): TransEventSubscription<in TransEntity, ET, @UnsafeVariance E>

    fun activateEvents(vararg types: @UnsafeVariance ET)

    fun disableEvents(vararg types: @UnsafeVariance ET)
}
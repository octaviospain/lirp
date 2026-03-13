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

import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.TransEventSubscription
import java.time.LocalDateTime
import java.util.concurrent.Flow
import java.util.function.Consumer
import kotlinx.coroutines.flow.SharedFlow

/**
 * Represents an entity that can be reactive to changes in its properties. Reactive in the way that
 * regarding its internal logic, it can create a logic reaction on the subscribed entities.
 *
 * @param K the type of the entity's id.
 * @param R the type of the entity.
 */
interface ReactiveEntity<K, R : ReactiveEntity<K, R>> :
    IdentifiableEntity<K>,
    Flow.Publisher<MutationEvent<K, R>> where K : Comparable<K> {

    val lastDateModified: LocalDateTime

    /**
     * A flow of entity change events that can be observed by collectors.
     */
    val changes: SharedFlow<MutationEvent<K, R>>

    /**
     * Publishes an event to all subscribers, asynchronously.
     */
    fun emitAsync(event: MutationEvent<K, R>)

    /**
     * Legacy compatibility method for Java-style Consumer subscriptions.
     * Consider migrating to the Kotlin Flow-based subscription method instead.
     */
    fun subscribe(action: suspend (MutationEvent<K, R>) -> Unit): TransEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>

    fun subscribe(action: Consumer<in MutationEvent<K, R>>): TransEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
        subscribe(action::accept)

    fun subscribe(vararg eventTypes: MutationEvent.Type, action: Consumer<in MutationEvent<K, R>>):
        TransEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>

    override fun clone(): ReactiveEntity<K, R>
}
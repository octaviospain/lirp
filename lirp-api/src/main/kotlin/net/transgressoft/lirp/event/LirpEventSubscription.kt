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

/**
 * A specialized [java.util.concurrent.Flow.Subscription] that connects subscribers to [LirpEventPublisher]
 * instances in the lirp event system.
 *
 * This interface extends the standard Java Flow API with additional context specific
 * to the lirp library, providing access to the source publisher of events.
 *
 * @param T The type of entities contained in the events this subscription handles
 * @param ET The type of event type
 * @param E The type of event this subscription handles
 *
 * @see [LirpEventPublisher]
 * @see [LirpEventSubscriber]
 */
interface LirpEventSubscription<T : LirpEntity, ET: EventType, E : LirpEvent<ET>> : Flow.Subscription {
    /**
     * The publisher that is the source of events for this subscription.
     *
     * This reference allows subscribers to interact with or query the publisher if needed.
     */
    val source: LirpEventPublisher<ET, E>
}
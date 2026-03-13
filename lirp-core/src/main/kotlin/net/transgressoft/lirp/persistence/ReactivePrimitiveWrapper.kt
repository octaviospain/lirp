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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.TransEventPublisher

/**
 * Abstract base class for reactive primitive wrappers that implements common functionality.
 *
 * This class extends [ReactiveEntityBase] to provide reactive behavior for primitive values.
 * It handles the notification of subscribers when the wrapped value changes and defines
 * the basic structure that concrete reactive primitive implementations must follow.
 *
 * @param R The concrete type of the reactive primitive wrapper
 * @param V The type of primitive value being wrapped, which must be comparable
 * @property id The unique identifier for this reactive primitive
 * @param initialValue The initial value to assign to this primitive, can be null
 */
abstract class ReactivePrimitiveWrapper<R : ReactivePrimitiveWrapper<R, V>, V : Comparable<V>>(
    override val id: String,
    initialValue: V?,
    publisherFactory: () -> TransEventPublisher<MutationEvent.Type, MutationEvent<String, ReactivePrimitive<V>>> = { FlowEventPublisher(id) }
) : ReactiveEntityBase<String, ReactivePrimitive<V>>(publisherFactory), ReactivePrimitive<V> {
    /**
     * The current value of this reactive primitive.
     *
     * When this property is modified, subscribers will be notified of the change.
     * The implementation uses [mutateAndPublish] to track changes and trigger events.
     */
    override var value: V? = initialValue
        set(value) {
            mutateAndPublish(value, field) {
                field = value
            }
        }

    /**
     * A generated unique identifier that combines the ID and current value.
     *
     * This provides a way to uniquely identify this primitive instance even when
     * multiple instances share the same ID but have different values.
     */
    override val uniqueId
        get() = "$id-$value"
}
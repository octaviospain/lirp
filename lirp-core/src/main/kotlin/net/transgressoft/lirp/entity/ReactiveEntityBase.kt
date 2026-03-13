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

import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.MutationEvent.Type.MUTATE
import net.transgressoft.lirp.event.ReactiveMutationEvent
import net.transgressoft.lirp.event.TransEventPublisher
import net.transgressoft.lirp.event.TransEventSubscription
import mu.KotlinLogging
import java.time.LocalDateTime
import java.util.concurrent.Flow
import java.util.function.Consumer
import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstract base class that provides reactive functionality for entities, enabling them to notify subscribers
 * about property changes through a reactive flow-based pattern.
 *
 * This class implements the [ReactiveEntity] interface and manages subscriptions using Kotlin Flows.
 * When properties of the entity change, all subscribers are automatically notified with both the updated
 * entity state and the previous state.
 *
 * This implementation uses lazy initialization for the event publisher - the publisher infrastructure
 * (channels, flows, and coroutines) is only created when the first subscriber registers. This significantly
 * reduces memory overhead for entities that are never observed, which is especially beneficial in applications
 * with thousands of reactive entities.
 *
 * @param K The type of the entity's unique identifier, which must implement [Comparable]
 * @param R The concrete type of the reactive entity that extends this class
 *
 * @see ReactiveEntity
 * @see MutationEvent
 */
abstract class ReactiveEntityBase<K, R : ReactiveEntity<K, R>>(
    private val publisherFactory: () -> TransEventPublisher<MutationEvent.Type, MutationEvent<K, R>>
) : ReactiveEntity<K, R> where K : Comparable<K> {
    private val log = KotlinLogging.logger {}

    /**
     * Convenience constructor that creates a default FlowEventPublisher with the entity's class name.
     */
    protected constructor() : this({ FlowEventPublisher("ReactiveEntity") })

    /**
     * Convenience constructor that creates a default FlowEventPublisher with a custom name.
     *
     * @param entityName The name to use for the publisher (useful for debugging and logging)
     */
    protected constructor(entityName: String) : this({ FlowEventPublisher(entityName) })

    /**
     * The lazily initialized publisher. Only created when the first subscriber registers.
     */
    @Volatile
    private var _publisher: TransEventPublisher<MutationEvent.Type, MutationEvent<K, R>>? = null

    /**
     * Gets the publisher, creating it lazily if needed. Thread-safe using double-checked locking.
     */
    private val publisher: TransEventPublisher<MutationEvent.Type, MutationEvent<K, R>>
        get() =
            _publisher ?: synchronized(this) {
                _publisher ?: publisherFactory().also { newPublisher ->
                    // A reactive entity only emits MUTATE events because it
                    // cannot create, delete, or read itself
                    newPublisher.activateEvents(MUTATE)
                    _publisher = newPublisher
                }
            }

    /**
     * Determines whether events should be emitted. Returns true only if the publisher has been initialized,
     * which happens when the first subscriber registers.
     *
     * For in-process publishers like FlowEventPublisher, this provides memory optimization by avoiding
     * event emission for entities without subscribers.
     *
     * For distributed publishers (e.g., Kafka), once initialized, this will always return true since
     * we cannot determine if remote consumers exist.
     */
    private val shouldEmit: Boolean
        get() = _publisher != null

    /**
     * The timestamp when this entity was last modified.
     * Automatically updated whenever a property is changed via [mutateAndPublish].
     */
    override var lastDateModified: LocalDateTime = LocalDateTime.now()
        protected set

    /**
     * A flow of entity change events that collectors can observe.
     * Accessing this property will trigger lazy initialization of the publisher.
     */
    override val changes: SharedFlow<MutationEvent<K, R>>
        get() = publisher.changes

    override fun emitAsync(event: MutationEvent<K, R>) = publisher.emitAsync(event)

    override fun subscribe(action: suspend (MutationEvent<K, R>) -> Unit):
        TransEventSubscription<in TransEntity, MutationEvent.Type, MutationEvent<K, R>> = publisher.subscribe(action)

    override fun subscribe(subscriber: Flow.Subscriber<in MutationEvent<K, R>>?) = publisher.subscribe(subscriber)

    override fun subscribe(vararg eventTypes: MutationEvent.Type, action: Consumer<in MutationEvent<K, R>>):
        TransEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> {
        require(MUTATE in eventTypes) {
            throw IllegalArgumentException("Only UPDATE event is supported for reactive entities")
        }
        return subscribe(action::accept)
    }

    /**
     * Sets a property value and notifies all subscribers if the value has changed.
     *
     * This method implements the reactive pattern - it:
     * 1. Compares the new value with the old value
     * 2. If different, captures the entity state before the change
     * 3. Applies the new value using the provided property setter
     * 4. Updates the last modified timestamp
     * 5. Notifies all subscribers with both the updated and previous entity states (only if the publisher is initialized)
     *
     * @param T The type of the property being modified
     * @param newValue The new value to set
     * @param oldValue The current value of the property
     * @param propertySetAction A consumer that actually sets the property's value
     */
    @Suppress("UNCHECKED_CAST")
    @JvmOverloads
    protected fun <T> mutateAndPublish(newValue: T, oldValue: T, propertySetAction: (T) -> Unit = {}) {
        if (newValue != oldValue) {
            val entityBeforeChange = clone()
            propertySetAction(newValue)
            lastDateModified = LocalDateTime.now()
            if (shouldEmit) {
                log.trace { "Firing entity update event from $entityBeforeChange to $this" }
                publisher.emitAsync(ReactiveMutationEvent(this as R, entityBeforeChange as R))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun <T> mutateAndPublish(mutationAction: () -> T): T {
        val entityBeforeChange = clone()
        val result = mutationAction()
        if (entityBeforeChange == this) {
            log.warn {
                "Attempt to publish update event from a mutation when object comparison was false. " +
                    "Consider implementing equals() and hashcode() that implies a mutation in instance variables affected by the mutationAction"
            }
        } else {
            lastDateModified = LocalDateTime.now()
            if (shouldEmit) {
                log.trace { "Firing entity update event from $entityBeforeChange to $this" }
                publisher.emitAsync(ReactiveMutationEvent(this as R, entityBeforeChange as R))
            }
        }
        return result
    }
}
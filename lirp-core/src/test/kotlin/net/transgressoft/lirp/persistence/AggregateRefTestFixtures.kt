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

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Test entity representing a customer aggregate root.
 *
 * Used as the target of aggregate references in [AggregateRefDeclarationTest] and [AggregateRefResolutionTest].
 */
@Serializable
data class Customer(
    override val id: Int,
    var name: String
) : ReactiveEntityBase<Int, Customer>() {
    override val uniqueId: String get() = "customer-$id"

    override fun clone(): Customer = copy()

    fun updateName(newName: String) = mutateAndPublish(newName, name) { name = it }
}

/**
 * Test entity representing an order that holds a typed reference to a [Customer].
 *
 * The [customer] property demonstrates KSP-generated reference wiring: the [ReactiveEntityRef]
 * annotation triggers generation of [Order_LirpRefAccessor] at compile time. The [Transient]
 * annotation prevents kotlinx-serialization from attempting to serialize the delegate object.
 *
 * Example usage after adding to a repository:
 * ```kotlin
 * val customer: Optional<Customer> = order.customer.resolve()
 * ```
 */
@Serializable
data class Order(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, Order>() {
    override val uniqueId: String get() = "order-$id"

    @ReactiveEntityRef(bubbleUp = false)
    @Transient
    val customer by aggregateRef<Customer, Int> { customerId }

    override fun clone(): Order = copy()
}

/**
 * Test entity representing an order that enables bubble-up propagation from [Customer] mutations.
 *
 * Used in [net.transgressoft.lirp.event.AggregateBubbleUpTest] to verify that mutations on the
 * referenced [Customer] are forwarded to this entity's subscribers as [net.transgressoft.lirp.event.AggregateMutationEvent].
 */
@Serializable
data class BubbleUpOrder(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, BubbleUpOrder>() {
    override val uniqueId: String get() = "bubble-up-order-$id"

    @ReactiveEntityRef(bubbleUp = true)
    @Transient
    val customer by aggregateRef<Customer, Int> { customerId }

    override fun clone(): BubbleUpOrder = copy()
}

/**
 * Test entity for transitive non-propagation: B references A with bubble-up=true.
 *
 * Used in [net.transgressoft.lirp.event.AggregateBubbleUpTest] to verify single-level bubble-up.
 * When A mutates, B receives the event. When B re-emits, its own parent should NOT receive it
 * as a further bubble-up (single-level only).
 */
@Serializable
data class EntityA(
    override val id: Int,
    var value: String
) : ReactiveEntityBase<Int, EntityA>() {
    override val uniqueId: String get() = "entity-a-$id"

    override fun clone(): EntityA = copy()

    fun updateValue(newValue: String) = mutateAndPublish(newValue, value) { value = it }
}

/**
 * Middle entity in transitive-propagation test: references [EntityA] with bubble-up=true.
 */
@Serializable
data class EntityB(
    override val id: Int,
    var entityAId: Int
) : ReactiveEntityBase<Int, EntityB>() {
    override val uniqueId: String get() = "entity-b-$id"

    @ReactiveEntityRef(bubbleUp = true)
    @Transient
    val refA by aggregateRef<EntityA, Int> { entityAId }

    override fun clone(): EntityB = copy()
}

/**
 * Top entity in transitive-propagation test: references [EntityB] with bubble-up=true.
 *
 * A mutation in [EntityA] should reach [EntityB]'s subscribers, but NOT [EntityC]'s subscribers —
 * propagation is single-level.
 */
@Serializable
data class EntityC(
    override val id: Int,
    var entityBId: Int
) : ReactiveEntityBase<Int, EntityC>() {
    override val uniqueId: String get() = "entity-c-$id"

    @ReactiveEntityRef(bubbleUp = true)
    @Transient
    val refB by aggregateRef<EntityB, Int> { entityBId }

    override fun clone(): EntityC = copy()
}

/**
 * Test entity with CASCADE delete action: removing this entity from its repository also removes
 * the referenced [Customer] from the customer repository.
 */
@Serializable
data class CascadeOrder(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, CascadeOrder>() {
    override val uniqueId: String get() = "cascade-order-$id"

    @ReactiveEntityRef(onDelete = CascadeAction.CASCADE)
    @Transient
    val customer by aggregateRef<Customer, Int> { customerId }

    override fun clone(): CascadeOrder = copy()
}

/**
 * Test entity with DETACH delete action and bubble-up enabled: removing this entity cancels the
 * bubble-up subscription but leaves the referenced [Customer] in its repository.
 */
@Serializable
data class DetachOrder(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, DetachOrder>() {
    override val uniqueId: String get() = "detach-order-$id"

    @ReactiveEntityRef(bubbleUp = true, onDelete = CascadeAction.DETACH)
    @Transient
    val customer by aggregateRef<Customer, Int> { customerId }

    override fun clone(): DetachOrder = copy()
}

/**
 * Test entity with NONE delete action: removing this entity does nothing to the referenced [Customer].
 */
@Serializable
data class NoneOrder(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, NoneOrder>() {
    override val uniqueId: String get() = "none-order-$id"

    @ReactiveEntityRef(onDelete = CascadeAction.NONE)
    @Transient
    val customer by aggregateRef<Customer, Int> { customerId }

    override fun clone(): NoneOrder = copy()
}
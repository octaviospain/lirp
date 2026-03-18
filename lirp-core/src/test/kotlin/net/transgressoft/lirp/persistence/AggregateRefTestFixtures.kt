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
    val name: String
) : ReactiveEntityBase<Int, Customer>() {
    override val uniqueId: String get() = "customer-$id"

    override fun clone(): Customer = copy()
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
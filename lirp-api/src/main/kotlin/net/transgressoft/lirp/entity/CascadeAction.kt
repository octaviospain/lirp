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

/**
 * Defines the action to take on a referenced aggregate entity when the referencing entity is
 * removed from its repository or closed.
 *
 * Configured per reference via [@Aggregate][net.transgressoft.lirp.persistence.Aggregate]:
 *
 * ```kotlin
 * @Aggregate(onDelete = CascadeAction.CASCADE)
 * val order by aggregate<Long, Order> { orderId }
 * ```
 *
 * @see net.transgressoft.lirp.persistence.Aggregate
 */
enum class CascadeAction {

    /**
     * Removes the referenced entity from its own repository when the referencing entity is deleted.
     *
     * Deletion is unconditional — no reference counting is performed. If multiple entities reference
     * the same aggregate, each cascade will attempt to remove it independently.
     */
    CASCADE,

    /**
     * Cancels any active bubble-up subscription to the referenced entity when the referencing entity
     * is deleted, but leaves the referenced entity untouched in its repository.
     *
     * This is the default behavior.
     */
    DETACH,

    /**
     * Takes no action on the referenced entity when the referencing entity is deleted.
     *
     * Bubble-up subscriptions, if active, are left in place. Use with caution — subscription
     * cleanup becomes the caller's responsibility.
     */
    NONE,

    /**
     * Prevents deletion of the referenced entity if it is still referenced by other entities.
     * Throws [IllegalStateException] at deletion time when other references exist.
     * The entity triggering the cascade is excluded from the reference check.
     */
    RESTRICT
}
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

import net.transgressoft.lirp.entity.IdentifiableEntity
import java.util.Optional

/**
 * A lazily-resolved reference to another aggregate entity stored in a [Registry].
 *
 * Instances are returned by property delegates declared with
 * [@Aggregate][Aggregate] in entity classes. The delegate holds only the raw ID
 * of the referenced entity; the actual entity lookup is deferred to the first [resolve] call.
 *
 * [resolve] returns [Optional] for Java interoperability — callers do not need Kotlin-specific
 * constructs to handle absent references. Kotlin callers can use [Optional.orElse] or convert
 * to a nullable via [Optional.orElseGet].
 *
 * The reference is stateless with respect to caching unless the underlying delegate implementation
 * chooses to cache the resolved result. If the referenced entity's ID changes (e.g. via a
 * `mutateAndPublish` call), any cached resolution must be invalidated.
 *
 * Example:
 * ```kotlin
 * val orderRef: ReactiveEntityReference<Long, Order> = invoice.order
 * val order: Optional<Order> = orderRef.resolve()
 * order.ifPresent { println(it.id) }
 * ```
 *
 * @param K the type of the referenced entity's ID, which must be [Comparable]
 * @param E the type of the referenced entity
 */
interface ReactiveEntityReference<K : Comparable<K>, E : IdentifiableEntity<K>> {

    /**
     * The raw ID of the referenced entity.
     *
     * This value is sourced from the ID-provider lambda captured at entity construction time.
     * It does not trigger any repository lookup.
     */
    val referenceId: K

    /**
     * Lazily resolves the referenced entity from its bound [Registry].
     *
     * Returns [Optional.empty] if the referenced entity does not exist in the registry or if
     * the reference has not yet been bound to a registry (e.g., before the owning entity has
     * been added to a repository).
     *
     * @return an [Optional] containing the resolved entity, or [Optional.empty] if absent
     */
    fun resolve(): Optional<E>
}
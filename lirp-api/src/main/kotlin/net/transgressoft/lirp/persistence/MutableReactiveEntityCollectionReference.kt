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

/**
 * A lazily-resolved, mutable reference to a collection of aggregate entities stored in a [Registry].
 *
 * Extends [ReactiveEntityCollectionReference] with mutation methods that synchronize the backing ID store
 * maintained by the delegate implementation. Changes made via [add], [remove], or [clear] are immediately
 * reflected in [referenceIds] and trigger mutation event emission after registry binding.
 *
 * The `idSetter` write-back — which propagates ID changes back to the owning entity's serializable field —
 * is handled by the concrete delegate implementation, not this interface.
 *
 * This interface also extends [MutableCollection]`<E>` to enable Java interoperability:
 * callers can write `entity.getItems().add(item)` directly from Java code.
 *
 * @param K the type of the referenced entities' IDs, which must be [Comparable]
 * @param E the type of the referenced entities
 */
interface MutableReactiveEntityCollectionReference<K : Comparable<K>, E : IdentifiableEntity<K>> :
    ReactiveEntityCollectionReference<K, E>,
    MutableCollection<E> {

    /**
     * Returns a [MutableIterator] over a detached snapshot of the resolved entities.
     *
     * Mutations performed through this iterator (e.g., [MutableIterator.remove] during iteration) operate
     * on the snapshot copy only and do **not** propagate to the backing ID store, trigger `idSetter`
     * write-back, or emit mutation events. For structural modifications that must be persisted, use
     * [add], [remove], or [clear] directly.
     */
    override fun iterator(): MutableIterator<E>

    /**
     * Adds [element] to this collection if it is not already present (set semantics) or appended
     * (list semantics depending on the delegate). Updates the backing ID store and triggers
     * mutation event emission.
     *
     * @return `true` if the collection changed as a result of the operation
     */
    override fun add(element: E): Boolean

    /**
     * Removes [element] from this collection if it is present. Updates the backing ID store and
     * triggers mutation event emission.
     *
     * @return `true` if the collection changed as a result of the operation
     */
    override fun remove(element: E): Boolean

    /**
     * Removes all elements from this collection. Updates the backing ID store and triggers
     * mutation event emission.
     */
    override fun clear()
}
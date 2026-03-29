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
 * A lazily-resolved reference to a collection of aggregate entities stored in a [Registry].
 *
 * Instances are returned by property delegates declared with `@Aggregate` in entity classes,
 * using either `aggregateList` (ordered, duplicates allowed) or `aggregateSet` (unique elements)
 * factory functions. The delegate holds only the raw IDs of the referenced entities; actual entity
 * lookups are deferred to the first [resolveAll] call.
 *
 * Concrete delegates ([AggregateListRefDelegate] and [AggregateSetRefDelegate]) narrow the return
 * types of [referenceIds] and [resolveAll] to `List` and `Set` respectively via covariant overrides.
 *
 * Example:
 * ```kotlin
 * @Aggregate
 * val items by aggregateList<Int, AudioItem> { itemIds }
 *
 * // Resolving all referenced entities:
 * val resolved: Collection<AudioItem> = playlist.items.resolveAll()
 * ```
 *
 * @param K the type of the referenced entities' IDs, which must be [Comparable]
 * @param E the type of the referenced entities
 */
interface ReactiveEntityCollectionReference<K : Comparable<K>, E : IdentifiableEntity<K>> {

    /**
     * The raw IDs of all referenced entities.
     *
     * These values are sourced from the ID-provider lambda captured at entity construction time.
     * Accessing this property does not trigger any registry lookup.
     */
    val referenceIds: Collection<K>

    /**
     * Lazily resolves all referenced entities from the bound [Registry].
     *
     * Entities whose IDs are not found in the registry are silently omitted from the result.
     * Returns an empty collection if the reference has not yet been bound to a registry (e.g.,
     * before the owning entity has been added to a repository).
     *
     * @return a collection of all resolved entities; never null, may be empty
     */
    fun resolveAll(): Collection<E>
}
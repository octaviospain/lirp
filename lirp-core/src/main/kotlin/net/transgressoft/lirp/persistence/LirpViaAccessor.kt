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

/**
 * Contract for KSP-generated cross-aggregate `via` accessors that expose typed [kotlin.reflect.KProperty1]
 * descriptors for every aggregate-reference property (`@ToOneAggregate` / `@ToManyAggregates`) of an entity.
 *
 * Each entity class with at least one `@ToOneAggregate` / `@ToManyAggregates` property gets a compile-time
 * generated implementation of this interface, named `{EntityName}_LirpViaAccessor` and placed in the
 * same package as the entity. The generated class is discovered at runtime via a convention-based
 * [Class.forName] lookup, mirroring the sibling [LirpRefAccessor] and [LirpIndexAccessor] patterns.
 * Hand-written implementations are not supported — the KSP processor is the single source of truth.
 *
 * Unlike [LirpRefAccessor], this accessor is concerned only with the property-shape metadata required
 * by the cross-aggregate Query DSL (`via … anyMatch / allMatch / noneMatch / where`). It does NOT
 * surface the `aggregateList` vs `aggregateSet` distinction — `via` operates over `Collection<K>`
 * uniformly, regardless of list/set semantics, because cross-aggregate quantifier semantics depend
 * on membership, not order.
 *
 * **Discovery and use.** `RegistryBase.discoverViaAccessors` loads the generated class once per
 * registry on the first entity add, caching the result for subsequent lookups. The companion
 * `RegistryBase.viaAccessorFor(entityClass)` exposes a process-wide cross-class cache used by the
 * cross-aggregate planner in `net.transgressoft.lirp.persistence.query.QueryPlanner` to resolve
 * KProperty1 references to the descriptors that point at the right child entity class.
 *
 * @param T the entity type this accessor was generated for
 * @property collectionEntries one [ViaCollectionAccessorEntry] per collection-typed `@ToManyAggregates`
 *   property (whether declared via `aggregateList`, `aggregateSet`, or their mutable variants)
 * @property singleEntries one [ViaSingleAccessorEntry] per single-entity `@ToOneAggregate` property
 */
interface LirpViaAccessor<T> {

    /**
     * Pre-built collection-reference descriptors for every collection-typed
     * [@ToManyAggregates][ToManyAggregates] property declared on [T].
     *
     * Star-projected K since collection references on the same entity may point to differently-typed IDs.
     */
    val collectionEntries: List<ViaCollectionAccessorEntry<*, T>>

    /**
     * Pre-built single-entity reference descriptors for every single-entity
     * [@ToOneAggregate][ToOneAggregate] property declared on [T].
     *
     * Star-projected K since single references on the same entity may point to differently-typed IDs.
     */
    val singleEntries: List<ViaSingleAccessorEntry<*, T>>
}
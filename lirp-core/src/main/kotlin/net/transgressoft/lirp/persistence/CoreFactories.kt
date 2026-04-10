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
 * Creates a read-only projection that groups entities from a source collection by a secondary key.
 *
 * The returned [ProjectionMap] lazily initializes on the first Kotlin `by`-delegation access,
 * building its initial state from the source's current contents. When the source is a
 * [MutableAggregateList] or [MutableAggregateSet], subsequent mutations are reflected
 * automatically without any manual notification. For other [AggregateCollectionRef] implementations,
 * only the initial snapshot is captured.
 *
 * Keys are maintained in natural sorted order via a [java.util.TreeMap] backing. The projected
 * map is read-only; mutations must flow through the source collection.
 *
 * Usage:
 * ```kotlin
 * val audioItemsByTitle by projectionMap(::audioItems) { it.title }
 * ```
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param sourceRef lambda returning the source collection (supports `::property` syntax)
 * @param keyExtractor trailing-lambda grouping function that extracts the projection key from an entity
 * @return a [ProjectionMap] delegate grouping entities by [keyExtractor]
 */
fun <K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>> projectionMap(
    sourceRef: () -> AggregateCollectionRef<K, E>,
    keyExtractor: (E) -> PK
): ProjectionMap<K, PK, E> = ProjectionMap(sourceRef, keyExtractor)
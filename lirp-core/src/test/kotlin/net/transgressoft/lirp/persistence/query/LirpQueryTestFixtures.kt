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

package net.transgressoft.lirp.persistence.query

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.MutableSoftDeletable
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.Registry
import net.transgressoft.lirp.persistence.VolatileRepository
import java.time.Instant

/**
 * Test entity with a mix of indexed and non-indexed properties for Query DSL tests.
 */
data class Product(
    override val id: Int,
    @Indexed val category: String,
    val price: Double,
    val stock: Int,
    val name: String,
    override val uniqueId: String = "product-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/**
 * Test repository for [Product] entities.
 */
class ProductVolatileRepo(context: LirpContext = LirpContext.default) :
    VolatileRepository<Int, Product>(context, "Products") {
    fun create(id: Int, category: String, price: Double, stock: Int, name: String): Product =
        Product(id, category, price, stock, name).also { add(it) }
}

/**
 * Test entity with multiple indexed fields for multi-index AND tests.
 */
data class Employee(
    override val id: Int,
    @Indexed val department: String,
    @Indexed val level: Int,
    val salary: Double,
    val name: String,
    override val uniqueId: String = "employee-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/**
 * Test repository for [Employee] entities.
 */
class EmployeeVolatileRepo(context: LirpContext = LirpContext.default) :
    VolatileRepository<Int, Employee>(context, "Employees") {
    fun create(id: Int, department: String, level: Int, salary: Double, name: String): Employee =
        Employee(id, department, level, salary, name).also { add(it) }
}

/**
 * Test entity that is both soft-deletable and carries an [Indexed] property.
 * Used to verify that indexed predicates combined with [QueryBuilder.includeDeleted] /
 * [QueryBuilder.onlyDeleted] correctly bypass the index (since soft-deleted entities are
 * deindexed) and fall back to a raw-sequence scan.
 */
class IndexedSoftDeletableTrack(
    override val id: Int,
    genre: String
) : ReactiveEntityBase<Int, IndexedSoftDeletableTrack>(), IdentifiableEntity<Int>, MutableSoftDeletable {
    override val uniqueId: String get() = "indexed-soft-deletable-track-$id"

    @Indexed
    var genre: String by reactiveProperty(genre)

    override var deletedAt: Instant? by reactiveProperty(null)

    override fun clone(): IndexedSoftDeletableTrack = IndexedSoftDeletableTrack(id, genre).also { it.deletedAt = deletedAt }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IndexedSoftDeletableTrack) return false
        return id == other.id && genre == other.genre && deletedAt == other.deletedAt
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + genre.hashCode()) + (deletedAt?.hashCode() ?: 0)

    override fun toString(): String = "IndexedSoftDeletableTrack(id=$id, genre='$genre', deletedAt=$deletedAt)"
}

/**
 * Repository for [IndexedSoftDeletableTrack] entities.
 */
class IndexedSoftDeletableTrackRepo(context: LirpContext = LirpContext.default) :
    VolatileRepository<Int, IndexedSoftDeletableTrack>(context, "IndexedSoftDeletableTracks") {
    fun create(id: Int, genre: String): IndexedSoftDeletableTrack =
        IndexedSoftDeletableTrack(id, genre).also { add(it) }
}

/**
 * Soft-deletable child entity referenced by [SoftDeletablePlaylist.trackIds].
 * Used to exercise `via()` query visibility under [QueryBuilder.includeDeleted] and
 * [QueryBuilder.onlyDeleted] across a cross-aggregate parent–child relationship.
 */
class SoftDeletableTrack(
    override val id: Int,
    title: String,
    price: Double
) : ReactiveEntityBase<Int, SoftDeletableTrack>(), IdentifiableEntity<Int>, MutableSoftDeletable {
    override val uniqueId: String get() = "soft-deletable-track-$id"

    var title: String by reactiveProperty(title)
    var price: Double by reactiveProperty(price)

    override var deletedAt: Instant? by reactiveProperty(null)

    override fun clone(): SoftDeletableTrack =
        SoftDeletableTrack(id, title, price).also { it.deletedAt = deletedAt }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoftDeletableTrack) return false
        return id == other.id && title == other.title && price == other.price && deletedAt == other.deletedAt
    }

    override fun hashCode(): Int =
        31 * (31 * (31 * id.hashCode() + title.hashCode()) + price.hashCode()) + (deletedAt?.hashCode() ?: 0)

    override fun toString(): String = "SoftDeletableTrack(id=$id, title='$title', price=$price, deletedAt=$deletedAt)"
}

/**
 * Repository for [SoftDeletableTrack] entities.
 */
class SoftDeletableTrackRepo(context: LirpContext = LirpContext.default) :
    VolatileRepository<Int, SoftDeletableTrack>(context, "SoftDeletableTracks") {
    fun create(id: Int, title: String, price: Double): SoftDeletableTrack =
        SoftDeletableTrack(id, title, price).also { add(it) }
}

/**
 * Soft-deletable parent entity whose [trackIds] collection is consumed by collection `via()`
 * queries and whose nullable [ownerTrackId] single reference is consumed by `via ... where { }`
 * queries. Used alongside [SoftDeletableTrack] to exercise cross-aggregate `via()` visibility
 * under [QueryBuilder.includeDeleted] and [QueryBuilder.onlyDeleted].
 */
class SoftDeletablePlaylist(
    override val id: Int,
    name: String,
    trackIds: List<Int>,
    ownerTrackId: Int? = null
) : ReactiveEntityBase<Int, SoftDeletablePlaylist>(), IdentifiableEntity<Int>, MutableSoftDeletable {
    override val uniqueId: String get() = "soft-deletable-playlist-$id"

    var name: String by reactiveProperty(name)
    var trackIds: List<Int> by reactiveProperty(trackIds)
    var ownerTrackId: Int? by reactiveProperty(ownerTrackId)

    override var deletedAt: Instant? by reactiveProperty(null)

    override fun clone(): SoftDeletablePlaylist =
        SoftDeletablePlaylist(id, name, trackIds, ownerTrackId).also { it.deletedAt = deletedAt }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoftDeletablePlaylist) return false
        return id == other.id &&
            name == other.name &&
            trackIds == other.trackIds &&
            ownerTrackId == other.ownerTrackId &&
            deletedAt == other.deletedAt
    }

    override fun hashCode(): Int =
        31 * (31 * (31 * (31 * id.hashCode() + name.hashCode()) + trackIds.hashCode()) + (ownerTrackId ?: 0)) +
            (deletedAt?.hashCode() ?: 0)

    override fun toString(): String =
        "SoftDeletablePlaylist(id=$id, name='$name', trackIds=$trackIds, ownerTrackId=$ownerTrackId, deletedAt=$deletedAt)"
}

/**
 * Repository for [SoftDeletablePlaylist] entities.
 */
class SoftDeletablePlaylistRepo(context: LirpContext = LirpContext.default) :
    VolatileRepository<Int, SoftDeletablePlaylist>(context, "SoftDeletablePlaylists") {
    fun create(id: Int, name: String, trackIds: List<Int>, ownerTrackId: Int? = null): SoftDeletablePlaylist =
        SoftDeletablePlaylist(id, name, trackIds, ownerTrackId).also { add(it) }
}

/**
 * Executes [via] under both [ViaStrategy.PER_PARENT_LOOP] and [ViaStrategy.HASH_JOIN] against
 * the same [parentRegistry] with the shared [query] visibility flags, and returns both result
 * sets as a [Pair]. The first element is the per-parent-loop result set; the second is the
 * hash-join result set.
 *
 * Pass an equality assertion on both elements of the pair to verify the strict-mirror
 * strategy-equivalence invariant: given the same [query], both join strategies must return
 * identical parent sets regardless of the visibility mode (active-only, includeDeleted, or
 * onlyDeleted). Example usage:
 *
 * ```kotlin
 * val (ppl, hj) = viaResultsUnderBothStrategies(via, playlists, query)
 * ppl shouldBe hj
 * ```
 *
 * @param via a normalised Via* predicate (pass a [ViaAnyMatch], [ViaAllMatch], [ViaNoneMatch], or [ViaWhere] node)
 * @param parentRegistry the registry holding the parent entities
 * @param query a [Query] carrying the desired visibility flags; defaults to active-only
 * @return a [Pair] of (per-parent-loop result set, hash-join result set)
 */
internal fun <T : IdentifiableEntity<*>> viaResultsUnderBothStrategies(
    via: Predicate<T>,
    parentRegistry: Registry<*, T>,
    query: Query<T> = activeOnlyQuery()
): Pair<Set<T>, Set<T>> {
    val executor = ViaJoinExecutor<T>()
    val visibility = query.visibility()
    val perParentLoopSet = executor.perParentLoop(via, parentRegistry, visibility).toSet()
    val hashJoinSet = executor.hashJoin(via, parentRegistry, visibility).toSet()
    return Pair(perParentLoopSet, hashJoinSet)
}
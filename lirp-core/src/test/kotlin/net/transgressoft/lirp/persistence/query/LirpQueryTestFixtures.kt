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
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
import net.transgressoft.lirp.persistence.Indexed
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.VolatileRepository

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
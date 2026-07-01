/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.fleet.person

import net.transgressoft.lirp.persistence.LirpRepository
import net.transgressoft.lirp.persistence.query.eq
import net.transgressoft.lirp.persistence.query.query
import net.transgressoft.lirp.persistence.sql.SqlRepository
import java.util.UUID
import javax.sql.DataSource

/**
 * Factory and query surface for the [Person] aggregate.
 *
 * Auto-registers in [net.transgressoft.lirp.persistence.LirpContext.default] on construction
 * and deregisters on [close].
 *
 * Factory methods build entities and add them to the repository in one call.
 */
@LirpRepository
class PersonRepository(dataSource: DataSource) :
    SqlRepository<UUID, Person>(dataSource, Person_LirpTableDef) {

    /**
     * Creates a new person belonging to [tenantId] and adds them to the repository.
     *
     * @return the newly created [Person], already assigned a random id.
     */
    fun register(tenantId: UUID, firstName: String, lastName: String): Person =
        Person(UUID.randomUUID(), tenantId).apply {
            this.firstName = firstName
            this.lastName = lastName
        }.also(::add)

    /**
     * Returns all persons belonging to the given tenant.
     */
    fun findByTenant(tenantId: UUID): List<Person> =
        query<UUID, Person> {
            where { Person::tenantId eq tenantId }
        }.toList()
}
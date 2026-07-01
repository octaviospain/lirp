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

package net.transgressoft.fleet.company

import net.transgressoft.lirp.persistence.LirpRepository
import net.transgressoft.lirp.persistence.query.eq
import net.transgressoft.lirp.persistence.query.query
import net.transgressoft.lirp.persistence.sql.SqlRepository
import java.util.UUID
import javax.sql.DataSource

/**
 * Factory and query surface for the [Company] aggregate.
 *
 * Auto-registers in [net.transgressoft.lirp.persistence.LirpContext.default] on construction
 * and deregisters on [close].
 *
 * Factory methods build entities and add them to the repository in one call.
 */
@LirpRepository
class CompanyRepository(dataSource: DataSource) :
    SqlRepository<UUID, Company>(dataSource, Company_LirpTableDef) {

    /**
     * Creates a new company belonging to [tenantId] and adds it to the repository.
     *
     * @return the newly created [Company], already assigned a random id.
     */
    fun register(tenantId: UUID, name: String): Company =
        Company(UUID.randomUUID(), tenantId).apply {
            this.name = name
        }.also(::add)

    /**
     * Returns all companies belonging to the given tenant.
     */
    fun findByTenant(tenantId: UUID): List<Company> =
        query<UUID, Company> {
            where { Company::tenantId eq tenantId }
        }.toList()

    /**
     * Returns all companies whose [Company.name] matches [name] exactly.
     */
    fun findByName(name: String): List<Company> =
        query<UUID, Company> {
            where { Company::name eq name }
        }.toList()
}
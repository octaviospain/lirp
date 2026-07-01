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

package net.transgressoft.fleet.tenant

import net.transgressoft.lirp.persistence.LirpRepository
import net.transgressoft.lirp.persistence.query.eq
import net.transgressoft.lirp.persistence.query.query
import net.transgressoft.lirp.persistence.sql.SqlRepository
import java.util.UUID
import javax.sql.DataSource

/**
 * Factory and query surface for the [Tenant] aggregate.
 *
 * Auto-registers in [net.transgressoft.lirp.persistence.LirpContext.default] on construction
 * and deregisters on [close]. Tenants do not publish to Kafka — a plain [SqlRepository] is
 * sufficient.
 *
 * Factory methods build entities and add them to the repository in one call.
 */
@LirpRepository
class TenantRepository(dataSource: DataSource) :
    SqlRepository<UUID, Tenant>(dataSource, Tenant_LirpTableDef) {

    /**
     * Creates a new tenant with the given [code] and [name] and adds it to the repository.
     *
     * @return the newly created [Tenant], already assigned a random id.
     */
    fun register(code: String, name: String): Tenant =
        Tenant(UUID.randomUUID()).apply {
            this.code = code
            this.name = name
        }.also(::add)

    /**
     * Returns all tenants whose [Tenant.code] matches [code] exactly.
     */
    fun findByCode(code: String): List<Tenant> =
        query<UUID, Tenant> {
            where { Tenant::code eq code }
        }.toList()
}
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

package net.transgressoft.lirp.persistence.sql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Shared Testcontainers PostgreSQL container and [HikariDataSource] factory for integration tests.
 *
 * The container is started lazily on first [buildDataSource] call and reused across tests within
 * the same JVM process for efficiency.
 */
object PostgresContainerSupport {
    val container: PostgreSQLContainer<Nothing> = PostgreSQLContainer("postgres:18-alpine")

    /**
     * Returns a new [HikariDataSource] connected to the shared PostgreSQL container,
     * starting the container if it is not yet running.
     */
    fun buildDataSource(): HikariDataSource {
        if (!container.isRunning) container.start()
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = 10
            }
        )
    }
}
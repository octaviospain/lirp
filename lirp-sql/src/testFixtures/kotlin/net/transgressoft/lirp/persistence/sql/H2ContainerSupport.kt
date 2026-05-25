/******************************************************************************
 * Copyright (C) 2025  Octavio Calleya Garcia                                 *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 * (at your option) any later version.                                        *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.     *
 ******************************************************************************/

package net.transgressoft.lirp.persistence.sql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID

object H2ContainerSupport {

    /**
     * Builds a fresh in-memory H2 datasource per call. A unique database name guarantees test
     * isolation; `DB_CLOSE_DELAY=-1` keeps the in-memory schema alive for the entire pool lifetime
     * so reopening through a second [SqlRepository] sees the rows committed by the first.
     *
     * `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` aligns H2's identifier handling with the project's
     * primary dialect: unquoted identifiers fold to lowercase (matching PostgreSQL behaviour),
     * so Exposed's quoted lowercase column references (e.g. `"name"`) resolve against the stored
     * table columns rather than against H2's default upper-case folding.
     */
    fun buildH2DataSource(): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl =
                    "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                maximumPoolSize = 4
            }
        )
}
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

import com.zaxxer.hikari.HikariDataSource
import io.kotest.engine.names.WithDataTestName
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException

/**
 * Database configuration for data-driven integration tests.
 *
 * Each instance represents a supported database engine and its [HikariDataSource] factory.
 * Implements [WithDataTestName] so Kotest `withTests` uses the database [name] in test output.
 */
data class DbConfig(val name: String, val buildDataSource: () -> HikariDataSource) : WithDataTestName {
    override fun dataTestName() = name
}

/**
 * Shared test infrastructure for parameterized multi-database integration tests.
 *
 * Provides the list of supported [database configurations][databases], a utility to
 * drop tables between test runs for isolation, and a [withDatabaseTest] helper that
 * guarantees resource cleanup via try/finally.
 */
object DatabaseTestSupport {

    val databases =
        listOf(
            DbConfig("PostgreSQL") { PostgresContainerSupport.buildDataSource() },
            DbConfig("MySQL") { MysqlContainerSupport.buildDataSource() },
            DbConfig("MariaDB") { MariaDbContainerSupport.buildDataSource() },
            DbConfig("SQLite") { SqliteFileSupport.buildDataSource() }
        )

    fun dropTable(dataSource: HikariDataSource, tableDef: SqlTableDef<*>) {
        val db = Database.connect(dataSource)
        val t = ExposedTableInterpreter().interpret(tableDef)
        try {
            transaction(db) { SchemaUtils.drop(t.table) }
        } catch (e: SQLException) {
            val tableNotFound =
                e.sqlState in listOf("42S02", "42P01") ||
                    e.message?.contains("does not exist", ignoreCase = true) == true ||
                    e.message?.contains("Unknown table", ignoreCase = true) == true ||
                    e.message?.contains("no such table", ignoreCase = true) == true
            if (!tableNotFound) throw e
        }
    }

    /**
     * Runs [block] with a fresh [HikariDataSource] from [db], dropping [tableDef] beforehand
     * for isolation. The data source is always closed in a finally block, even if the test fails.
     */
    inline fun withDatabaseTest(db: DbConfig, tableDef: SqlTableDef<*>, block: (HikariDataSource) -> Unit) {
        val dataSource = db.buildDataSource()
        try {
            dropTable(dataSource, tableDef)
            block(dataSource)
        } finally {
            dataSource.close()
        }
    }
}
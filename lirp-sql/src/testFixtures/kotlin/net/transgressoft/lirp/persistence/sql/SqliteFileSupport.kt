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
import java.nio.file.Files

/**
 * Tempfile-backed [HikariDataSource] factory for SQLite integration tests.
 *
 * A fresh database file is created per [buildDataSource] call (registered for
 * `deleteOnExit`); each `withDatabaseTest(...)` invocation therefore runs against
 * an empty isolated database. The locked PRAGMA bundle (`foreign_keys = ON`,
 * `journal_mode = WAL`, `busy_timeout = 5000`, `synchronous = NORMAL`) applies
 * on every pooled connection.
 */
object SqliteFileSupport {

    // PRAGMAs are passed as URL query parameters (xerial sqlite-jdbc parses them via
    // SQLiteConnection.extractPragmasFromFilename). HikariCP's connectionInitSql cannot
    // be used because sqlite-jdbc's Statement.execute(sql) only runs the first statement
    // of a multi-statement string; subsequent PRAGMAs would be silently dropped.
    private const val PRAGMA_QUERY =
        "?foreign_keys=on&journal_mode=wal&busy_timeout=5000&synchronous=normal"

    /**
     * Returns a new [HikariDataSource] connected to a fresh temporary SQLite database
     * file. The file is registered for `deleteOnExit`. The locked PRAGMA bundle is
     * applied on every connection via xerial sqlite-jdbc URL parameters.
     */
    fun buildDataSource(): HikariDataSource {
        val tempFile =
            Files.createTempFile("lirp-sqlite-", ".db")
                .also { it.toFile().deleteOnExit() }
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${tempFile.toAbsolutePath()}$PRAGMA_QUERY"
                maximumPoolSize = 4
            }
        )
    }
}
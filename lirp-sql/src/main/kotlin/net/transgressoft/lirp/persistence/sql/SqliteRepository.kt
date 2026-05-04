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

import net.transgressoft.lirp.entity.ReactiveEntity
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Path

/**
 * Factory namespace for [SqlRepository] instances backed by SQLite.
 *
 * Each factory builds a HikariCP pool with the curated PRAGMA bundle
 * (`foreign_keys = ON`, `journal_mode = WAL`, `busy_timeout = 5000`,
 * `synchronous = NORMAL`) applied via xerial sqlite-jdbc URL parameters,
 * so foreign-key enforcement and WAL concurrent reads apply to every
 * pooled connection. The returned repository owns the pool and closes
 * it on [SqlRepository.close].
 */
object SqliteRepository {

    // PRAGMAs are passed as URL query parameters (xerial sqlite-jdbc parses them via
    // SQLiteConnection.extractPragmasFromFilename). HikariCP's connectionInitSql cannot
    // be used because sqlite-jdbc's Statement.execute(sql) only runs the first statement
    // of a multi-statement string; subsequent PRAGMAs would be silently dropped.
    private const val PRAGMA_QUERY =
        "?foreign_keys=on&journal_mode=wal&busy_timeout=5000&synchronous=normal"

    /**
     * Creates a [SqlRepository] backed by a SQLite database file at [path].
     *
     * The returned repository owns the underlying HikariCP pool (closed when
     * [SqlRepository.close] runs). The locked PRAGMA bundle is applied on every
     * pooled connection.
     *
     * @param path Filesystem path of the SQLite database file. The file is
     *   created on first connection if it does not exist.
     * @param tableDef SQL table definition describing the entity column mapping.
     * @param loadOnInit When `true` (default), rows are loaded immediately;
     *   when `false`, [SqlRepository.load] must be called explicitly.
     */
    @JvmOverloads
    @JvmStatic
    fun <K : Comparable<K>, R : ReactiveEntity<K, R>> fileBacked(
        path: Path,
        tableDef: SqlTableDef<R>,
        loadOnInit: Boolean = true
    ): SqlRepository<K, R> {
        val ds = buildPool(jdbcUrl = "jdbc:sqlite:${path.toAbsolutePath()}$PRAGMA_QUERY", poolSize = 4)
        return SqlRepository(ds, tableDef, ownsDataSource = true, loadOnInit)
    }

    /**
     * Creates a [SqlRepository] backed by an in-memory SQLite database.
     *
     * The pool is pinned to a single connection because each `:memory:`
     * connection in xerial's driver is an independent private database;
     * a multi-connection pool would silently lose schema and data across
     * acquired connections.
     *
     * @param tableDef SQL table definition describing the entity column mapping.
     * @param loadOnInit When `true` (default), rows are loaded immediately;
     *   when `false`, [SqlRepository.load] must be called explicitly.
     */
    @JvmOverloads
    @JvmStatic
    fun <K : Comparable<K>, R : ReactiveEntity<K, R>> inMemory(
        tableDef: SqlTableDef<R>,
        loadOnInit: Boolean = true
    ): SqlRepository<K, R> {
        val ds = buildPool(jdbcUrl = "jdbc:sqlite::memory:$PRAGMA_QUERY", poolSize = 1)
        return SqlRepository(ds, tableDef, ownsDataSource = true, loadOnInit)
    }

    private fun buildPool(jdbcUrl: String, poolSize: Int): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.maximumPoolSize = poolSize
            }
        )
}
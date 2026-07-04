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

import net.transgressoft.lirp.event.ReactiveScope
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.engine.names.WithDataTestName
import io.kotest.matchers.nulls.shouldNotBeNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

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
            DbConfig("SQLite") { SqliteFileSupport.buildDataSource() },
            DbConfig("H2") { H2ContainerSupport.buildH2DataSource() }
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
     * Reads a single row by primary key via raw JDBC on [dataSource], returning the requested
     * [columns] as a name → value map (SQL `NULL`s map to `null`), or `null` when no row matches.
     *
     * Integration tests poll the persisted row with this instead of reconstructing a full
     * [SqlRepository] on every `eventually` iteration: repeated repository construction opens a new
     * pooled connection, bulk-loads the whole table, and registers reactive subscriptions on each
     * poll — adding load to the very scopes the pending flush depends on. A single short-lived read
     * keeps the polling loop cheap.
     */
    fun readRow(dataSource: HikariDataSource, table: String, id: String, vararg columns: String): Map<String, Any?>? =
        readRowById(dataSource, table, id, columns)

    /**
     * Variant of [readRow] for integer primary keys. Uses [java.sql.PreparedStatement.setInt] to
     * avoid type-mismatch errors on strict JDBC drivers (e.g. PostgreSQL rejects
     * `integer = varchar` without an explicit cast).
     */
    fun readRow(dataSource: HikariDataSource, table: String, id: Int, vararg columns: String): Map<String, Any?>? =
        readRowById(dataSource, table, id, columns)

    /**
     * Standard bounded-retry window for asserting durably-persisted state after a repository mutation.
     *
     * A mutation reaches the SQL write pipeline asynchronously — the reactive event is delivered on a
     * background scope and the row is written by the debounced writer (max ~1s) — so an assertion that
     * reads the persisted row or reloads the entity immediately can race that delivery, especially under
     * CI load. Poll within this window instead of asserting once; the poll returns as soon as the write
     * is observed, so the ceiling only affects the rare slow case.
     *
     * The window is deliberately generous: the debounced writer alone can take up to ~1s, and under a
     * loaded CI runner the isolated single-thread ioScope that flushes the write can be starved for
     * several seconds. A tighter ceiling (the earlier 5s) intermittently expired before a slow flush
     * landed; because `eventually` short-circuits on success, the larger value costs nothing on the
     * happy path and only widens the safety margin for the rare contended case.
     */
    val PERSISTED_ROW_POLL: Duration = 15.seconds

    /**
     * Polls [readRow] for ([table], [id]) until [assert] passes or [timeout] elapses, absorbing the
     * asynchronous debounced-write delay between a repository mutation and its durable persistence.
     *
     * Prefer this over a bare [readRow] immediately after a mutation (or after `close()`, which races
     * async event delivery against subscription cancellation). Poll while the writing repository is
     * still open so the event is delivered and flushed within the window.
     */
    suspend fun awaitRow(
        dataSource: HikariDataSource,
        table: String,
        id: Int,
        vararg columns: String,
        timeout: Duration = PERSISTED_ROW_POLL,
        assert: (Map<String, Any?>) -> Unit
    ) = eventually(timeout) {
        val row = readRow(dataSource, table, id, *columns)
        row.shouldNotBeNull()
        assert(row)
    }

    /**
     * Polls a freshly-[reader]-opened reader until [assert] passes or [timeout] elapses, re-creating
     * the reader on each attempt so a late async write becomes visible.
     *
     * A repository opened once caches an in-memory snapshot at load time and never observes a
     * subsequent write, so polling a single reopened reader is ineffective; this reopens per attempt.
     * Each attempt's reader is closed via [AutoCloseable.use].
     */
    suspend fun <R : AutoCloseable> awaitReloaded(
        timeout: Duration = PERSISTED_ROW_POLL,
        reader: () -> R,
        assert: (R) -> Unit
    ) = eventually(timeout) {
        reader().use { assert(it) }
    }

    /**
     * Waits a short warm-up window for a freshly-registered `SharedFlow` collector coroutine to begin
     * collecting before the test emits the mutation it wants observed.
     *
     * A subscription registered immediately before a mutation can miss it because the collector
     * coroutine has not started; this brief settle absorbs that handoff. It is deliberately NOT
     * [awaitRow]: it waits on in-JVM collector readiness, not on a durable write.
     */
    suspend fun awaitSubscriptionReady(warmup: Duration = 50.milliseconds) = delay(warmup)

    /**
     * Polls until a row with [id] is present in [table], or [timeout] elapses. A presence-only
     * companion to [awaitRow] for tests that assert a durable write landed without inspecting columns.
     */
    suspend fun awaitRowPresent(
        dataSource: HikariDataSource,
        table: String,
        id: Int,
        timeout: Duration = PERSISTED_ROW_POLL
    ) = eventually(timeout) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM $table WHERE id = $id")
                rs.next()
                require(rs.getInt(1) == 1) { "row id=$id not yet visible in $table" }
            }
        }
    }

    /**
     * Drops each table in [tableNames] with `DROP TABLE IF EXISTS`, isolating a test's schema from
     * leftovers of a previous run. Order matters for FK-linked tables: pass children/junctions before
     * parents. A propagated [SQLException] is a real setup failure and is intentionally not swallowed.
     */
    fun dropTables(dataSource: HikariDataSource, vararg tableNames: String) {
        for (name in tableNames) {
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS $name") }
            }
        }
    }

    /**
     * Runs [block] as an Exposed transaction bound to [tableDef]'s interpreted table, returning its
     * result. Lets a test read or mutate persisted rows directly via the Exposed DSL without standing
     * up a full [SqlRepository].
     */
    fun <T> rawTransaction(dataSource: HikariDataSource, tableDef: SqlTableDef<*>, block: Table.() -> T): T {
        val db = Database.connect(dataSource)
        val exposed = ExposedTableInterpreter().interpret(tableDef)
        return transaction(db) { exposed.table.block() }
    }

    private fun readRowById(dataSource: HikariDataSource, table: String, id: Any, columns: Array<out String>): Map<String, Any?>? =
        dataSource.connection.use { conn ->
            // Quote identifiers with the dialect's own quote string so reserved words (e.g. `year`)
            // parse on every engine — MySQL/MariaDB use backticks, the rest use double quotes.
            val q = conn.metaData.identifierQuoteString.takeIf { it.isNotBlank() } ?: "\""
            val selectCols = columns.joinToString(", ") { "$q$it$q" }
            conn.prepareStatement("SELECT $selectCols FROM $q$table$q WHERE id = ?").use { ps ->
                when (id) {
                    is Int -> ps.setInt(1, id)
                    else -> ps.setString(1, id.toString())
                }
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        columns.associateWith { col ->
                            val value = rs.getObject(col)
                            if (rs.wasNull()) null else value
                        }
                    }
                }
            }
        }

    /**
     * Runs [block] with a fresh [HikariDataSource] from [db], dropping [tableDef] beforehand
     * for isolation. The data source is always closed in a finally block, even if the test fails.
     */
    inline fun withDatabaseTest(db: DbConfig, tableDef: SqlTableDef<*>, block: (HikariDataSource) -> Unit) {
        // Isolate the reactive scopes per test. The production ioScope is JVM-global and
        // single-threaded; under the integration suite a slow dialect's blocking flush would
        // otherwise queue every other repository's flush behind it on that one shared thread,
        // starving the async write under verification (reproducible on any dialect, including
        // in-memory H2, and on master). A fresh scope per test keeps one test's flush from blocking
        // another. The scopes mirror the production parallelism exactly — ioScope stays
        // single-threaded so the global write-serialization guarantee that
        // SqlRepositoryConcurrencyIntegrationTest relies on is preserved — they are merely isolated,
        // not widened. The scope is cancelled and the defaults restored afterwards; this relies on
        // the integration specs running sequentially, so the global swap is never concurrent.
        val isolatedFlowScope = CoroutineScope(Dispatchers.Default.limitedParallelism(4) + SupervisorJob())
        val isolatedIoScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
        val previousFlowScope = ReactiveScope.flowScope
        val previousIoScope = ReactiveScope.ioScope
        ReactiveScope.flowScope = isolatedFlowScope
        ReactiveScope.ioScope = isolatedIoScope
        val dataSource = db.buildDataSource()
        try {
            dropTable(dataSource, tableDef)
            block(dataSource)
        } finally {
            try {
                dataSource.close()
            } finally {
                isolatedFlowScope.cancel()
                isolatedIoScope.cancel()
                ReactiveScope.flowScope = previousFlowScope
                ReactiveScope.ioScope = previousIoScope
            }
        }
    }
}
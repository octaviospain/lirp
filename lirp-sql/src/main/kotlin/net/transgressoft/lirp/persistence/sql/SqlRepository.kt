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
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.PendingBatchDelete
import net.transgressoft.lirp.persistence.PendingBatchInsert
import net.transgressoft.lirp.persistence.PendingClear
import net.transgressoft.lirp.persistence.PendingDelete
import net.transgressoft.lirp.persistence.PendingInsert
import net.transgressoft.lirp.persistence.PendingOp
import net.transgressoft.lirp.persistence.PendingUpdate
import net.transgressoft.lirp.persistence.PersistentRepositoryBase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import javax.sql.DataSource

/**
 * SQL-backed reactive repository using JetBrains Exposed and HikariCP connection pooling.
 *
 * Extends [PersistentRepositoryBase] with a queue-based write pipeline: CRUD operations update
 * in-memory state immediately (optimistic reads) and enqueue [PendingOp] entries. The base class
 * debounce timer collapses and flushes the queue to SQL via [writePending], which executes all
 * collapsed operations in a single transaction using batch SQL where applicable.
 *
 * On initialization, this repository:
 * 1. Auto-creates the table using [SchemaUtils.create] (no-op if it already exists).
 * 2. Loads all existing rows from the database into in-memory state via [addToMemoryOnly],
 *    bypassing the pending-ops queue so loaded entities are not re-written.
 *
 * Two construction modes are supported:
 * - **User-provided [DataSource]:** The caller owns the connection pool; [close] does not close it.
 * - **JDBC URL constructor:** A [HikariDataSource] is created and owned by this repository;
 *   [close] shuts down the pool after the final flush.
 *
 * @param K The type of entity identifier, must be [Comparable].
 * @param R The type of reactive entity stored in this repository.
 */
open class SqlRepository<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    private val dataSource: DataSource,
    private val tableDef: SqlTableDef<R>,
    private val ownsDataSource: Boolean
) : PersistentRepositoryBase<K, R>("SqlRepository-${tableDef.tableName}") {

    /**
     * Creates a [SqlRepository] using a user-provided [DataSource].
     *
     * The caller retains ownership of the [DataSource]; closing this repository will not close
     * the underlying connection pool.
     *
     * @param dataSource The JDBC data source to use for all SQL operations.
     * @param tableDef The SQL table definition describing the entity's column mapping.
     */
    constructor(dataSource: DataSource, tableDef: SqlTableDef<R>): this(dataSource, tableDef, false)

    /**
     * Creates a [SqlRepository] with a HikariCP connection pool configured from the given JDBC URL.
     *
     * The created [HikariDataSource] is owned by this repository and will be closed when [close]
     * is called.
     *
     * @param jdbcUrl The JDBC connection URL (e.g. `jdbc:postgresql://host/db`).
     * @param tableDef The SQL table definition describing the entity's column mapping.
     * @param poolSize Maximum number of connections in the HikariCP pool. Defaults to 10.
     * @param schema Optional database schema name to use for the connection.
     */
    @JvmOverloads
    constructor(
        jdbcUrl: String,
        tableDef: SqlTableDef<R>,
        poolSize: Int = 10,
        schema: String? = null
    ) : this(buildDataSource(jdbcUrl, poolSize, schema), tableDef, true)

    private val exposedTable: ExposedTable = ExposedTableInterpreter().interpret(tableDef)
    private val table: Table = exposedTable.table
    private val pkCol: Column<*> = exposedTable.columnsByName.getValue(tableDef.columns.first { it.primaryKey }.name)
    private val db: Database = Database.connect(dataSource)

    init {
        try {
            disableEvents(CREATE, UPDATE)

            // Auto-create the table if it does not yet exist
            transaction(db = db) {
                SchemaUtils.create(table)
            }

            // Load all existing rows into in-memory state via addToMemoryOnly(), which bypasses
            // the pending-ops queue — loaded entities are already persisted and must not be re-written.
            val loaded =
                transaction(db = db) {
                    table.selectAll().map { row -> tableDef.fromRow(row, table) }
                }
            loaded.forEach { entity -> addToMemoryOnly(entity) }
            dirty.set(false)

            activateEvents(CREATE, UPDATE)
        } catch (e: Exception) {
            if (ownsDataSource) {
                (dataSource as? HikariDataSource)?.close()
            }
            throw e
        }
    }

    /**
     * Executes all collapsed pending operations against the database in a single transaction.
     *
     * Insert operations use [batchInsert] for efficient bulk inserts. Delete operations use
     * `deleteWhere { inList }` for batch deletes. Updates are applied individually per entity.
     * A [PendingClear] deletes all rows from the table.
     *
     * Empty batch lists are guarded to avoid generating invalid SQL.
     *
     * @param ops the minimal collapsed list of operations to execute.
     */
    override fun writePending(ops: List<PendingOp<K, R>>) {
        transaction(db = db) {
            ops.forEach { op ->
                when (op) {
                    is PendingInsert ->
                        table.insert { stmt ->
                            tableDef.toParams(op.entity, table).forEach { (col, value) ->
                                @Suppress("UNCHECKED_CAST")
                                stmt[col as Column<Any?>] = value
                            }
                        }
                    is PendingBatchInsert -> {
                        if (op.entities.isNotEmpty()) {
                            table.batchInsert(op.entities, shouldReturnGeneratedValues = false) { entity ->
                                tableDef.toParams(entity, table).forEach { (col, value) ->
                                    @Suppress("UNCHECKED_CAST")
                                    this[col as Column<Any?>] = value
                                }
                            }
                        }
                    }
                    is PendingUpdate ->
                        table.update({
                            @Suppress("UNCHECKED_CAST")
                            (pkCol as Column<Any?>).eq(op.entity.id)
                        }) { stmt ->
                            tableDef.toParams(op.entity, table).forEach { (col, value) ->
                                @Suppress("UNCHECKED_CAST")
                                stmt[col as Column<Any?>] = value
                            }
                        }
                    is PendingDelete ->
                        table.deleteWhere {
                            @Suppress("UNCHECKED_CAST")
                            (pkCol as Column<Any?>).eq(op.id)
                        }
                    is PendingBatchDelete -> {
                        // Use individual deleteWhere per ID within the same transaction to avoid
                        // inList parameter expansion issues across different database dialects.
                        // Acceptable for typical batch sizes (tens of IDs) produced by the collapse algorithm.
                        op.ids.forEach { id ->
                            table.deleteWhere {
                                @Suppress("UNCHECKED_CAST")
                                (pkCol as Column<Any?>).eq(id)
                            }
                        }
                    }
                    is PendingClear -> table.deleteAll()
                }
            }
        }
    }

    /**
     * Emits a [CrudEvent.Type.UPDATE] event to repository subscribers when an entity mutation is detected.
     *
     * The [MutationEvent] carries both the previous and current entity state, allowing subscribers
     * to observe what changed.
     */
    override fun onEntityMutated(event: MutationEvent<K, R>) {
        publisher.emitAsync(StandardCrudEvent.Update(event.newEntity, event.oldEntity))
    }

    /**
     * Closes this repository and, if this repository created the connection pool, shuts it down.
     *
     * The base class [close] cancels pending debounce timers, performs a synchronous final flush
     * of all pending ops, and cancels entity mutation subscriptions. HikariCP pool shutdown
     * follows only if this repository owns the data source.
     *
     * Idempotent: subsequent calls are safe no-ops.
     */
    override fun close() {
        if (closed) return
        try {
            super.close()
        } finally {
            if (ownsDataSource) {
                (dataSource as? HikariDataSource)?.close()
            }
        }
    }

    companion object {
        private fun buildDataSource(jdbcUrl: String, poolSize: Int, schema: String?): HikariDataSource {
            val config =
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    this.maximumPoolSize = poolSize
                    schema?.let { this.schema = it }
                }
            return HikariDataSource(config)
        }
    }
}
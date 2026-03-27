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
import net.transgressoft.lirp.persistence.PersistentRepositoryBase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
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
 * Extends [PersistentRepositoryBase] with SQL-first write semantics: every [add], [remove],
 * [removeAll], and [clear] operation performs the corresponding SQL statement synchronously
 * before updating the in-memory state. Entity mutations observed via the [onDirty] hook trigger
 * a full-table SQL UPDATE of all current entities.
 *
 * On initialization, this repository:
 * 1. Auto-creates the table using [SchemaUtils.createMissingTablesAndColumns] (no-op if it already exists).
 * 2. Loads all existing rows from the database into in-memory state.
 *
 * Two construction modes are supported:
 * - **User-provided [DataSource]:** The caller owns the connection pool; [close] does not close it.
 * - **JDBC URL constructor:** A [HikariDataSource] is created and owned by this repository;
 *   [close] shuts down the pool.
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
    constructor(
        dataSource: DataSource,
        tableDef: SqlTableDef<R>
    ) : this(dataSource, tableDef, false)

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
    private val db: Database = Database.connect(dataSource)

    // Guards onDirty() during the init block to prevent SQL UPDATEs while loading rows from the DB.
    private var initializing = true

    init {
        try {
            disableEvents(CREATE, UPDATE)

            // Auto-create the table if it does not yet exist (SQL-04)
            transaction(db = db) {
                SchemaUtils.createMissingTablesAndColumns(table)
            }

            // Load all existing rows into in-memory state (SQL-08).
            // super.add() routes through PersistentRepositoryBase.add(), which handles
            // in-memory insertion, entity subscription, and the dirty/onDirty cycle.
            // The initializing flag prevents onDirty() from issuing redundant SQL UPDATEs here.
            val loaded =
                transaction(db = db) {
                    table.selectAll().map { row -> tableDef.fromRow(row, table) }
                }
            loaded.forEach { entity -> super.add(entity) }
            dirty.set(false)

            initializing = false
            activateEvents(CREATE, UPDATE)
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * Inserts [entity] into the database, then adds it to in-memory state.
     *
     * SQL INSERT is performed before the in-memory update to ensure DB consistency is maintained
     * even if the in-memory operation fails.
     *
     * @param entity The entity to add.
     * @return `true` if the entity was added; `false` if an entity with the same ID already exists.
     * @throws IllegalStateException if the repository has been closed.
     */
    override fun add(entity: R): Boolean {
        check(!closed) { "SqlRepository is closed" }
        transaction(db = db) {
            table.insert { stmt ->
                tableDef.toParams(entity, table).forEach { (col, value) ->
                    @Suppress("UNCHECKED_CAST")
                    stmt[col as Column<Any?>] = value
                }
            }
        }
        return super.add(entity)
    }

    /**
     * Deletes [entity] from the database by primary key, then removes it from in-memory state.
     *
     * @param entity The entity to remove.
     * @return `true` if the entity was removed; `false` if it was not present.
     * @throws IllegalStateException if the repository has been closed.
     */
    override fun remove(entity: R): Boolean {
        check(!closed) { "SqlRepository is closed" }
        val pkCol = primaryKeyColumn()
        transaction(db = db) {
            table.deleteWhere {
                @Suppress("UNCHECKED_CAST")
                (pkCol as Column<Any?>).eq(entity.id)
            }
        }
        return super.remove(entity)
    }

    /**
     * Deletes all specified [entities] from the database by primary key, then removes them
     * from in-memory state.
     *
     * @param entities The entities to remove.
     * @return `true` if at least one entity was removed.
     * @throws IllegalStateException if the repository has been closed.
     */
    override fun removeAll(entities: Collection<R>): Boolean {
        check(!closed) { "SqlRepository is closed" }
        val pkCol = primaryKeyColumn()
        val ids = entities.map { it.id }
        transaction(db = db) {
            ids.forEach { id ->
                table.deleteWhere {
                    @Suppress("UNCHECKED_CAST")
                    (pkCol as Column<Any?>).eq(id)
                }
            }
        }
        return super.removeAll(entities)
    }

    /**
     * Deletes all rows from the database table, then clears in-memory state.
     *
     * @throws IllegalStateException if the repository has been closed.
     */
    override fun clear() {
        check(!closed) { "SqlRepository is closed" }
        transaction(db = db) {
            table.deleteAll()
        }
        super.clear()
    }

    /**
     * Performs a full-table SQL UPDATE of all entities currently held in memory.
     *
     * Called by [PersistentRepositoryBase] whenever an entity mutation is detected. Because the base
     * class does not pass the specific mutated entity to this hook, a full scan is used here.
     * Optimising to track individual dirty entities is deferred to a future caching layer.
     */
    override fun onDirty() {
        if (initializing) return
        val pkCol = primaryKeyColumn()
        transaction(db = db) {
            entitiesById.values.forEach { entity ->
                table.update({
                    @Suppress("UNCHECKED_CAST")
                    (pkCol as Column<Any?>).eq(entity.id)
                }) { stmt ->
                    tableDef.toParams(entity, table).forEach { (col, value) ->
                        @Suppress("UNCHECKED_CAST")
                        stmt[col as Column<Any?>] = value
                    }
                }
            }
        }
    }

    /**
     * Emits a [CrudEvent.Type.UPDATE] event to repository subscribers when an entity mutation is detected.
     *
     * Called by [PersistentRepositoryBase] after [onDirty] completes. The [MutationEvent] carries
     * both the previous and current entity state, allowing subscribers to observe what changed.
     */
    override fun onEntityMutated(event: MutationEvent<K, R>) {
        if (!initializing) {
            publisher.emitAsync(StandardCrudEvent.Update(event.newEntity, event.oldEntity))
        }
    }

    /**
     * Closes this repository and, if this repository created the connection pool, shuts it down.
     *
     * After closing, all mutating operations throw [IllegalStateException].
     * Idempotent: subsequent calls are safe no-ops.
     */
    override fun close() {
        if (closed) return
        super.close()
        if (ownsDataSource) {
            (dataSource as? HikariDataSource)?.close()
        }
    }

    private fun primaryKeyColumn(): Column<*> {
        val pkColName = tableDef.columns.first { it.primaryKey }.name
        return exposedTable.columnsByName[pkColName]!!
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
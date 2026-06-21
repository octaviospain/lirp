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
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.LirpRawConstructor
import net.transgressoft.lirp.persistence.LirpRawInitializer
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Loads all entities and hydrates junction collections for a [SqlRepository].
 *
 * Handles the full bulk-load path: selecting all rows, applying scalar columns via
 * the KSP-generated `applyScalarRow` fast path, and populating ordered/unordered
 * junction collections from per-descriptor SELECT queries.
 *
 * Instances are created once per [SqlRepository] and reused for every [loadFromStore] call.
 */
internal class SqlEntityLoader<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    private val tableDef: SqlTableDef<R>,
    private val exposedTable: ExposedTable,
    private val junctionTables: Map<JunctionTableDef, ExposedJunctionTable>,
    private val db: Database,
    private val publicRawInitializerFor: (Class<*>) -> LirpRawInitializer<Any>,
    private val publicRawConstructorFor: (Class<*>) -> LirpRawConstructor<Any>?
) {
    private val table: Table = exposedTable.table

    // Resolved once: the type argument is erased, so the cast is unchecked by construction but
    // safe — JunctionAware members are typed on the same self-type R as this loader's tableDef.
    @Suppress("UNCHECKED_CAST")
    private val junctionAware: JunctionAware<R>? = tableDef as? JunctionAware<R>

    // Non-null when the table definition opts into construction delegation: the entity is built by
    // its co-located LirpRawConstructor instead of tableDef.fromRow, so a persistence-module table
    // definition can map an entity whose primary constructor is not reachable across the module wall.
    @Suppress("UNCHECKED_CAST")
    private val rawConstructibleTableDef: RawConstructibleTableDef<R>? = tableDef as? RawConstructibleTableDef<R>

    // Resolved once (a RawConstructibleTableDef maps a single concrete entity type via
    // entityClassName); polymorphic per-row construction stays on the fromRow path.
    @Suppress("UNCHECKED_CAST")
    private val rawConstructor: LirpRawConstructor<R>? by lazy {
        rawConstructibleTableDef?.let { rc ->
            val entityClass = Class.forName(rc.entityClassName)
            (
                publicRawConstructorFor(entityClass)
                    ?: error(
                        "RawConstructibleTableDef '${tableDef::class.qualifiedName}' declares " +
                            "entityClassName='${rc.entityClassName}' but no " +
                            "${rc.entityClassName}_LirpRawConstructor was found on the classpath"
                    )
            ) as LirpRawConstructor<R>
        }
    }

    /**
     * Loads all rows from the entity table (and its junction tables when present) into a
     * map keyed by primary key. Must be called outside a transaction; opens its own
     * [transaction] block internally.
     *
     * @return a map of entity ID to entity from the database, or an empty map if the table is empty.
     */
    fun loadFromStore(): Map<K, R> {
        val byId =
            transaction(db = db) {
                val byId = loadEntities()
                if (junctionTables.isNotEmpty()) applyJunctionRowsToEntities(byId)
                byId
            }
        return byId
    }

    /**
     * Loads all rows for this table and hydrates them via [SqlTableDef.fromRow] plus the
     * KSP-generated raw initializer's `applyScalarRow` fast path. Returns the entities keyed by
     * primary key. Must be called from within an Exposed transaction.
     */
    private fun JdbcTransaction.loadEntities(): Map<K, R> {
        // Cache the raw initializer per concrete entity class. `fromRow` is free to materialize
        // subclasses on a per-row basis (e.g. discriminator-driven polymorphic hydration), so a
        // single resolved-once initializer would feed the wrong silent setters into later rows.
        // Hand-written SqlTableDefs that bypass KSP populate the entity entirely inside
        // [fromRow] and have no raw initializer — for those the cached value is `null` and the
        // applyScalarRow fast path is skipped silently. The KSP-mandatory contract is surfaced
        // when the entity is part of a KSP-processed module (validator + the generated
        // `<Entity>_LirpTableDef.applyScalarRow` are produced in lock-step).
        val rawInitByClass = mutableMapOf<Class<*>, LirpRawInitializer<R>?>()
        val entities =
            table.selectAll().map { row ->
                val entity = constructEntity(row)
                val rawInit = rawInitByClass.getOrPut(entity::class.java) { resolveRawInitializer(entity) }
                rawInit?.let { applyScalarRow(entity, row, it) }
                entity
            }
        return entities.associateBy { it.id }
    }

    /**
     * Builds a single entity from [row]. Delegates to the entity's [LirpRawConstructor] when the
     * table definition is a [RawConstructibleTableDef] (construction lives in the entity's module),
     * otherwise calls [SqlTableDef.fromRow] as usual.
     */
    private fun constructEntity(row: ResultRow): R {
        val rc = rawConstructibleTableDef ?: return tableDef.fromRow(row, table)
        return rawConstructor!!.construct(rc.constructorParams(row, table))
    }

    private fun resolveRawInitializer(entity: R): LirpRawInitializer<R>? =
        try {
            @Suppress("UNCHECKED_CAST")
            publicRawInitializerFor(entity::class.java) as LirpRawInitializer<R>
        } catch (_: IllegalStateException) {
            // Hand-written tableDef path: no generated raw initializer.
            // [fromRow] already populated the entity; the default
            // [SqlTableDef.applyScalarRow] is a no-op.
            null
        }

    private fun applyScalarRow(entity: R, row: ResultRow, rawInit: LirpRawInitializer<R>) {
        // Belt-and-braces guard: silentSetter already bypasses event emission for
        // reactive-backed fields, but withEventsDisabled prevents any stray emission
        // from non-reactive var assignments inside the generated applyScalarRow body.
        if (entity is ReactiveEntityBase<*, *>) {
            entity.withEventsDisabled { tableDef.applyScalarRow(entity, row, table, rawInit) }
        } else {
            tableDef.applyScalarRow(entity, row, table, rawInit)
        }
    }

    /**
     * Issues one ordered SELECT per junction descriptor and groups the rows by parent_id.
     * For ordered descriptors, sorts the per-parent ID list by the position column before
     * handing it off to [SqlTableDef.applyJunctionRows]. Orphan rows (parent_id not in [byId])
     * are dropped silently — the FK `ON DELETE CASCADE` handles SQL-side cleanup.
     */
    private fun JdbcTransaction.applyJunctionRowsToEntities(byId: Map<K, R>) {
        for (junction in junctionTables.values) {
            val grouped = groupJunctionRowsByParent(junction)
            applyGroupedJunctionRows(byId, junction.descriptor, grouped)
        }
    }

    private fun JdbcTransaction.groupJunctionRowsByParent(
        junction: ExposedJunctionTable
    ): LinkedHashMap<Any, MutableList<Pair<Any, Int?>>> {
        val rowsQuery = junction.table.selectAll()
        val orderedQuery =
            junction.positionCol?.let { posCol ->
                rowsQuery.orderBy(junction.parentIdCol, SortOrder.ASC).orderBy(posCol, SortOrder.ASC)
            } ?: rowsQuery.orderBy(junction.parentIdCol, SortOrder.ASC)

        val grouped = LinkedHashMap<Any, MutableList<Pair<Any, Int?>>>()
        for (row in orderedQuery) {
            val parentId = toDomainId(row[junction.parentIdCol])
            val itemId = toDomainId(row[junction.itemIdCol])
            val position = junction.positionCol?.let { row[it] }
            grouped.getOrPut(parentId) { mutableListOf() }.add(itemId to position)
        }
        return grouped
    }

    private fun applyGroupedJunctionRows(
        byId: Map<K, R>,
        descriptor: JunctionTableDef,
        grouped: Map<Any, List<Pair<Any, Int?>>>
    ) {
        for ((parentId, pairs) in grouped) {
            @Suppress("UNCHECKED_CAST")
            val entity = byId[parentId as K] ?: continue
            val orderedIds: List<Any> =
                if (descriptor.isOrdered) pairs.sortedBy { it.second ?: 0 }.map { it.first }
                else pairs.map { it.first }
            junctionAware?.applyJunctionRows(entity, descriptor, orderedIds)
        }
    }

    /**
     * Fetches junction rows for [entity] from the database and applies them via
     * [JunctionAware.applyJunctionRows]. Issues one SELECT per junction table, filtered to the
     * entity's primary key, so the query set is proportional to the number of collection fields
     * rather than the total entity count. No-op when [junctionTables] is empty.
     */
    fun hydrateJunctionsForEntity(entity: R) {
        if (junctionTables.isEmpty()) return
        val exposedId = toExposedId(entity.id)
        transaction(db = db) {
            for (junction in junctionTables.values) {
                val descriptor = junction.descriptor
                val rows =
                    junction.table.selectAll()
                        .where {
                            @Suppress("UNCHECKED_CAST")
                            (junction.parentIdCol as Column<Any?>).eq(exposedId)
                        }
                val orderedRows =
                    junction.positionCol?.let { posCol ->
                        rows.orderBy(posCol, SortOrder.ASC)
                    } ?: rows
                val pairs =
                    orderedRows.map { row ->
                        val itemId = toDomainId(row[junction.itemIdCol])
                        val position = junction.positionCol?.let { row[it] }
                        itemId to position
                    }
                val orderedIds: List<Any> =
                    if (descriptor.isOrdered) {
                        pairs.sortedBy { it.second ?: 0 }.map { it.first }
                    } else {
                        pairs.map { it.first }
                    }
                junctionAware?.applyJunctionRows(entity, descriptor, orderedIds)
            }
        }
    }

    companion object {
        /**
         * Converts a `java.util.UUID` to `kotlin.uuid.Uuid` for Exposed column operations.
         * Exposed 1.x uses `kotlin.uuid.Uuid` natively; entity IDs may be `java.util.UUID`.
         */
        internal fun toExposedId(id: Any): Any =
            if (id is UUID) id.toKotlinUuid() else id

        /**
         * Converts a `kotlin.uuid.Uuid` read from Exposed column back to `java.util.UUID` for
         * domain-model comparison. Junction `parent_id` / `item_id` columns return `kotlin.uuid.Uuid`;
         * entity IDs stored as `java.util.UUID` must be normalized to the same type for map lookups
         * and collection matching to succeed.
         */
        internal fun toDomainId(id: Any): Any =
            if (id is Uuid) id.toJavaUuid() else id
    }
}
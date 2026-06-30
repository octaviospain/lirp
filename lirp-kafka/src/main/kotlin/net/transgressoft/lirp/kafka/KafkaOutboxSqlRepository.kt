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

package net.transgressoft.lirp.kafka

import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.kafka.outbox.OutboxEvent
import net.transgressoft.lirp.kafka.outbox.OutboxEventTable
import net.transgressoft.lirp.persistence.PendingUpdate
import net.transgressoft.lirp.persistence.TransactionBuffer
import net.transgressoft.lirp.persistence.sql.SqlRepository
import net.transgressoft.lirp.persistence.sql.SqlTableDef
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.uuid.toKotlinUuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A [SqlRepository] subclass that co-inserts one outbox row per entity change into the
 * `lirp_kafka_outbox` table, atomically inside the same JDBC commit that persists the entity rows.
 *
 * Outbox rows are inserted on two paths:
 * - The explicit `transaction { }` path via [onAfterEntityWritesInTransaction], which also
 *   captures the mutation events buffered during the block (property-changed, batch-changed).
 * - The debounced write-pending path via [onAfterEntityWritesInWritePending], which captures
 *   CRUD codes only (no deferred events are available on this path).
 *
 * Both overrides run inside the parent's already-open Exposed transaction block. They use bare
 * Exposed DSL without opening a new `transaction { }` — any new transaction call from inside an
 * active transaction would start a separate JDBC commit and break the atomicity guarantee.
 *
 * The outbox payload is a serializer-neutral JSON field snapshot built by calling
 * [SqlTableDef.toParams] on each entity — the same field mapping the SQL persistence layer
 * uses for INSERT/UPDATE. Consumers needing field-level encryption or transformation should
 * apply it at the [SqlTableDef] mapping level so both the SQL row and the outbox payload remain
 * consistent.
 *
 * The `lirp_kafka_outbox` table is created at construction time via [SchemaUtils.create].
 *
 * @param K The type of entity identifier, must be [Comparable].
 * @param R The type of reactive entity stored in this repository.
 * @param dataSource JDBC data source shared with the parent [SqlRepository]; owning the same
 *   pool ensures all writes land on the same JDBC connection and share the same transaction.
 * @param tableDef SQL table definition for the entity. Stored locally because the parent's
 *   [SqlTableDef] field is `private` and the payload builder needs it for [SqlTableDef.toParams].
 * @param loadOnInit When `true` (default), rows are loaded from the database immediately during
 *   construction.
 */
class KafkaOutboxSqlRepository<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    dataSource: DataSource,
    private val tableDef: SqlTableDef<R>,
    loadOnInit: Boolean = true
) : SqlRepository<K, R>(dataSource, tableDef, loadOnInit) {

    init {
        // Create the outbox table at construction time, mirroring the entity-table creation in
        // SqlRepository.init. Reuses the parent's connection registration so no second one is
        // opened. SchemaUtils.create is a no-op when the table already exists.
        transaction(db) {
            SchemaUtils.create(OutboxEventTable)
        }
    }

    /**
     * Called inside the `transaction(db) { }` block of [commitTransactionBuffer], after all entity
     * writes and before the transaction closes. Inserts one outbox row per entity change using bare
     * Exposed DSL — no new transaction is opened here.
     *
     * Covers the event families reachable inside the entity-state flush. CRUD codes are derived
     * from the buffer's insert/update/delete partitions; mutation codes come from the events the
     * entities buffered during the block:
     * - Inserts → CrudEvent.Type.CREATE (code 100)
     * - Updates → CrudEvent.Type.UPDATE (code 300)
     * - Deletes → CrudEvent.Type.DELETE (code 400)
     * - Deferred mutation events → each event's [EventType.code] (302 for PropertyChanged,
     *   303 for BatchChanged)
     *
     * The mapping reads [EventType.code] verbatim rather than switching on a fixed set, so any
     * code an event carries is recorded faithfully. Events a consumer publishes through the event
     * publisher outside the flush are not visible here; those are captured by the publisher-backed
     * outbox path.
     */
    override fun onAfterEntityWritesInTransaction(buffer: TransactionBuffer<K, R>) {
        val rows =
            buildCrudOutboxRows(
                buffer.inserts.map { it to CrudEvent.Type.CREATE },
                buffer.updates.map { it.entity to CrudEvent.Type.UPDATE },
                buffer.deletes.map { it.entity to CrudEvent.Type.DELETE }
            ) +
                buffer.deferredEvents.map { event ->
                    buildRow(event.entity, event.type.code)
                }
        insertRows(rows)
    }

    /**
     * Called inside the `transaction(db) { }` block of [writePending], after all entity writes and
     * before the transaction closes. Inserts one outbox row per entity change using bare Exposed DSL —
     * no new transaction is opened here.
     *
     * Only CRUD event codes are available on the debounced path — no deferred mutation events exist
     * outside a `transaction { }` block. For DELETE rows, the entity has already been removed from
     * in-memory state before [writePending] fires; the payload is recorded as an empty JSON object
     * since the field values are no longer available.
     */
    override fun onAfterEntityWritesInWritePending(
        inserts: List<R>,
        updates: List<PendingUpdate<K, R>>,
        deletes: List<Pair<K, Long?>>
    ) {
        val insertRows = inserts.map { buildRow(it, CrudEvent.Type.CREATE.code) }
        val updateRows = updates.map { buildRow(it.entity, CrudEvent.Type.UPDATE.code) }
        // On the writePending path, deleted entities are already removed from in-memory state.
        // Only the key is available; payload is recorded as an empty JSON object.
        val deleteRows =
            deletes.map { (key, _) ->
                OutboxEvent(
                    id = UUID.randomUUID(),
                    aggregateType = tableDef.tableName,
                    aggregateId = key.toString(),
                    eventTypeCode = CrudEvent.Type.DELETE.code,
                    payload = "{}",
                    createdAt = Clock.System.now()
                )
            }
        insertRows(insertRows + updateRows + deleteRows)
    }

    /**
     * Builds a serializer-neutral JSON payload from the entity's persisted field values by calling
     * [SqlTableDef.toParams] — the same field extraction used for SQL INSERT/UPDATE statements.
     *
     * Column values are encoded as their JSON primitive equivalents: `null` → `JsonNull`;
     * [String], [Number], [Boolean] → typed [JsonPrimitive]; everything else → `toString()`.
     */
    private fun buildPayload(entity: R): String {
        val params = tableDef.toParams(entity, exposedTable.table)
        return Json.encodeToString(
            buildJsonObject {
                params.forEach { (col, v) ->
                    put(
                        col.name,
                        when (v) {
                            null -> JsonNull
                            is String -> JsonPrimitive(v)
                            is Number -> JsonPrimitive(v)
                            is Boolean -> JsonPrimitive(v)
                            else -> JsonPrimitive(v.toString())
                        }
                    )
                }
            }
        )
    }

    private fun buildRow(entity: R, eventTypeCode: Int): OutboxEvent =
        OutboxEvent(
            id = UUID.randomUUID(),
            aggregateType = tableDef.tableName,
            aggregateId = entity.id.toString(),
            eventTypeCode = eventTypeCode,
            payload = buildPayload(entity),
            createdAt = Clock.System.now()
        )

    private fun buildCrudOutboxRows(
        inserts: List<Pair<R, CrudEvent.Type>>,
        updates: List<Pair<R, CrudEvent.Type>>,
        deletes: List<Pair<R, CrudEvent.Type>>
    ): List<OutboxEvent> =
        (inserts + updates + deletes).map { (entity, type) -> buildRow(entity, type.code) }

    private fun insertRows(rows: List<OutboxEvent>) {
        if (rows.isEmpty()) return
        OutboxEventTable.batchInsert(rows, shouldReturnGeneratedValues = false) { row ->
            this[OutboxEventTable.id] = row.id.toKotlinUuid()
            this[OutboxEventTable.aggregateType] = row.aggregateType
            this[OutboxEventTable.aggregateId] = row.aggregateId
            this[OutboxEventTable.eventTypeCode] = row.eventTypeCode
            this[OutboxEventTable.payload] = row.payload
            this[OutboxEventTable.createdAt] = row.createdAt
        }
    }
}
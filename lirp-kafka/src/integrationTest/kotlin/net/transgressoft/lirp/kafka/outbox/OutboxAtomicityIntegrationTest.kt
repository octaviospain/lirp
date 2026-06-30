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

package net.transgressoft.lirp.kafka.outbox

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.kafka.KafkaOutboxSqlRepository
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.LirpTransactionException
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.sql.AudioItemSqlTableDef
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.withDatabaseTest
import net.transgressoft.lirp.persistence.transaction
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe

/**
 * Multi-dialect integration tests for [KafkaOutboxSqlRepository] transactional outbox capture.
 *
 * Verifies commit and rollback atomicity across all five supported SQL dialects
 * (PostgreSQL, MySQL, MariaDB, SQLite, H2). For each dialect:
 * - A committed `transaction { r.add(item) }` produces exactly one outbox row with the
 *   correct aggregate id and CREATE code.
 * - A block-throw rollback leaves zero additional outbox rows.
 *
 * The `uuid` and `timestamp` columns on the outbox table are exercised implicitly —
 * a dialect-specific DDL or type-mapping mismatch would surface as a DDL or read error
 * inside [withDatabaseTest].
 */
@DisplayName("OutboxAtomicityIntegrationTest")
internal class OutboxAtomicityIntegrationTest : FunSpec({

    context("outbox atomicity across dialects") {
        withTests(databases) { db ->
            withDatabaseTest(db, AudioItemSqlTableDef) { dataSource ->

                // Drop the outbox table before each dialect run so KafkaOutboxSqlRepository.init
                // recreates it fresh via SchemaUtils.create — same isolation guarantee as the
                // entity table drop performed by withDatabaseTest for AudioItemSqlTableDef.
                dropOutboxTableIfExists(dataSource)

                val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)

                try {
                    // Commit path: one outbox row with matching aggregate id and CREATE code.
                    val item = MutableAudioItem(1, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem
                    transaction(repo) { r -> r.add(item) }

                    countOutboxRows(dataSource) shouldBe 1L

                    val row = readFirstOutboxRow(dataSource)!!
                    row["aggregate_id"] shouldBe "1"
                    row["event_type_code"] shouldBe CrudEvent.Type.CREATE.code

                    // Rollback path: block-throw leaves zero additional outbox rows.
                    val outboxCountBeforeRollback = countOutboxRows(dataSource)
                    shouldThrow<LirpTransactionException> {
                        transaction(repo) { r ->
                            r.add(MutableAudioItem(2, "Killer Queen", "Sheer Heart Attack") as AudioItem)
                            throw RuntimeException("injected failure — both entity and outbox rows must roll back")
                        }
                    }

                    countOutboxRows(dataSource) shouldBe outboxCountBeforeRollback
                } finally {
                    repo.close()
                }
            }
        }
    }
})

/**
 * Counts the rows in the `lirp_kafka_outbox` table using raw JDBC, independent of the
 * Exposed table object which is internal to the main source set.
 */
private fun countOutboxRows(dataSource: HikariDataSource): Long =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM lirp_kafka_outbox").use { stmt ->
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

/**
 * Reads the first row from `lirp_kafka_outbox` as a name-to-value map using raw JDBC.
 * Returns `null` when the table is empty.
 */
private fun readFirstOutboxRow(dataSource: HikariDataSource): Map<String, Any?>? =
    dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT aggregate_id, event_type_code FROM lirp_kafka_outbox LIMIT 1")
            .use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else {
                        mapOf(
                            "aggregate_id" to rs.getString("aggregate_id"),
                            "event_type_code" to rs.getInt("event_type_code")
                        )
                    }
                }
            }
    }

/**
 * Drops the `lirp_kafka_outbox` table if it exists, swallowing the "table not found" dialect
 * variants across PostgreSQL/MySQL/MariaDB/SQLite/H2.
 */
private fun dropOutboxTableIfExists(dataSource: HikariDataSource) {
    try {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DROP TABLE IF EXISTS lirp_kafka_outbox").use { it.execute() }
        }
    } catch (_: Exception) {
        // Some drivers throw on DROP TABLE IF EXISTS even when the table doesn't exist;
        // ignore and let KafkaOutboxSqlRepository.init create it fresh.
    }
}
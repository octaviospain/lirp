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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/**
 * Raw-JDBC UUID interoperability tests across every SQL dialect supported by lirp-sql.
 */
@DisplayName("UUID JDBC Interoperability Integration")
internal class UuidJdbcInteropIntegrationTest : FunSpec({

    val uuidDatabases = DatabaseTestSupport.databases

    fun idColumn(dbName: String, primaryKey: Boolean): String {
        val columnType =
            when (dbName) {
                "H2", "PostgreSQL" -> "UUID"
                "MySQL", "MariaDB" -> "BINARY(16)"
                "SQLite" -> "BLOB"
                else -> error("Unsupported UUID test dialect '$dbName'.")
            }
        return if (primaryKey) "$columnType PRIMARY KEY" else columnType
    }

    fun nullSqlType(dbName: String): Int =
        when (dbName) {
            "H2", "PostgreSQL" -> Types.OTHER
            "MySQL", "MariaDB" -> Types.BINARY
            "SQLite" -> Types.BLOB
            else -> error("Unsupported UUID test dialect '$dbName'.")
        }

    fun createTableSql(dbName: String, includeNullableColumn: Boolean = false): String {
        val nullableColumn =
            if (includeNullableColumn) {
                ", optional_uuid ${idColumn(dbName, primaryKey = false)}"
            } else {
                ""
            }
        return "CREATE TABLE uuid_interop_t (id ${idColumn(dbName, primaryKey = true)}, payload TEXT$nullableColumn)"
    }

    fun HikariDataSource.withUuidTable(dbName: String, includeNullableColumn: Boolean = false, block: (java.sql.Connection) -> Unit) {
        try {
            connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP TABLE IF EXISTS uuid_interop_t")
                    stmt.execute(createTableSql(dbName, includeNullableColumn))
                }
                block(conn)
            }
        } finally {
            close()
        }
    }

    context("bindUuid round-trips through readUuid") {
        withTests(uuidDatabases) { db ->
            val ds = db.buildDataSource()
            ds.withUuidTable(db.name) { conn ->
                val uuid = UUID.randomUUID()

                conn.prepareStatement("INSERT INTO uuid_interop_t (id, payload) VALUES (?, ?)").use { ps ->
                    ps.bindUuid(1, uuid, conn)
                    ps.setString(2, "data")
                    ps.executeUpdate() shouldBe 1
                }

                conn.prepareStatement("SELECT id FROM uuid_interop_t").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.readUuid(1, conn) shouldBe uuid
                        rs.readUuid("id", conn) shouldBe uuid
                    }
                }
            }
        }
    }

    context("bindUuid in WHERE clause matches exactly one row") {
        withTests(uuidDatabases) { db ->
            val ds = db.buildDataSource()
            ds.withUuidTable(db.name) { conn ->
                val uuid = UUID.randomUUID()

                conn.prepareStatement("INSERT INTO uuid_interop_t (id, payload) VALUES (?, ?)").use { ps ->
                    ps.bindUuid(1, uuid, conn)
                    ps.setString(2, "data")
                    ps.executeUpdate() shouldBe 1
                }

                conn.prepareStatement("UPDATE uuid_interop_t SET payload = ? WHERE id = ?").use { ps ->
                    ps.setString(1, "updated")
                    ps.bindUuid(2, uuid, conn)
                    ps.executeUpdate() shouldBe 1
                }
            }
        }
    }

    // Pinned regression from issue #161: documents byte-backed UUID columns rejecting canonical strings.
    context("raw setString in WHERE clause asymmetry witness") {
        withTests(uuidDatabases) { db ->
            val ds = db.buildDataSource()
            ds.withUuidTable(db.name) { conn ->
                val uuid = UUID.randomUUID()

                conn.prepareStatement("INSERT INTO uuid_interop_t (id, payload) VALUES (?, ?)").use { ps ->
                    ps.bindUuid(1, uuid, conn)
                    ps.setString(2, "data")
                    ps.executeUpdate() shouldBe 1
                }

                when (db.name) {
                    "MySQL", "MariaDB", "SQLite" -> {
                        // Strict regression pin (issue #161): byte-backed UUID columns return
                        // executeUpdate() == 0 with no exception when compared with canonical TEXT.
                        // Any change here means the driver/storage coercion shifted; update KDoc and wiki first.
                        conn.prepareStatement("UPDATE uuid_interop_t SET payload = ? WHERE id = ?").use { ps ->
                            ps.setString(1, "via-string")
                            ps.setString(2, uuid.toString())
                            val affected = ps.executeUpdate()
                            affected shouldBe 0
                        }
                    }
                    "H2" -> {
                        conn.prepareStatement("UPDATE uuid_interop_t SET payload = ? WHERE id = ?").use { ps ->
                            ps.setString(1, "via-string")
                            ps.setString(2, uuid.toString())
                            ps.executeUpdate() shouldBe 1
                        }
                    }
                    "PostgreSQL" -> {
                        shouldThrow<SQLException> {
                            conn.prepareStatement("UPDATE uuid_interop_t SET payload = ? WHERE id = ?").use { ps ->
                                ps.setString(1, "via-string")
                                ps.setString(2, uuid.toString())
                                ps.executeUpdate()
                            }
                        }
                    }
                    else -> error("Unsupported UUID test dialect '${db.name}'.")
                }
            }
        }
    }

    context("readUuid returns null for SQL NULL") {
        withTests(uuidDatabases) { db ->
            val ds = db.buildDataSource()
            ds.withUuidTable(db.name, includeNullableColumn = true) { conn ->
                val uuid = UUID.randomUUID()

                conn.prepareStatement("INSERT INTO uuid_interop_t (id, payload, optional_uuid) VALUES (?, ?, ?)").use { ps ->
                    ps.bindUuid(1, uuid, conn)
                    ps.setString(2, "data")
                    ps.setNull(3, nullSqlType(db.name))
                    ps.executeUpdate() shouldBe 1
                }

                conn.prepareStatement("SELECT optional_uuid FROM uuid_interop_t").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.readUuid("optional_uuid", conn).shouldBeNull()
                    }
                }
            }
        }
    }
})
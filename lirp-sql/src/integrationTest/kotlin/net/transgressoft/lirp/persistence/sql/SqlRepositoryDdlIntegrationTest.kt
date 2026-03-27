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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName

/**
 * Integration tests for [SqlRepository] DDL behaviour against a real PostgreSQL database.
 *
 * Verifies that tables are auto-created with the correct column types for all 12 [ColumnType]
 * variants, that re-initialization is idempotent, and that the PostgreSQL dialect is detected
 * automatically from the provided [javax.sql.DataSource].
 *
 * Each test drops its table afterwards to maintain isolation within the shared container schema.
 */
@DisplayName("SqlRepository DDL Integration")
internal class SqlRepositoryDdlIntegrationTest : StringSpec({

    var dataSource: HikariDataSource? = null

    beforeSpec {
        dataSource = PostgresContainerSupport.buildDataSource()
    }

    afterSpec {
        dataSource?.close()
    }

    fun dropTable(tableDef: SqlTableDef<*>) {
        val db = Database.connect(dataSource!!)
        val t = ExposedTableInterpreter().interpret(tableDef)
        runCatching { transaction(db) { SchemaUtils.drop(t.table) } }
    }

    "auto-creates table with correct PostgreSQL column types for all 12 ColumnType variants" {
        val repo = SqlRepository(dataSource!!, AllTypesTableDef)

        val columnTypes = mutableMapOf<String, String>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? AND table_schema = 'public'"
            ).use { stmt ->
                stmt.setString(1, "all_types")
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        columnTypes[rs.getString("column_name")] = rs.getString("data_type")
                    }
                }
            }
        }

        columnTypes["id"] shouldBe "integer"
        columnTypes["long_val"] shouldBe "bigint"
        columnTypes["text_val"] shouldBe "text"
        columnTypes["bool_val"] shouldBe "boolean"
        columnTypes["double_val"] shouldBe "double precision"
        columnTypes["float_val"] shouldBe "real"
        columnTypes["uuid_val"] shouldBe "uuid"
        columnTypes["date_val"] shouldBe "date"
        columnTypes["date_time_val"] shouldBe "timestamp without time zone"
        columnTypes["varchar_val"] shouldBe "character varying"
        columnTypes["decimal_val"] shouldBe "numeric"
        columnTypes["enum_val"] shouldBe "character varying"

        repo.close()
        dropTable(AllTypesTableDef)
    }

    "auto-creates table idempotently on re-initialization" {
        val repo1 = SqlRepository(dataSource!!, TestPersonTableDef)
        repo1.close()

        // Creating a second repository on the same DataSource must not throw
        val repo2 = SqlRepository(dataSource, TestPersonTableDef)
        repo2.size() shouldBe 0
        repo2.close()

        dropTable(TestPersonTableDef)
    }

    "detects PostgreSQL dialect automatically from DataSource" {
        val db = Database.connect(dataSource!!)
        val dialectName = transaction(db) { db.dialect.name }
        dialectName shouldBe "PostgreSQL"
    }
})
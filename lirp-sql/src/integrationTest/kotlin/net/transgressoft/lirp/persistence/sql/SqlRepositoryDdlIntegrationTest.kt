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

import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.withDatabaseTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import java.math.BigDecimal
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Integration tests for [SqlRepository] DDL behaviour against PostgreSQL, MySQL 8.0, and MariaDB 11.
 *
 * Verifies that tables are auto-created and all 12 [net.transgressoft.lirp.persistence.ColumnType]
 * variants round-trip correctly via CRUD operations, that re-initialization is idempotent, and that
 * the dialect is detected automatically from the provided [javax.sql.DataSource].
 */
@OptIn(ExperimentalUuidApi::class)
@DisplayName("SqlRepository DDL Integration")
internal class SqlRepositoryDdlIntegrationTest : FunSpec({

    context("all 12 ColumnType variants round-trip correctly via CRUD") {
        withTests(databases) { db ->
            withDatabaseTest(db, AllTypesTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, AllTypesTableDef)

                val uuid = Uuid.random()
                val date = LocalDate(2025, 6, 15)
                val dateTime = LocalDateTime(2025, 6, 15, 12, 30, 0)

                val entity =
                    AllTypesEntity(1).apply {
                        longVal = 123456789L
                        textVal = "hello text"
                        boolVal = true
                        doubleVal = 3.14
                        floatVal = 2.71f
                        uuidVal = uuid
                        dateVal = date
                        dateTimeVal = dateTime
                        varcharVal = "varchar value"
                        decimalVal = BigDecimal("99.99")
                        enumVal = "ACTIVE"
                    }

                repo.add(entity)

                repo.findById(1).shouldBePresent {
                    it.longVal shouldBe 123456789L
                    it.textVal shouldBe "hello text"
                    it.boolVal shouldBe true
                    it.doubleVal shouldBe 3.14
                    it.floatVal shouldBe 2.71f
                    it.uuidVal shouldBe uuid
                    it.dateVal shouldBe date
                    it.dateTimeVal shouldBe dateTime
                    it.varcharVal shouldBe "varchar value"
                    it.decimalVal shouldBe BigDecimal("99.99")
                    it.enumVal shouldBe "ACTIVE"
                }

                repo.close()
            }
        }
    }

    context("auto-creates table idempotently on re-initialization") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo1 = SqlRepository(dataSource, TestPersonTableDef)
                repo1.close()

                val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                repo2.size() shouldBe 0
                repo2.close()
            }
        }
    }

    context("detects dialect automatically from DataSource") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                val exposedDb = Database.connect(dataSource)
                val dialectName = transaction(exposedDb) { exposedDb.dialect.name }

                val expectedDialects = mapOf("PostgreSQL" to "PostgreSQL", "MySQL" to "MySQL", "MariaDB" to "MariaDB")
                dialectName shouldBe expectedDialects[db.name]
            } finally {
                dataSource.close()
            }
        }
    }
})
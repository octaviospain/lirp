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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-dialect round-trip integration test asserting that KSP-generated `_LirpTableDef`
 * artefacts handle `Short` and `Byte` properties correctly across PostgreSQL, MySQL, MariaDB,
 * SQLite, and H2 — both nullable and non-nullable, including boundary values.
 *
 * The fixture entity ([ShortByteFixtureEntity]) is annotated with `@PersistenceMapping` so the
 * KSP processor produces the table descriptor consumed here; the test therefore exercises the
 * full inference path (`mapToColumnTypeExpression` → `buildRowAccess` → `buildEntityAccess`).
 */
internal class ShortByteColumnTypeIT : FunSpec({

    val dialects =
        listOf(
            DbConfig("PostgreSQL") { PostgresContainerSupport.buildDataSource() },
            DbConfig("MySQL") { MysqlContainerSupport.buildDataSource() },
            DbConfig("MariaDB") { MariaDbContainerSupport.buildDataSource() },
            DbConfig("SQLite") { SqliteFileSupport.buildDataSource() },
            DbConfig("H2") { buildH2DataSource() }
        )

    context("Short and Byte fields round-trip across PostgreSQL, MySQL, MariaDB, SQLite, and H2") {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, ShortByteFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                val entity =
                    ShortByteFixtureEntity("e1").apply {
                        year = 1985
                        nullableYear = null
                        flag = 7
                        nullableFlag = null
                    }
                repo.add(entity)
                repo.close()

                val reloaded = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                reloaded.findById("e1").shouldBePresent {
                    it.year shouldBe 1985.toShort()
                    it.nullableYear shouldBe null
                    it.flag shouldBe 7.toByte()
                    it.nullableFlag shouldBe null
                }
                // Update via the loaded repo, then re-read in a fresh repo to assert the widened-
                // write path survived the round trip. The mutation fires a reactive event that the
                // SqlRepository subscription routes to enqueueUpdate; `eventually` covers the brief
                // coroutine dispatch window before close()'s synchronous flush observes the entry.
                reloaded.findById("e1").shouldBePresent {
                    it.year = 2024
                    it.nullableYear = 999
                    it.flag = (-12).toByte()
                    it.nullableFlag = 5
                }
                eventually(5.seconds) {
                    val repo3 = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                    repo3.findById("e1").shouldBePresent {
                        it.year shouldBe 2024.toShort()
                        it.nullableYear shouldBe 999.toShort()
                        it.flag shouldBe (-12).toByte()
                        it.nullableFlag shouldBe 5.toByte()
                    }
                    repo3.close()
                }
                reloaded.close()
            }
        }
    }

    context("nullable Short and Byte fields preserve null across persist and re-load on every dialect") {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, ShortByteFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                repo.add(
                    ShortByteFixtureEntity("n1").apply {
                        year = 2000
                        flag = 1
                        // nullableYear / nullableFlag stay at their null default
                    }
                )
                repo.close()

                val repo2 = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                repo2.findById("n1").shouldBePresent {
                    it.nullableYear shouldBe null
                    it.nullableFlag shouldBe null
                    it.nullableYear = 42
                    it.nullableFlag = 9
                }
                eventually(5.seconds) {
                    val repo3 = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                    repo3.findById("n1").shouldBePresent {
                        it.nullableYear shouldBe 42.toShort()
                        it.nullableFlag shouldBe 9.toByte()
                    }
                    repo3.close()
                }
                repo2.close()

                val repo4 = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                repo4.findById("n1").shouldBePresent {
                    it.nullableYear = null
                    it.nullableFlag = null
                }
                eventually(5.seconds) {
                    val repo5 = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                    repo5.findById("n1").shouldBePresent {
                        it.nullableYear shouldBe null
                        it.nullableFlag shouldBe null
                    }
                    repo5.close()
                }
                repo4.close()
            }
        }
    }

    context("Short and Byte values at boundary ranges round-trip without overflow") {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, ShortByteFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                repo.add(
                    ShortByteFixtureEntity("max").apply {
                        year = Short.MAX_VALUE
                        nullableYear = Short.MAX_VALUE
                        flag = Byte.MAX_VALUE
                        nullableFlag = Byte.MAX_VALUE
                    }
                )
                repo.add(
                    ShortByteFixtureEntity("min").apply {
                        year = Short.MIN_VALUE
                        nullableYear = Short.MIN_VALUE
                        flag = Byte.MIN_VALUE
                        nullableFlag = Byte.MIN_VALUE
                    }
                )
                repo.close()

                val reloaded = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                reloaded.findById("max").shouldBePresent {
                    it.year shouldBe Short.MAX_VALUE
                    it.nullableYear shouldBe Short.MAX_VALUE
                    it.flag shouldBe Byte.MAX_VALUE
                    it.nullableFlag shouldBe Byte.MAX_VALUE
                }
                reloaded.findById("min").shouldBePresent {
                    it.year shouldBe Short.MIN_VALUE
                    it.nullableYear shouldBe Short.MIN_VALUE
                    it.flag shouldBe Byte.MIN_VALUE
                    it.nullableFlag shouldBe Byte.MIN_VALUE
                }
                reloaded.close()
            }
        }
    }
})

/**
 * Builds a fresh in-memory H2 datasource per call. A unique database name guarantees test
 * isolation; `DB_CLOSE_DELAY=-1` keeps the in-memory schema alive for the entire pool lifetime
 * so reopening through a second [SqlRepository] sees the rows committed by the first.
 */
private fun buildH2DataSource(): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
            maximumPoolSize = 4
        }
    )
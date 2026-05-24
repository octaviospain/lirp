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
 * Joint cross-dialect canary asserting that the three KSP robustness fixes from issue #207
 * compose correctly on a single entity ([CombinedKspFixtureEntity]) across PostgreSQL, MySQL,
 * MariaDB, SQLite, and H2.
 *
 * The fixture exercises simultaneously: a `private var` excluded from the bulk-load
 * rehydration path, `Short` and `Byte` fields routed through `IntType` with narrowing on
 * reload, a primary-constructor `val` non-PK column reaching `SqlTableDef` codegen via the
 * refined mutability gate, and ordinary mutable reactive siblings as a control. This test
 * does not duplicate the focused per-fix integration suites — it proves the joint contract.
 */
internal class CombinedKspRobustnessIT : FunSpec({

    val dialects =
        listOf(
            DbConfig("PostgreSQL") { PostgresContainerSupport.buildDataSource() },
            DbConfig("MySQL") { MysqlContainerSupport.buildDataSource() },
            DbConfig("MariaDB") { MariaDbContainerSupport.buildDataSource() },
            DbConfig("SQLite") { SqliteFileSupport.buildDataSource() },
            DbConfig("H2") { buildH2DataSource() }
        )

    context(
        "compound entity with private-var, Short, Byte, ctor-val, and mutable siblings round-trips " +
            "across PostgreSQL, MySQL, MariaDB, SQLite, and H2"
    ) {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, CombinedKspFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                val entity =
                    CombinedKspFixtureEntity("e1", "L").apply {
                        year = 1985
                        nullableYear = null
                        flag = 7
                        notes = "n0"
                        setCacheValue(42)
                    }
                repo.add(entity)
                repo.close()

                val reloaded = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                reloaded.findById("e1").shouldBePresent {
                    it.id shouldBe "e1"
                    it.label shouldBe "L"
                    it.year shouldBe 1985.toShort()
                    it.nullableYear shouldBe null
                    it.flag shouldBe 7.toByte()
                    it.notes shouldBe "n0"
                    // The private `cache` field is excluded by the bulk-load rehydration path,
                    // so the property initializer's default applies after reload — not the
                    // 42 written via setCacheValue before flush.
                    it.cacheValue() shouldBe 0
                }
                reloaded.close()
            }
        }
    }

    context(
        "mutating var siblings on the compound entity flushes correctly while ctor-val label remains untouched"
    ) {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, CombinedKspFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                repo.add(
                    CombinedKspFixtureEntity("e2", "L").apply {
                        year = 1985
                        flag = 7
                        notes = "n0"
                    }
                )
                repo.findById("e2").shouldBePresent {
                    it.notes = "n1"
                    it.year = 2024
                }
                eventually(5.seconds) {
                    val verify = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                    verify.findById("e2").shouldBePresent {
                        it.label shouldBe "L"
                        it.year shouldBe 2024.toShort()
                        it.notes shouldBe "n1"
                    }
                    verify.close()
                }
                repo.close()
            }
        }
    }

    context(
        "nullable Short on the compound entity preserves null across persist and update cycles"
    ) {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, CombinedKspFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                repo.add(
                    CombinedKspFixtureEntity("e3", "L").apply {
                        year = 2000
                        flag = 1
                        // nullableYear stays at its null default
                    }
                )
                repo.close()

                val repo2 = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                repo2.findById("e3").shouldBePresent {
                    it.nullableYear shouldBe null
                    it.nullableYear = 999
                }
                eventually(5.seconds) {
                    val repo3 = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                    repo3.findById("e3").shouldBePresent {
                        it.nullableYear shouldBe 999.toShort()
                    }
                    repo3.close()
                }
                repo2.close()

                val repo4 = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                repo4.findById("e3").shouldBePresent {
                    it.nullableYear = null
                }
                eventually(5.seconds) {
                    val repo5 = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                    repo5.findById("e3").shouldBePresent {
                        it.nullableYear shouldBe null
                    }
                    repo5.close()
                }
                repo4.close()
            }
        }
    }

    context("private var assignments do not appear in the persisted row") {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, CombinedKspFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                repo.add(
                    CombinedKspFixtureEntity("e4", "L").apply {
                        year = 1985
                        flag = 7
                        notes = "initial"
                        setCacheValue(42)
                    }
                )
                repo.close()

                val reloaded = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                reloaded.findById("e4").shouldBePresent {
                    // First sanity check: the pre-flush setCacheValue(42) did not survive — the
                    // private field is excluded from persistence.
                    it.cacheValue() shouldBe 0
                    // Now write the private field AND a non-private sibling. Only the sibling
                    // assignment should drive a flush; the private cache change is silent.
                    it.setCacheValue(99)
                    it.notes = "trigger"
                }
                eventually(5.seconds) {
                    val verify = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                    verify.findById("e4").shouldBePresent {
                        // The sibling flush happened (rules out "no flush at all" as the
                        // explanation for cache being absent from the row).
                        it.notes shouldBe "trigger"
                        // The private field is still excluded — the 99 written in-memory
                        // never reached the row, so the reloaded entity gets the initializer
                        // default again.
                        it.cacheValue() shouldBe 0
                    }
                    verify.close()
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
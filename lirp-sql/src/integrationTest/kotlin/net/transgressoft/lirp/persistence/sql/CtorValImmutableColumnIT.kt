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
 * Cross-dialect round-trip integration test asserting that entities with a primary-constructor
 * `val` non-PK column receive `SqlTableDef` codegen and persist correctly across PostgreSQL,
 * MySQL, MariaDB, SQLite, and H2.
 *
 * The fixture entity ([CtorValFixtureEntity]) declares an immutable `label` ctor-val alongside a
 * mutable `notes` reactive `var`. These tests prove (a) the gate refinement bites at runtime —
 * the entity is persisted via `SqlTableDef`, not the JSON-only fallback — and (b) the runtime
 * invariant that `applyRow` skips the immutable column is preserved: mutating `notes` flushes
 * without disturbing `label`.
 */
internal class CtorValImmutableColumnIT : FunSpec({

    val dialects =
        listOf(
            DbConfig("PostgreSQL") { PostgresContainerSupport.buildDataSource() },
            DbConfig("MySQL") { MysqlContainerSupport.buildDataSource() },
            DbConfig("MariaDB") { MariaDbContainerSupport.buildDataSource() },
            DbConfig("SQLite") { SqliteFileSupport.buildDataSource() },
            DbConfig("H2") { buildH2DataSource() }
        )

    context("ctor-param val column round-trips identically across PostgreSQL, MySQL, MariaDB, SQLite, and H2") {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, CtorValFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, CtorValFixtureEntity_LirpTableDef)
                val entity =
                    CtorValFixtureEntity("id-1", "permanent-label").apply {
                        notes = "initial"
                    }
                repo.add(entity)
                repo.close()

                val reloaded = SqlRepository(ds, CtorValFixtureEntity_LirpTableDef)
                reloaded.findById("id-1").shouldBePresent {
                    it.id shouldBe "id-1"
                    it.label shouldBe "permanent-label"
                    it.notes shouldBe "initial"
                }
                reloaded.close()
            }
        }
    }

    context("mutating a sibling var on an entity with ctor-param val column does not disturb the immutable column") {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, CtorValFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, CtorValFixtureEntity_LirpTableDef)
                repo.add(
                    CtorValFixtureEntity("id-2", "frozen-label").apply {
                        notes = "before"
                    }
                )
                // Mutate the mutable sibling through the live repo; the SqlRepository subscription
                // routes the reactive event through the debounced write pipeline.
                repo.findById("id-2").shouldBePresent {
                    it.notes = "after"
                }
                eventually(5.seconds) {
                    val verify = SqlRepository(ds, CtorValFixtureEntity_LirpTableDef)
                    verify.findById("id-2").shouldBePresent {
                        it.label shouldBe "frozen-label"
                        it.notes shouldBe "after"
                    }
                    verify.close()
                }
                repo.close()
            }
        }
    }

    context("applyRow on an entity with only ctor-param val non-PK columns reloads identically without reassigning val") {
        withTests(dialects) { db ->
            DatabaseTestSupport.withDatabaseTest(db, CtorValFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, CtorValFixtureEntity_LirpTableDef)
                // `notes` is the only mutable sibling and stays at its default "" value, so this
                // exercise focuses on the `label` ctor-val: it is rebuilt by fromRow through the
                // primary constructor and never touched by applyRow.
                repo.add(CtorValFixtureEntity("id-3", "ctor-only-label"))
                repo.close()

                val reopened = SqlRepository(ds, CtorValFixtureEntity_LirpTableDef)
                reopened.findById("id-3").shouldBePresent {
                    it.label shouldBe "ctor-only-label"
                    it.notes shouldBe ""
                }
                reopened.close()
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
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
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.nondeterministic.eventuallyConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polling config for cross-dialect persistence assertions. A mutation is persisted asynchronously
 * (reactive event dispatch → debounced flush on a shared single-threaded write scope), so reads are
 * retried with a generous window; a coarse `interval` keeps the lightweight raw reads from adding
 * load to the very scopes the pending flush depends on.
 */
private val persistedRowPoll =
    eventuallyConfig {
        duration = 30.seconds
        interval = 200.milliseconds
    }

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

    context("Short and Byte fields round-trip across PostgreSQL, MySQL, MariaDB, SQLite, and H2") {
        withTests(databases) { db ->
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
                // Mutate via the loaded repo, then poll the persisted row until the debounced write
                // pipeline has flushed. The subscription handler routes the reactive event through
                // the debounce window asynchronously; `eventually` covers that dispatch latency while
                // a lightweight raw read (instead of reconstructing a repository per poll) keeps the
                // SQLite write lock contention-free.
                reloaded.findById("e1").shouldBePresent {
                    it.year = 2024
                    it.nullableYear = 999
                    it.flag = (-12).toByte()
                    it.nullableFlag = 5
                }
                eventually(persistedRowPoll) {
                    val row =
                        DatabaseTestSupport.readRow(
                            ds, "short_byte_fixture", "e1", "year", "nullable_year", "flag", "nullable_flag"
                        )!!
                    (row["year"] as Number).toShort() shouldBe 2024.toShort()
                    (row["nullable_year"] as Number).toShort() shouldBe 999.toShort()
                    (row["flag"] as Number).toByte() shouldBe (-12).toByte()
                    (row["nullable_flag"] as Number).toByte() shouldBe 5.toByte()
                }
                reloaded.close()
            }
        }
    }

    context("nullable Short and Byte fields preserve null across persist and re-load on every dialect") {
        withTests(databases) { db ->
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
                eventually(persistedRowPoll) {
                    val row = DatabaseTestSupport.readRow(ds, "short_byte_fixture", "n1", "nullable_year", "nullable_flag")!!
                    (row["nullable_year"] as Number).toShort() shouldBe 42.toShort()
                    (row["nullable_flag"] as Number).toByte() shouldBe 9.toByte()
                }
                repo2.close()

                val repo4 = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                repo4.findById("n1").shouldBePresent {
                    it.nullableYear = null
                    it.nullableFlag = null
                }
                eventually(persistedRowPoll) {
                    val row = DatabaseTestSupport.readRow(ds, "short_byte_fixture", "n1", "nullable_year", "nullable_flag")!!
                    row["nullable_year"] shouldBe null
                    row["nullable_flag"] shouldBe null
                }
                repo4.close()
            }
        }
    }

    context("Short and Byte values at boundary ranges round-trip without overflow") {
        withTests(databases) { db ->
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
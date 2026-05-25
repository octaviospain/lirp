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
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
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
                // Mutate via the loaded repo, then poll a fresh repo until the debounced write
                // pipeline has flushed. The subscription handler routes the reactive event through
                // the debounce window asynchronously; `eventually` covers that dispatch latency.
                reloaded.findById("e1").shouldBePresent {
                    it.year = 2024
                    it.nullableYear = 999
                    it.flag = (-12).toByte()
                    it.nullableFlag = 5
                }
                eventually(10.seconds) {
                    val verifier = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                    try {
                        verifier.findById("e1").shouldBePresent {
                            it.year shouldBe 2024.toShort()
                            it.nullableYear shouldBe 999.toShort()
                            it.flag shouldBe (-12).toByte()
                            it.nullableFlag shouldBe 5.toByte()
                        }
                    } finally {
                        verifier.close()
                    }
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
                eventually(10.seconds) {
                    val afterAssign = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                    try {
                        afterAssign.findById("n1").shouldBePresent {
                            it.nullableYear shouldBe 42.toShort()
                            it.nullableFlag shouldBe 9.toByte()
                        }
                    } finally {
                        afterAssign.close()
                    }
                }
                repo2.close()

                val repo4 = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                repo4.findById("n1").shouldBePresent {
                    it.nullableYear = null
                    it.nullableFlag = null
                }
                eventually(10.seconds) {
                    val afterClear = SqlRepository(ds, ShortByteFixtureEntity_LirpTableDef)
                    try {
                        afterClear.findById("n1").shouldBePresent {
                            it.nullableYear shouldBe null
                            it.nullableFlag shouldBe null
                        }
                    } finally {
                        afterClear.close()
                    }
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
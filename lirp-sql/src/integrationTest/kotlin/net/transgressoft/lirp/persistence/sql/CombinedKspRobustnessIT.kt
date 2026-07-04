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

import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.awaitSubscriptionReady
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

    context(
        "compound entity with private-var, Short, Byte, ctor-val, and mutable siblings round-trips " +
            "across PostgreSQL, MySQL, MariaDB, SQLite, and H2"
    ) {
        withTests(databases) { db ->
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
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, CombinedKspFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                repo.add(
                    CombinedKspFixtureEntity("e2", "L").apply {
                        year = 1985
                        flag = 7
                        notes = "n0"
                    }
                )
                awaitSubscriptionReady()
                repo.findById("e2").shouldBePresent {
                    it.notes = "n1"
                    it.year = 2024
                }
                eventually(persistedRowPoll) {
                    val row = DatabaseTestSupport.readRow(ds, "combined_ksp_fixture", "e2", "label", "year", "notes")!!
                    row["label"] shouldBe "L"
                    (row["year"] as Number).toShort() shouldBe 2024.toShort()
                    row["notes"] shouldBe "n1"
                }
                repo.close()
            }
        }
    }

    context(
        "nullable Short on the compound entity preserves null across persist and update cycles"
    ) {
        withTests(databases) { db ->
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
                awaitSubscriptionReady()
                repo2.findById("e3").shouldBePresent {
                    it.nullableYear shouldBe null
                    it.nullableYear = 999
                }
                eventually(persistedRowPoll) {
                    val row = DatabaseTestSupport.readRow(ds, "combined_ksp_fixture", "e3", "nullable_year")!!
                    (row["nullable_year"] as Number).toShort() shouldBe 999.toShort()
                }
                repo2.close()

                val repo4 = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                awaitSubscriptionReady()
                repo4.findById("e3").shouldBePresent {
                    it.nullableYear = null
                }
                eventually(persistedRowPoll) {
                    val row = DatabaseTestSupport.readRow(ds, "combined_ksp_fixture", "e3", "nullable_year")!!
                    row["nullable_year"] shouldBe null
                }
                repo4.close()
            }
        }
    }

    context("private var assignments do not appear in the persisted row") {
        withTests(databases) { db ->
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
                awaitSubscriptionReady()
                reloaded.findById("e4").shouldBePresent {
                    // First sanity check: the pre-flush setCacheValue(42) did not survive — the
                    // private field is excluded from persistence.
                    it.cacheValue() shouldBe 0
                    // Now write the private field AND a non-private sibling. Only the sibling
                    // assignment should drive a flush; the private cache change is silent.
                    it.setCacheValue(99)
                    it.notes = "trigger"
                }
                // Poll the persisted row with a lightweight raw read until the sibling flush lands —
                // constructing a repository per poll would add event-subscription load to the shared
                // reactive scopes and starve the very flush we are waiting for.
                eventually(persistedRowPoll) {
                    val row = DatabaseTestSupport.readRow(ds, "combined_ksp_fixture", "e4", "notes")!!
                    // The sibling flush happened (rules out "no flush at all" as the explanation
                    // for the cache being absent from the row).
                    row["notes"] shouldBe "trigger"
                }
                reloaded.close()

                // The flush has landed; a single reload confirms the private field was excluded —
                // the 99 written in-memory never reached the row, so the rehydrated entity falls
                // back to the property initializer default.
                val verify = SqlRepository(ds, CombinedKspFixtureEntity_LirpTableDef)
                try {
                    verify.findById("e4").shouldBePresent { it.cacheValue() shouldBe 0 }
                } finally {
                    verify.close()
                }
            }
        }
    }
})
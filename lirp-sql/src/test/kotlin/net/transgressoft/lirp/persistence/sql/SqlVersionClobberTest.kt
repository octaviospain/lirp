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

import net.transgressoft.lirp.persistence.PendingUpdate
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for the `executeUpdate` version-clobber fix in [SqlWritePipeline].
 *
 * Verifies that:
 * - a null `expectedVersion` on a versioned entity fails fast rather than silently rewriting
 *   the version column to a stale pre-bump value, and
 * - a present `expectedVersion` correctly bumps the persisted version to `expected + 1`.
 */
@io.kotest.core.annotation.DisplayName("SqlWritePipeline version-clobber regression")
internal class SqlVersionClobberTest : StringSpec({

    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    /** Reflectively reads the `db` field of a [SqlRepository] for direct DDL in tests. */
    fun dbOf(repo: SqlRepository<*, *>): Database {
        val field = SqlRepository::class.java.getDeclaredField("db").apply { isAccessible = true }
        return field.get(repo) as Database
    }

    /** Reflectively reads the `writePipeline` field of a [SqlRepository]. */
    @Suppress("UNCHECKED_CAST")
    fun pipelineOf(repo: SqlRepository<Int, TestVersionedPerson>): SqlWritePipeline<Int, TestVersionedPerson> {
        val field = SqlRepository::class.java.getDeclaredField("writePipeline").apply { isAccessible = true }
        return field.get(repo) as SqlWritePipeline<Int, TestVersionedPerson>
    }

    "[SqlWritePipeline] fails fast when executeUpdate receives null expectedVersion for versioned entity" {
        val repo: SqlRepository<Int, TestVersionedPerson> = SqlRepository(freshJdbcUrl(), TestVersionedPersonTableDef)
        val pipeline = pipelineOf(repo)

        val person =
            TestVersionedPerson(1).also {
                it.firstName = "Alice"
                it.lastName = "Smith"
                it.age = 30
            }
        // Insert the entity so there is a row to update.
        repo.add(person)
        eventually(5.seconds) { repo.findById(1).shouldBePresent { it shouldBe person } }

        // Drive executeUpdate directly with null expectedVersion — the caller-bug path
        // that must be rejected with error() rather than silently clobbering the version column.
        val nullVersionOp = PendingUpdate(person, expectedVersion = null)
        val conflicts = mutableListOf<PendingConflict<Int>>()

        shouldThrow<IllegalStateException> {
            transaction(db = dbOf(repo)) {
                pipeline.executeUpdate(nullVersionOp, conflicts)
            }
        }

        // No conflict accumulated — fail-fast exits before conflict logic.
        conflicts.isEmpty() shouldBe true

        // Version remains 0 on the live entity (nothing was flushed by the rejected call).
        person.version shouldBe 0L

        repo.close()
    }

    "[SqlWritePipeline] bumps version to expected + 1 on a normal versioned update" {
        val repo: SqlRepository<Int, TestVersionedPerson> = SqlRepository(freshJdbcUrl(), TestVersionedPersonTableDef)
        val db = dbOf(repo)

        val person =
            TestVersionedPerson(2).also {
                it.firstName = "Bob"
                it.lastName = "Jones"
                it.age = 25
            }
        repo.add(person)

        // Wait for the INSERT to reach the DB before mutating, so the subsequent mutation
        // is enqueued as an UPDATE rather than merged into the pending INSERT.
        eventually(5.seconds) {
            val count =
                transaction(db = db) {
                    exec("SELECT COUNT(*) FROM ${TestVersionedPersonTableDef.tableName} WHERE id = 2") { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            count shouldBe 1
        }

        // Mutate the entity — now an UPDATE is enqueued; subscription captures expectedVersion = 0L.
        // Pipeline must persist version = 1L and bump the in-memory version to match.
        person.firstName = "Robert"

        eventually(5.seconds) {
            // person is the live registry entity; bumpVersion sets person.version directly after flush.
            person.version shouldBe 1L
        }

        repo.close()
    }
})
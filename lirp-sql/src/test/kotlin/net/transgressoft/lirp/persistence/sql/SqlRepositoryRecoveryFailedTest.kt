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

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.StandardCrudEvent
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Regression tests for #201: `SqlRepository.writePending` must not silently swallow
 * `recoverEntityFromConflict` exceptions. After [SqlRepository.MAX_RECOVERY_ATTEMPTS] consecutive
 * failures on the same id the retry queue escalates to a [StandardCrudEvent.RecoveryFailed] event
 * and drops the entry.
 *
 * The retry path (`drainStaleIds()`) is private and runs at the start of each `writePending`
 * cycle. To exercise the escalation logic in isolation, these tests inject entries directly into
 * the private `staleIds` map via reflection and drop the underlying table to force every retry
 * attempt to throw. A normal `add()` then triggers the debounced flush, which in turn drains the
 * seeded entries.
 */
class SqlRepositoryRecoveryFailedTest : StringSpec({

    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    /** Reflectively reads the `db` field of a [SqlRepository] for direct DDL execution in tests. */
    fun dbOf(repo: SqlRepository<*, *>): Database {
        val field = SqlRepository::class.java.getDeclaredField("db").apply { isAccessible = true }
        return field.get(repo) as Database
    }

    /** Reflectively accesses the private `staleIds` map. */
    @Suppress("UNCHECKED_CAST")
    fun staleIdsOf(repo: SqlRepository<*, *>): ConcurrentHashMap<Any, Any> {
        val field = SqlRepository::class.java.getDeclaredField("staleIds").apply { isAccessible = true }
        return field.get(repo) as ConcurrentHashMap<Any, Any>
    }

    /**
     * Constructs a [SqlRepository.StaleEntry] via reflection on the private nested class.
     */
    fun newStaleEntry(expectedVersion: Long, attempts: Int): Any {
        val cls = SqlRepository::class.java.declaredClasses.first { it.simpleName == "StaleEntry" }
        val ctor = cls.declaredConstructors.first().apply { isAccessible = true }
        return ctor.newInstance(expectedVersion, attempts)
    }

    fun attemptsOf(entry: Any): Int {
        val f = entry.javaClass.getDeclaredField("attempts").apply { isAccessible = true }
        return f.get(entry) as Int
    }

    "[SqlRepositoryRecoveryFailed] emits RecoveryFailed event after MAX_RECOVERY_ATTEMPTS consecutive recovery failures for same id" {
        val repo = SqlRepository(freshJdbcUrl(), TestVersionedPersonTableDef)
        val received = CopyOnWriteArrayList<CrudEvent<*, *>>()
        repo.subscribe { event -> received.add(event) }
        delay(50.milliseconds)

        // Seed the retry queue at the escalation threshold so a single failed retry escalates.
        staleIdsOf(repo)[999] = newStaleEntry(expectedVersion = 5L, attempts = SqlRepository.MAX_RECOVERY_ATTEMPTS)

        // Drop the parent table so the retry's transaction throws an Exposed SQL exception.
        transaction(db = dbOf(repo)) {
            exec("DROP TABLE ${TestVersionedPersonTableDef.tableName}")
        }

        // Trigger a flush. close() invokes flush() unconditionally; even with no pending ops,
        // writePending only runs when there is work — so we force work via a no-op clear() that
        // sets hadClear=true. Easier: invoke writePending directly via reflection.
        val writePending =
            SqlRepository::class.java.getDeclaredMethod(
                "writePending",
                List::class.java, List::class.java, List::class.java, Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
        try {
            writePending.invoke(repo, emptyList<Any>(), emptyList<Any>(), emptyList<Any>(), true)
        } catch (_: Exception) {
            // The hadClear branch will throw because the table is dropped; the drain runs first
            // (at the very start of writePending) and emits RecoveryFailed before the throw.
        }

        eventually(5.seconds) {
            val recoveryFailed = received.filterIsInstance<StandardCrudEvent.RecoveryFailed<*, *>>()
            recoveryFailed.size shouldBe 1
            val event = recoveryFailed.first()
            event.id shouldBe 999
            event.expectedVersion shouldBe 5L
            event.type shouldBe CrudEvent.Type.RECOVERY_FAILED
            event.cause.shouldBeInstanceOf<Exception>()
        }

        try {
            repo.close()
        } catch (_: Exception) {
        }
    }

    "[SqlRepositoryRecoveryFailed] removes staleIds entry after escalation" {
        val repo = SqlRepository(freshJdbcUrl(), TestVersionedPersonTableDef)

        staleIdsOf(repo)[42] = newStaleEntry(expectedVersion = 1L, attempts = SqlRepository.MAX_RECOVERY_ATTEMPTS)
        staleIdsOf(repo).size shouldBe 1

        transaction(db = dbOf(repo)) {
            exec("DROP TABLE ${TestVersionedPersonTableDef.tableName}")
        }

        val writePending =
            SqlRepository::class.java.getDeclaredMethod(
                "writePending",
                List::class.java, List::class.java, List::class.java, Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
        try {
            writePending.invoke(repo, emptyList<Any>(), emptyList<Any>(), emptyList<Any>(), true)
        } catch (_: Exception) {
        }

        staleIdsOf(repo).size shouldBe 0

        try {
            repo.close()
        } catch (_: Exception) {
        }
    }

    "[SqlRepositoryRecoveryFailed] increments attempts when below escalation threshold" {
        val repo = SqlRepository(freshJdbcUrl(), TestVersionedPersonTableDef)

        staleIdsOf(repo)[7] = newStaleEntry(expectedVersion = 2L, attempts = 1)
        staleIdsOf(repo).size shouldBe 1

        transaction(db = dbOf(repo)) {
            exec("DROP TABLE ${TestVersionedPersonTableDef.tableName}")
        }

        val writePending =
            SqlRepository::class.java.getDeclaredMethod(
                "writePending",
                List::class.java, List::class.java, List::class.java, Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
        try {
            writePending.invoke(repo, emptyList<Any>(), emptyList<Any>(), emptyList<Any>(), true)
        } catch (_: Exception) {
        }

        // Below-threshold failure keeps the entry, with attempts incremented to 2.
        staleIdsOf(repo).size shouldBe 1
        attemptsOf(staleIdsOf(repo)[7]!!) shouldBe 2

        try {
            repo.close()
        } catch (_: Exception) {
        }
    }
})
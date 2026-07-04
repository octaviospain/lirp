/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

import net.transgressoft.lirp.persistence.sql.fixture.CtorVarEmbeddedFixtureEntity
import net.transgressoft.lirp.persistence.sql.fixture.CtorVarEmbeddedFixtureEntity_LirpTableDef
import net.transgressoft.lirp.persistence.sql.fixture.LocationValue
import net.transgressoft.lirp.persistence.sql.fixture.ReactiveEmbeddedFixtureEntity
import net.transgressoft.lirp.persistence.sql.fixture.ReactiveEmbeddedFixtureEntity_LirpTableDef
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * H2 round-trip tests for the `@Embedded` body-declared reactive property codegen path.
 *
 * Verifies that the KSP-generated `ReactiveEmbeddedFixtureEntity_LirpTableDef` correctly
 * flattens and reconstructs a `LocationValue` declared as `var location by reactiveProperty(...)`
 * via the `silentSetter` path on load — preserving leaf values and emitting no spurious
 * property mutation events during hydration.
 *
 * Also covers the ctor-`var @Embedded` round-trip path via [CtorVarEmbeddedFixtureEntity].
 */
internal class ReactiveEmbeddedSqlTest : StringSpec({

    extension(IsolatedReactiveScope)

    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    "ReactiveEmbeddedFixtureEntity round-trips body-declared @Embedded leaf values on H2" {
        val jdbcUrl = freshJdbcUrl()
        val location = LocationValue("51.5074", "-0.1278")
        val entity = ReactiveEmbeddedFixtureEntity(id = 1).also { it.location = location }

        SqlRepository(jdbcUrl, ReactiveEmbeddedFixtureEntity_LirpTableDef).use { seedRepo ->
            seedRepo.add(entity)
        }

        val reloaded = SqlRepository(jdbcUrl, ReactiveEmbeddedFixtureEntity_LirpTableDef)
        reloaded.use { reloaded ->
            reloaded.findById(1).shouldBePresent {
                it.location.lat shouldBe "51.5074"
                it.location.lon shouldBe "-0.1278"
            }
        }
    }

    "loading body-declared @Embedded entity fires no MutationEvent during hydration" {
        val jdbcUrl = freshJdbcUrl()
        val location = LocationValue("48.8566", "2.3522")
        val entity = ReactiveEmbeddedFixtureEntity(id = 2).also { it.location = location }

        SqlRepository(jdbcUrl, ReactiveEmbeddedFixtureEntity_LirpTableDef).use { seedRepo ->
            seedRepo.add(entity)
        }

        // Fresh repo triggers full SQL load into the in-memory map. Verify that entity-level
        // subscribers see exactly one mutation event when a post-load mutation is applied,
        // proving hydration was silent (any hydration events would appear in the count).
        val mutationEvents = CopyOnWriteArrayList<String>()
        val reloaded = SqlRepository(jdbcUrl, ReactiveEmbeddedFixtureEntity_LirpTableDef)
        reloaded.use { reloaded ->
            val loadedEntity = reloaded.findById(2).orElseThrow()
            loadedEntity.subscribe { ev -> mutationEvents += ev.toString() }

            // Deliberate mutation to verify subscription is wired and fires exactly once.
            // If hydration had fired events, the count would exceed 1.
            loadedEntity.location = LocationValue("0.0", "0.0")

            eventually(1.seconds) {
                mutationEvents.size shouldBe 1
            }
        }
    }

    "CtorVarEmbeddedFixtureEntity round-trips ctor-var @Embedded leaf values on H2" {
        val jdbcUrl = freshJdbcUrl()
        val location = LocationValue("35.6762", "139.6503")
        val entity = CtorVarEmbeddedFixtureEntity(id = 1, location = location)

        SqlRepository(jdbcUrl, CtorVarEmbeddedFixtureEntity_LirpTableDef).use { seedRepo ->
            seedRepo.add(entity)
        }

        val reloaded = SqlRepository(jdbcUrl, CtorVarEmbeddedFixtureEntity_LirpTableDef)
        reloaded.use { reloaded ->
            reloaded.findById(1).shouldBePresent {
                it.location.lat shouldBe "35.6762"
                it.location.lon shouldBe "139.6503"
            }
        }
    }
})
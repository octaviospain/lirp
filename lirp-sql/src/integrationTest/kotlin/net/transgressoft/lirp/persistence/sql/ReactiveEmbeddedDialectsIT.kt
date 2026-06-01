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

import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import net.transgressoft.lirp.persistence.sql.fixture.LocationValue
import net.transgressoft.lirp.persistence.sql.fixture.ReactiveEmbeddedFixtureEntity
import net.transgressoft.lirp.persistence.sql.fixture.ReactiveEmbeddedFixtureEntity_LirpTableDef
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe

/**
 * Cross-dialect round-trip integration tests for the `@Embedded` body-declared reactive property
 * codegen path.
 *
 * Verifies that the KSP-generated `ReactiveEmbeddedFixtureEntity_LirpTableDef` correctly flattens
 * a `LocationValue` to scalar columns and reconstructs it via `silentSetter` on reload across all
 * four supported dialects: PostgreSQL, MySQL, MariaDB and SQLite.
 */
internal class ReactiveEmbeddedDialectsIT : FunSpec({

    context("ReactiveEmbeddedFixtureEntity round-trips body-declared @Embedded across all dialects") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, ReactiveEmbeddedFixtureEntity_LirpTableDef) { ds ->
                val location = LocationValue("51.5074", "-0.1278")
                val repo = SqlRepository(ds, ReactiveEmbeddedFixtureEntity_LirpTableDef)
                try {
                    repo.add(ReactiveEmbeddedFixtureEntity(id = 1).also { it.location = location })
                } finally {
                    repo.close()
                }

                val reloaded = SqlRepository(ds, ReactiveEmbeddedFixtureEntity_LirpTableDef)
                try {
                    reloaded.findById(1).shouldBePresent {
                        it.location shouldBe location
                    }
                } finally {
                    reloaded.close()
                }
            }
        }
    }
})
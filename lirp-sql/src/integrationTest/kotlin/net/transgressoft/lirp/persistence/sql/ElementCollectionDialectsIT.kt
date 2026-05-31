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
import net.transgressoft.lirp.persistence.sql.fixture.ElementCollectionFixtureEntity
import net.transgressoft.lirp.persistence.sql.fixture.ElementCollectionFixtureEntity_LirpTableDef
import net.transgressoft.lirp.persistence.sql.fixture.Rating
import net.transgressoft.lirp.persistence.sql.fixture.Tag
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe

/**
 * Cross-dialect round-trip integration tests for the `@ElementCollection` codegen surface.
 *
 * Verifies that the KSP-generated `ElementCollectionFixtureEntity_LirpTableDef` correctly
 * encodes and decodes both `Set<Rating>` (non-String-S / native-Int JSON path) and `List<Tag>`
 * (String-S path) against every supported dialect via `DatabaseTestSupport`.
 *
 * Three scenarios per dialect exercise the three distinct risk surfaces: empty-collection
 * DEFAULT `'[]'` DDL portability, non-String-S native-Int encoding symmetry, and
 * List insertion-order preservation.
 */
internal class ElementCollectionDialectsIT : FunSpec({

    context("ElementCollectionFixtureEntity round-trips empty collections across all dialects") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, ElementCollectionFixtureEntity_LirpTableDef) { ds ->
                val repo = SqlRepository(ds, ElementCollectionFixtureEntity_LirpTableDef)
                try {
                    repo.add(ElementCollectionFixtureEntity(id = 1))
                } finally {
                    repo.close()
                }

                val reloaded = SqlRepository(ds, ElementCollectionFixtureEntity_LirpTableDef)
                try {
                    reloaded.findById(1).shouldBePresent {
                        it.ratings shouldBe emptySet()
                        it.tags shouldBe emptyList()
                    }
                } finally {
                    reloaded.close()
                }
            }
        }
    }

    context("ElementCollectionFixtureEntity round-trips Set<Rating> with native-Int-S encoding across all dialects") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, ElementCollectionFixtureEntity_LirpTableDef) { ds ->
                val ratings = setOf(Rating(1), Rating(5), Rating(3))
                val repo = SqlRepository(ds, ElementCollectionFixtureEntity_LirpTableDef)
                try {
                    repo.add(ElementCollectionFixtureEntity(id = 2, ratings = ratings))
                } finally {
                    repo.close()
                }

                val reloaded = SqlRepository(ds, ElementCollectionFixtureEntity_LirpTableDef)
                try {
                    reloaded.findById(2).shouldBePresent {
                        it.ratings shouldContainExactlyInAnyOrder ratings
                    }
                } finally {
                    reloaded.close()
                }
            }
        }
    }

    context("ElementCollectionFixtureEntity round-trips List<Tag> preserving insertion order across all dialects") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, ElementCollectionFixtureEntity_LirpTableDef) { ds ->
                val tags = listOf(Tag("rock"), Tag("jazz"), Tag("blues"))
                val repo = SqlRepository(ds, ElementCollectionFixtureEntity_LirpTableDef)
                try {
                    repo.add(ElementCollectionFixtureEntity(id = 3, tags = tags))
                } finally {
                    repo.close()
                }

                val reloaded = SqlRepository(ds, ElementCollectionFixtureEntity_LirpTableDef)
                try {
                    reloaded.findById(3).shouldBePresent {
                        it.tags shouldContainExactly tags
                    }
                } finally {
                    reloaded.close()
                }
            }
        }
    }
})
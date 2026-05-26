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
import net.transgressoft.lirp.persistence.sql.fixture.CatalogItem
import net.transgressoft.lirp.persistence.sql.fixture.CatalogItem_LirpTableDef
import net.transgressoft.lirp.persistence.sql.fixture.L1Top
import net.transgressoft.lirp.persistence.sql.fixture.L2Mid
import net.transgressoft.lirp.persistence.sql.fixture.L3Leaf
import net.transgressoft.lirp.persistence.sql.fixture.MediaEntity
import net.transgressoft.lirp.persistence.sql.fixture.MediaEntity_LirpTableDef
import net.transgressoft.lirp.persistence.sql.fixture.MediaValue
import net.transgressoft.lirp.persistence.sql.fixture.PersonValue
import net.transgressoft.lirp.persistence.sql.fixture.PublisherValue
import net.transgressoft.lirp.persistence.sql.fixture.ThreeLevelEntity
import net.transgressoft.lirp.persistence.sql.fixture.ThreeLevelEntity_LirpTableDef
import net.transgressoft.lirp.persistence.sql.fixture.WorkValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import java.nio.file.Paths

/**
 * Cross-dialect round-trip integration test for the `@Embeddable` + `@Embedded` codegen surface.
 *
 * Each case persists and reloads a fixture entity through `SqlRepository` against every supported
 * dialect (PostgreSQL, MySQL, MariaDB, SQLite, H2) and asserts the reloaded value equals the
 * original. Round-trip equality implicitly proves the generated `fromRow` / `toParams` resolve
 * the flattened columns correctly — the column-name scheme itself is locked by the KSP unit
 * tests in `lirp-ksp`, so this layer only verifies dialect-agnostic SQL behaviour.
 */
internal class EmbeddableRoundTripIT : FunSpec({

    context("CatalogItem persists 2-level @Embedded WorkValue across all supported dialects") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, CatalogItem_LirpTableDef) { ds ->
                val work =
                    WorkValue(
                        title = "Concerto",
                        performer = PersonValue("Alice", "ES"),
                        isCompilation = true,
                        year = 1999,
                        publisher = PublisherValue("Imprint", "GB")
                    )
                val original = CatalogItem(1L, "Item One", work, Paths.get("/tmp/item.bin"))
                val expectedPath = Paths.get(original.path.toUri())

                val repo = SqlRepository(ds, CatalogItem_LirpTableDef)
                try {
                    repo.add(original)
                } finally {
                    repo.close()
                }

                val reloaded = SqlRepository(ds, CatalogItem_LirpTableDef)
                try {
                    reloaded.findById(1L).shouldBePresent { fetched ->
                        fetched.title shouldBe "Item One"
                        fetched.work shouldBe work
                        fetched.path shouldBe expectedPath
                    }
                } finally {
                    reloaded.close()
                }
            }
        }
    }

    context("ThreeLevelEntity persists 3-level @Embedded chain across all supported dialects") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, ThreeLevelEntity_LirpTableDef) { ds ->
                val original = ThreeLevelEntity(10L, L1Top(L2Mid(L3Leaf("deep"))))

                val repo = SqlRepository(ds, ThreeLevelEntity_LirpTableDef)
                try {
                    repo.add(original)
                } finally {
                    repo.close()
                }

                val reloaded = SqlRepository(ds, ThreeLevelEntity_LirpTableDef)
                try {
                    reloaded.findById(10L).shouldBePresent { it.top.mid.leaf.value shouldBe "deep" }
                } finally {
                    reloaded.close()
                }
            }
        }
    }

    context("MediaEntity persists @Embeddable containing @PersistenceProperty converter leaf across all supported dialects") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, MediaEntity_LirpTableDef) { ds ->
                val original = MediaEntity(20L, MediaValue("stream", Paths.get("/tmp/leaf.bin")))
                val expectedPath = Paths.get(original.media.path.toUri())

                val repo = SqlRepository(ds, MediaEntity_LirpTableDef)
                try {
                    repo.add(original)
                } finally {
                    repo.close()
                }

                val reloaded = SqlRepository(ds, MediaEntity_LirpTableDef)
                try {
                    reloaded.findById(20L).shouldBePresent { fetched ->
                        fetched.media.name shouldBe "stream"
                        fetched.media.path shouldBe expectedPath
                    }
                } finally {
                    reloaded.close()
                }
            }
        }
    }
})
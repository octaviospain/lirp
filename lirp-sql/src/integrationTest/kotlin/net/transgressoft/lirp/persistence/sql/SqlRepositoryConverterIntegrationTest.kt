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
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import java.nio.file.Paths
import java.time.Duration

/**
 * Cross-dialect round-trip integration test asserting that `@PersistenceProperty(converter = …)`
 * routes non-scalar domain types (`java.nio.file.Path`, `java.time.Duration`) through the
 * KSP-generated `_LirpTableDef` correctly on every supported SQL backend.
 *
 * Each dialect runs two scenarios: a non-null round-trip exercising both [PathConverter] and
 * [DurationConverter], and a nullable round-trip preserving `null` through write and read.
 */
internal class SqlRepositoryConverterIntegrationTest : FunSpec({

    withData(databases) { db ->
        test("SqlRepository round-trips ConverterFixtureEntity with non-null converter-routed fields") {
            DatabaseTestSupport.withDatabaseTest(db, ConverterFixtureEntity_LirpTableDef) { ds ->
                val originalPath = Paths.get("/tmp/song.mp3")
                val originalCover = Paths.get("/tmp/cover.png")
                val originalLength = Duration.ofSeconds(180)
                // PathConverter canonicalises through URI; assertions compare against the same
                // normalised form to avoid platform-separator drift on round-trip.
                val expectedPath = Paths.get(originalPath.toUri())
                val expectedCover = Paths.get(originalCover.toUri())

                val repo = SqlRepository(ds, ConverterFixtureEntity_LirpTableDef)
                try {
                    repo.add(ConverterFixtureEntity(1, originalPath, originalLength, originalCover))
                } finally {
                    repo.close()
                }

                val reloaded = SqlRepository(ds, ConverterFixtureEntity_LirpTableDef)
                try {
                    reloaded.findById(1).shouldBePresent {
                        it.path shouldBe expectedPath
                        it.length shouldBe originalLength
                        it.coverPath shouldBe expectedCover
                    }
                } finally {
                    reloaded.close()
                }
            }
        }

        test("SqlRepository preserves null in a nullable converter-routed column") {
            DatabaseTestSupport.withDatabaseTest(db, ConverterFixtureEntity_LirpTableDef) { ds ->
                val originalPath = Paths.get("/tmp/song.mp3")
                val originalLength = Duration.ofSeconds(60)

                val repo = SqlRepository(ds, ConverterFixtureEntity_LirpTableDef)
                try {
                    repo.add(ConverterFixtureEntity(2, originalPath, originalLength, coverPath = null))
                } finally {
                    repo.close()
                }

                val reloaded = SqlRepository(ds, ConverterFixtureEntity_LirpTableDef)
                try {
                    reloaded.findById(2).shouldBePresent {
                        it.coverPath shouldBe null
                        it.path shouldBe Paths.get(originalPath.toUri())
                        it.length shouldBe originalLength
                    }
                } finally {
                    reloaded.close()
                }
            }
        }
    }
})
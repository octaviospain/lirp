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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.UUID

/**
 * H2 round-trip unit tests for the `@PersistenceProperty(converter = …)` codegen path —
 * verifies that the KSP-generated `_LirpTableDef` routes non-null and nullable converter-bearing
 * columns through `PathConverter` / `DurationConverter` in both directions (fromRow / toParams).
 */
internal class SqlRepositoryConverterTest : StringSpec({

    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    "SqlRepository round-trips ConverterFixtureEntity with non-null converter-routed fields on H2" {
        val jdbcUrl = freshJdbcUrl()
        val originalPath = Paths.get("/tmp/song.mp3")
        val originalCover = Paths.get("/tmp/cover.png")
        val originalLength = Duration.ofSeconds(180)
        // Compare against URI-normalised forms — PathConverter encodes via toUri() / parses
        // via Paths.get(URI(…)), so the round-trip canonicalises platform-specific separators.
        val expectedPath = Paths.get(originalPath.toUri())
        val expectedCover = Paths.get(originalCover.toUri())

        val repo = SqlRepository(jdbcUrl, ConverterFixtureEntity_LirpTableDef)
        try {
            repo.add(ConverterFixtureEntity(1, originalPath, originalLength, originalCover))
        } finally {
            repo.close()
        }

        val reloaded = SqlRepository(jdbcUrl, ConverterFixtureEntity_LirpTableDef)
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

    "SqlRepository preserves null in a nullable converter-routed column on H2" {
        val jdbcUrl = freshJdbcUrl()
        val originalPath = Paths.get("/tmp/song.mp3")
        val originalLength = Duration.ofSeconds(60)

        val repo = SqlRepository(jdbcUrl, ConverterFixtureEntity_LirpTableDef)
        try {
            repo.add(ConverterFixtureEntity(2, originalPath, originalLength, coverPath = null))
        } finally {
            repo.close()
        }

        val reloaded = SqlRepository(jdbcUrl, ConverterFixtureEntity_LirpTableDef)
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

    "SqlRepository persists a non-null cover path after the entity was first stored with null" {
        val jdbcUrl = freshJdbcUrl()
        val basePath = Paths.get("/tmp/song.mp3")

        // Seed phase: insert the entity with coverPath = null and flush via close().
        val seedRepo = SqlRepository(jdbcUrl, ConverterFixtureEntity_LirpTableDef)
        try {
            seedRepo.add(ConverterFixtureEntity(3, basePath, Duration.ofSeconds(90), coverPath = null))
        } finally {
            seedRepo.close()
        }

        // Delete phase: open a fresh repo, remove the entity, and close() to flush the delete
        // before any subsequent insert can race the PK constraint check.
        val deletingRepo = SqlRepository(jdbcUrl, ConverterFixtureEntity_LirpTableDef)
        val loaded: ConverterFixtureEntity
        try {
            loaded = deletingRepo.findById(3).get()
            loaded.coverPath shouldBe null
            deletingRepo.remove(loaded)
        } finally {
            deletingRepo.close()
        }

        // Re-insert phase: ConverterFixtureEntity is a data class with constructor `val`s, so
        // the null→non-null transition is expressed as delete-then-reinsert; data-class copy()
        // produces the updated entity carrying a non-null coverPath.
        val newCover: Path = Paths.get("/tmp/cover-new.png")
        val rewriteRepo = SqlRepository(jdbcUrl, ConverterFixtureEntity_LirpTableDef)
        try {
            rewriteRepo.add(loaded.copy(coverPath = newCover))
        } finally {
            rewriteRepo.close()
        }

        val verifier = SqlRepository(jdbcUrl, ConverterFixtureEntity_LirpTableDef)
        try {
            verifier.findById(3).shouldBePresent {
                it.coverPath shouldBe Paths.get(newCover.toUri())
            }
        } finally {
            verifier.close()
        }
    }
})
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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import java.net.URI
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * H2 round-trip unit tests for the built-in default `ColumnConverter` resolution path — verifies
 * that the KSP-generated `_LirpTableDef` routes un-annotated [java.nio.file.Path],
 * [java.time.Duration], [java.time.Instant], and [java.net.URI] columns through the built-in
 * converters in both directions, with a nullable column preserving `null`.
 */
internal class SqlRepositoryDefaultConverterTest : StringSpec({

    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    "SqlRepository round-trips DefaultConverterFixtureEntity through the built-in converters on H2" {
        val jdbcUrl = freshJdbcUrl()
        val originalPath = Paths.get("/tmp/song.mp3")
        val originalCover = Paths.get("/tmp/cover.png")
        val originalLength = Duration.ofSeconds(180).plusNanos(123_456_789)
        val originalInstant = Instant.parse("2026-06-23T10:15:30.250Z")
        val originalSource = URI("https://example.com/catalog/42")
        // PathColumnConverter encodes via toUri() and parses via Paths.get(URI(…)), so the round-trip
        // canonicalises platform-specific separators against the URI-normalised form.
        val expectedPath = Paths.get(originalPath.toUri())
        val expectedCover = Paths.get(originalCover.toUri())

        val repo = SqlRepository(jdbcUrl, DefaultConverterFixtureEntity_LirpTableDef)
        try {
            repo.add(DefaultConverterFixtureEntity(1, originalPath, originalLength, originalInstant, originalSource, originalCover))
        } finally {
            repo.close()
        }

        val reloaded = SqlRepository(jdbcUrl, DefaultConverterFixtureEntity_LirpTableDef)
        try {
            reloaded.findById(1).shouldBePresent {
                it.path shouldBe expectedPath
                it.length shouldBe originalLength
                it.recordedAt shouldBe originalInstant
                it.source shouldBe originalSource
                it.coverPath shouldBe expectedCover
            }
        } finally {
            reloaded.close()
        }
    }

    "SqlRepository preserves null in a nullable default-converter column on H2" {
        val jdbcUrl = freshJdbcUrl()
        val originalPath = Paths.get("/tmp/song.mp3")

        val repo = SqlRepository(jdbcUrl, DefaultConverterFixtureEntity_LirpTableDef)
        try {
            repo.add(
                DefaultConverterFixtureEntity(
                    id = 2,
                    path = originalPath,
                    length = Duration.ofSeconds(60),
                    recordedAt = Instant.parse("2026-06-23T11:00:00Z"),
                    source = URI("https://example.com/catalog/2"),
                    coverPath = null
                )
            )
        } finally {
            repo.close()
        }

        val reloaded = SqlRepository(jdbcUrl, DefaultConverterFixtureEntity_LirpTableDef)
        try {
            reloaded.findById(2).shouldBePresent {
                it.coverPath shouldBe null
                it.path shouldBe Paths.get(originalPath.toUri())
            }
        } finally {
            reloaded.close()
        }
    }
})
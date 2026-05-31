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

import net.transgressoft.lirp.persistence.sql.fixture.ElementCollectionFixtureEntity
import net.transgressoft.lirp.persistence.sql.fixture.ElementCollectionFixtureEntity_LirpTableDef
import net.transgressoft.lirp.persistence.sql.fixture.Rating
import net.transgressoft.lirp.persistence.sql.fixture.ReactiveElementCollectionFixtureEntity
import net.transgressoft.lirp.persistence.sql.fixture.ReactiveElementCollectionFixtureEntity_LirpTableDef
import net.transgressoft.lirp.persistence.sql.fixture.Tag
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * H2 round-trip tests for the `@ElementCollection` codegen path, verifying that the
 * KSP-generated `ElementCollectionFixtureEntity_LirpTableDef` correctly encodes and decodes
 * both `Set<Rating>` (non-String-S element converter) and `List<Tag>` (String-S element
 * converter) across persist → reload cycles.
 */
internal class ElementCollectionH2RoundTripTest : StringSpec({

    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    "ElementCollectionFixtureEntity round-trips with empty collections on H2" {
        val jdbcUrl = freshJdbcUrl()
        val entity = ElementCollectionFixtureEntity(id = 1)

        val seedRepo = SqlRepository(jdbcUrl, ElementCollectionFixtureEntity_LirpTableDef)
        try {
            seedRepo.add(entity)
        } finally {
            seedRepo.close()
        }

        val reloaded = SqlRepository(jdbcUrl, ElementCollectionFixtureEntity_LirpTableDef)
        try {
            reloaded.findById(1).shouldBePresent {
                it.ratings shouldBe emptySet()
                it.tags shouldBe emptyList()
            }
        } finally {
            reloaded.close()
        }
    }

    "ElementCollectionFixtureEntity round-trips Set<Rating> with native-Int JSON encoding on H2" {
        val jdbcUrl = freshJdbcUrl()
        val ratings = setOf(Rating(1), Rating(5), Rating(3))
        val entity = ElementCollectionFixtureEntity(id = 2, ratings = ratings)

        val seedRepo = SqlRepository(jdbcUrl, ElementCollectionFixtureEntity_LirpTableDef)
        try {
            seedRepo.add(entity)
        } finally {
            seedRepo.close()
        }

        val reloaded = SqlRepository(jdbcUrl, ElementCollectionFixtureEntity_LirpTableDef)
        try {
            reloaded.findById(2).shouldBePresent {
                it.ratings shouldContainExactlyInAnyOrder ratings
            }
        } finally {
            reloaded.close()
        }
    }

    "ElementCollectionFixtureEntity round-trips List<Tag> preserving insertion order on H2" {
        val jdbcUrl = freshJdbcUrl()
        val tags = listOf(Tag("rock"), Tag("jazz"), Tag("blues"))
        val entity = ElementCollectionFixtureEntity(id = 3, tags = tags)

        val seedRepo = SqlRepository(jdbcUrl, ElementCollectionFixtureEntity_LirpTableDef)
        try {
            seedRepo.add(entity)
        } finally {
            seedRepo.close()
        }

        val reloaded = SqlRepository(jdbcUrl, ElementCollectionFixtureEntity_LirpTableDef)
        try {
            reloaded.findById(3).shouldBePresent {
                it.tags shouldContainExactly tags
            }
        } finally {
            reloaded.close()
        }
    }

    "ReactiveElementCollectionFixtureEntity round-trips body-declared reactive collections on H2" {
        val jdbcUrl = freshJdbcUrl()
        val ratings = setOf(Rating(4), Rating(2))
        val tags = listOf(Tag("ambient"), Tag("electronic"))
        val entity =
            ReactiveElementCollectionFixtureEntity(id = 10).apply {
                this.ratings = ratings
                this.tags = tags
            }

        val seedRepo = SqlRepository(jdbcUrl, ReactiveElementCollectionFixtureEntity_LirpTableDef)
        try {
            seedRepo.add(entity)
        } finally {
            seedRepo.close()
        }

        val reloaded = SqlRepository(jdbcUrl, ReactiveElementCollectionFixtureEntity_LirpTableDef)
        try {
            reloaded.findById(10).shouldBePresent {
                it.ratings shouldContainExactlyInAnyOrder ratings
                it.tags shouldContainExactly tags
            }
        } finally {
            reloaded.close()
        }
    }

    "ReactiveElementCollectionFixtureEntity round-trips empty reactive collections on H2" {
        val jdbcUrl = freshJdbcUrl()
        val entity = ReactiveElementCollectionFixtureEntity(id = 11)

        val seedRepo = SqlRepository(jdbcUrl, ReactiveElementCollectionFixtureEntity_LirpTableDef)
        try {
            seedRepo.add(entity)
        } finally {
            seedRepo.close()
        }

        val reloaded = SqlRepository(jdbcUrl, ReactiveElementCollectionFixtureEntity_LirpTableDef)
        try {
            reloaded.findById(11).shouldBePresent {
                it.ratings shouldBe emptySet()
                it.tags shouldBe emptyList()
            }
        } finally {
            reloaded.close()
        }
    }
})
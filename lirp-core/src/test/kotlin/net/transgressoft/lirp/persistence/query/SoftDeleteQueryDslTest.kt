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

package net.transgressoft.lirp.persistence.query

import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.SoftDeletableMutableAudioItem
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Tests for the [QueryBuilder.includeDeleted] / [QueryBuilder.onlyDeleted] query DSL verbs.
 *
 * The default query excludes soft-deleted entities (fail-closed). The opt-in verbs allow
 * callers to explicitly request soft-deleted entities:
 * - [QueryBuilder.includeDeleted]: returns active + soft-deleted entities.
 * - [QueryBuilder.onlyDeleted]: returns only soft-deleted entities.
 *
 * The two verbs are mutually exclusive; combining them throws [IllegalStateException].
 * Both flags propagate through [Query] with `false` defaults and a mutual-exclusion `require`.
 */
@DisplayName("SoftDeleteQueryDslTest")
internal class SoftDeleteQueryDslTest : StringSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext
    lateinit var audioItemRepo: AudioItemVolatileRepository

    beforeEach {
        ctx = LirpContext()
        audioItemRepo = AudioItemVolatileRepository(ctx)
    }

    afterEach {
        ctx.close()
    }

    "default query excludes soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        val results = audioItemRepo.query { }.toList()

        results shouldBe listOf(active)
    }

    "query with includeDeleted returns active and soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        val results = audioItemRepo.query { includeDeleted() }.toSet()

        results.size shouldBe 2
        results.map { it.id }.toSet() shouldBe setOf(1, 2)
    }

    "query with onlyDeleted returns only soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        val results = audioItemRepo.query { onlyDeleted() }.toList()

        results shouldBe listOf(deleted)
    }

    "includeDeleted and onlyDeleted are mutually exclusive" {
        shouldThrow<IllegalStateException> {
            audioItemRepo.query {
                includeDeleted()
                onlyDeleted()
            }
        }
    }

    "Query data class carries includeDeleted and onlyDeleted flags with false defaults" {
        val q = QueryBuilder<SoftDeletableMutableAudioItem>().build()

        q.includeDeleted shouldBe false
        q.onlyDeleted shouldBe false
    }

    "Query with both includeDeleted and onlyDeleted true throws IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            Query<SoftDeletableMutableAudioItem>(null, emptyList(), null, 0, includeDeleted = true, onlyDeleted = true)
        }
    }

    "Via query combined with includeDeleted throws IllegalStateException" {
        val tracks = TrackRepo().apply { add(Track(1, "Ghost", 10.0)) }
        val playlists = PlaylistRepo().apply { add(Playlist(1, "PL", listOf(1), null)) }

        shouldThrow<IllegalStateException> {
            playlists.query {
                where { Playlist::trackIds via tracks anyMatch { Track::price gt 5.0 } }
                includeDeleted()
            }.toList()
        }
    }

    "Via query combined with onlyDeleted throws IllegalStateException" {
        val tracks = TrackRepo().apply { add(Track(1, "Ghost", 10.0)) }
        val playlists = PlaylistRepo().apply { add(Playlist(1, "PL", listOf(1), null)) }

        shouldThrow<IllegalStateException> {
            playlists.query {
                where { Playlist::trackIds via tracks anyMatch { Track::price gt 5.0 } }
                onlyDeleted()
            }.toList()
        }
    }

    "indexed-predicate query with onlyDeleted returns soft-deleted matches" {
        val trackRepo = IndexedSoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Rock")
        val deletedRock = trackRepo.create(2, "Rock")
        val deletedJazz = trackRepo.create(3, "Jazz")
        trackRepo.softDelete(deletedRock)
        trackRepo.softDelete(deletedJazz)
        reactive.advance()

        // Predicate on the indexed 'genre' property combined with onlyDeleted
        val results =
            trackRepo.query {
                where { IndexedSoftDeletableTrack::genre eq "Rock" }
                onlyDeleted()
            }.toList()

        // Only the soft-deleted Rock track must be returned; the active Rock track must not appear
        results.map { it.id } shouldContainExactlyInAnyOrder listOf(2)
    }

    "indexed-predicate query with includeDeleted returns both active and deleted matches" {
        val trackRepo = IndexedSoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Rock")
        val deletedRock = trackRepo.create(2, "Rock")
        trackRepo.create(3, "Jazz")
        trackRepo.softDelete(deletedRock)
        reactive.advance()

        val results =
            trackRepo.query {
                where { IndexedSoftDeletableTrack::genre eq "Rock" }
                includeDeleted()
            }.toList()

        results.map { it.id }.toSet() shouldBe setOf(1, 2)
    }
})
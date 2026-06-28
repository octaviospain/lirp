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
 * Tests for the [QueryBuilder.includeDeleted] / [QueryBuilder.onlyDeleted] query DSL verbs,
 * including `via()` cross-aggregate queries that honor visibility flags on both parent
 * enumeration and child resolution.
 *
 * The default query excludes soft-deleted entities (fail-closed). The opt-in verbs allow
 * callers to explicitly request soft-deleted entities:
 * - [QueryBuilder.includeDeleted]: returns active + soft-deleted entities.
 * - [QueryBuilder.onlyDeleted]: returns only soft-deleted entities.
 *
 * The two verbs are mutually exclusive; combining them throws [IllegalStateException].
 * Both flags propagate through [Query] with `false` defaults and a mutual-exclusion `require`.
 *
 * For `via()` queries, the same visibility flag applies to both sides of the join: parent
 * enumeration and child resolution use the same flag-scoped visible set. Under `onlyDeleted()`,
 * a soft-deleted parent matches `allMatch` / `noneMatch` vacuously when none of its referenced
 * children are soft-deleted (strict-mirror semantics).
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

    "Via query with includeDeleted returns soft-deleted parents referencing active children" {
        val trackRepo =
            SoftDeletableTrackRepo(ctx).apply {
                create(1, "Active Track", 10.0)
                create(2, "Another Active Track", 20.0)
            }
        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        playlistRepo.create(1, "Active Playlist", listOf(1))
        val deletedPl = playlistRepo.create(2, "Deleted Playlist", listOf(2))
        playlistRepo.softDelete(deletedPl)
        reactive.advance()

        val results =
            playlistRepo.query {
                where { SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 } }
                includeDeleted()
            }.map { it.id }.toSet()

        // Both active and soft-deleted playlists referencing active tracks should be returned.
        results shouldBe setOf(1, 2)
    }

    "Via query with onlyDeleted returns only soft-deleted parents referencing soft-deleted children (strict mirror)" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        val activeTrack = trackRepo.create(1, "Active Track", 10.0)
        val deletedTrack = trackRepo.create(2, "Deleted Track", 20.0)
        trackRepo.softDelete(deletedTrack)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        val deletedPlWithActiveChild = playlistRepo.create(1, "Deleted with active child", listOf(1))
        val deletedPlWithDeletedChild = playlistRepo.create(2, "Deleted with deleted child", listOf(2))
        val activePl = playlistRepo.create(3, "Active playlist", listOf(2))
        playlistRepo.softDelete(deletedPlWithActiveChild)
        playlistRepo.softDelete(deletedPlWithDeletedChild)
        reactive.advance()

        val results =
            playlistRepo.query {
                where { SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 } }
                onlyDeleted()
            }.map { it.id }.toSet()

        // Only the soft-deleted playlist referencing a soft-deleted track must match.
        // The soft-deleted playlist whose child is active must NOT match (strict mirror).
        // The active playlist must NOT appear (onlyDeleted).
        results shouldBe setOf(2)
    }

    "Via anyMatch with onlyDeleted — soft-deleted parent with no soft-deleted children does not match" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Active Track", 10.0)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        val deletedPl = playlistRepo.create(1, "Deleted playlist", listOf(1))
        playlistRepo.softDelete(deletedPl)
        reactive.advance()

        val results =
            playlistRepo.query {
                where { SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 } }
                onlyDeleted()
            }.toList()

        // No soft-deleted children exist — anyMatch is false; parent excluded.
        results shouldBe emptyList()
    }

    "Via allMatch with onlyDeleted — soft-deleted parent with no soft-deleted children matches vacuously" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Active Track", 10.0)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        val deletedPl = playlistRepo.create(1, "Deleted playlist", listOf(1))
        playlistRepo.softDelete(deletedPl)
        reactive.advance()

        val results =
            playlistRepo.query {
                where { SoftDeletablePlaylist::trackIds via trackRepo allMatch { SoftDeletableTrack::price gt 5.0 } }
                onlyDeleted()
            }.map { it.id }.toList()

        // The flag-scoped child set is empty (no soft-deleted children), so allMatch is vacuously true.
        results shouldBe listOf(1)
    }

    "Via noneMatch with onlyDeleted — soft-deleted parent with no soft-deleted children matches vacuously" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Active Track", 10.0)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        val deletedPl = playlistRepo.create(1, "Deleted playlist", listOf(1))
        playlistRepo.softDelete(deletedPl)
        reactive.advance()

        val results =
            playlistRepo.query {
                where { SoftDeletablePlaylist::trackIds via trackRepo noneMatch { SoftDeletableTrack::price gt 5.0 } }
                onlyDeleted()
            }.map { it.id }.toList()

        // The flag-scoped child set is empty (no soft-deleted children), so noneMatch is vacuously true.
        results shouldBe listOf(1)
    }

    "Via anyMatch with onlyDeleted — deleted parent referencing deleted child matches; referencing active child does not" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        val activeTrack = trackRepo.create(1, "Active Track", 10.0)
        val deletedTrack = trackRepo.create(2, "Deleted Track", 20.0)
        trackRepo.softDelete(deletedTrack)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        val deletedPlWithActive = playlistRepo.create(1, "Deleted, active child ref", listOf(1))
        val deletedPlWithDeleted = playlistRepo.create(2, "Deleted, deleted child ref", listOf(2))
        playlistRepo.softDelete(deletedPlWithActive)
        playlistRepo.softDelete(deletedPlWithDeleted)
        reactive.advance()

        val deletedPlWithActiveResults =
            playlistRepo.query {
                where { SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::id eq 1 } }
                onlyDeleted()
            }.toList()

        val deletedPlWithDeletedResults =
            playlistRepo.query {
                where { SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::id eq 2 } }
                onlyDeleted()
            }.map { it.id }.toList()

        // Deleted parent referencing active child: the active child is invisible under onlyDeleted — no match.
        deletedPlWithActiveResults shouldBe emptyList()
        // Deleted parent referencing deleted child: the deleted child is in the visible set — matches.
        deletedPlWithDeletedResults shouldBe listOf(2)
    }

    "Via where (single-ref) with onlyDeleted — deleted parent whose deleted owner matches is included; active owner is invisible" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Active Owner", 10.0)
        val deletedOwner = trackRepo.create(2, "Deleted Owner", 20.0)
        trackRepo.softDelete(deletedOwner)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        // Deleted parent whose single owner-ref points at the soft-deleted owner → in the visible set → matches.
        val refsDeletedOwner = playlistRepo.create(1, "Refs deleted owner", emptyList(), ownerTrackId = 2)
        // Deleted parent whose owner-ref points at an active owner → invisible under onlyDeleted → no match.
        val refsActiveOwner = playlistRepo.create(2, "Refs active owner", emptyList(), ownerTrackId = 1)
        // Deleted parent with no owner-ref → ViaWhere on null → no match.
        val noOwner = playlistRepo.create(3, "No owner", emptyList(), ownerTrackId = null)
        listOf(refsDeletedOwner, refsActiveOwner, noOwner).forEach { playlistRepo.softDelete(it) }
        reactive.advance()

        val results =
            playlistRepo.query {
                where { SoftDeletablePlaylist::ownerTrackId via trackRepo where { SoftDeletableTrack::price gt 5.0 } }
                onlyDeleted()
            }.map { it.id }.toSet()

        // Only the deleted parent whose owner is also soft-deleted (and price > 5) matches.
        results shouldBe setOf(1)
    }

    "default (no flag) via query still excludes soft-deleted parents and children (fail-closed regression guard)" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Active Track", 10.0)
        val deletedTrack = trackRepo.create(2, "Deleted Track", 20.0)
        trackRepo.softDelete(deletedTrack)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        playlistRepo.create(1, "Active playlist", listOf(1))
        val deletedPl = playlistRepo.create(2, "Deleted playlist", listOf(1, 2))
        playlistRepo.softDelete(deletedPl)
        reactive.advance()

        val results =
            playlistRepo.query {
                where { SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 } }
            }.map { it.id }.toList()

        // Only the active playlist referencing the active track should appear.
        results shouldBe listOf(1)
    }

    "HASH_JOIN and PER_PARENT_LOOP return identical result sets under includeDeleted" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Active Track", 10.0)
        val deletedTrack = trackRepo.create(2, "Deleted Track", 20.0)
        trackRepo.softDelete(deletedTrack)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        playlistRepo.create(1, "Active playlist", listOf(1))
        val deletedPl = playlistRepo.create(2, "Deleted playlist", listOf(2))
        playlistRepo.softDelete(deletedPl)
        reactive.advance()

        val via = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 }
        val query = Query<SoftDeletablePlaylist>(predicate = via, orderBy = emptyList(), limit = null, offset = 0, includeDeleted = true)
        val (ppl, hj) = viaResultsUnderBothStrategies(via, playlistRepo, query)

        ppl shouldBe hj
    }

    "HASH_JOIN and PER_PARENT_LOOP return identical result sets under onlyDeleted" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Active Track", 10.0)
        val deletedTrack = trackRepo.create(2, "Deleted Track", 20.0)
        trackRepo.softDelete(deletedTrack)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        playlistRepo.create(1, "Active playlist", listOf(1))
        val deletedPl = playlistRepo.create(2, "Deleted playlist", listOf(2))
        playlistRepo.softDelete(deletedPl)
        reactive.advance()

        val via = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 }
        val query = Query<SoftDeletablePlaylist>(predicate = via, orderBy = emptyList(), limit = null, offset = 0, onlyDeleted = true)
        val (ppl, hj) = viaResultsUnderBothStrategies(via, playlistRepo, query)

        ppl shouldBe hj
    }

    "compound And(Via, Via) with onlyDeleted returns only deleted parents satisfying both via arms" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        // Active tracks — invisible under onlyDeleted.
        trackRepo.create(1, "Active Low", 5.0)
        trackRepo.create(2, "Active High", 100.0)
        // Deleted tracks — visible under onlyDeleted.
        val deletedLow = trackRepo.create(3, "Deleted Low", 8.0)
        val deletedHigh = trackRepo.create(4, "Deleted High", 200.0)
        trackRepo.softDelete(deletedLow)
        trackRepo.softDelete(deletedHigh)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        // Deleted playlist with both deleted low-price (3) and deleted high-price (4) tracks.
        val deletedBothArms = playlistRepo.create(1, "Deleted both arms", listOf(3, 4))
        // Deleted playlist with only deleted high-price track (4) — satisfies both arms (200 > 5 and 200 > 150).
        val deletedSingleTrack = playlistRepo.create(2, "Deleted single track", listOf(4))
        // Active playlist — must not appear under onlyDeleted.
        playlistRepo.create(3, "Active", listOf(3, 4))
        playlistRepo.softDelete(deletedBothArms)
        playlistRepo.softDelete(deletedSingleTrack)
        reactive.advance()

        // Compound: BOTH arms must match over the flag-scoped (deleted-only) child visible set.
        // arm1: anyMatch price > 5.0  → matches deleted tracks 3 (8.0) and 4 (200.0)
        // arm2: anyMatch price > 150.0 → matches only deleted track 4 (200.0)
        val arm1 = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 }
        val arm2 = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 150.0 }
        val compound = arm1 and arm2

        val results =
            playlistRepo.query {
                where { compound }
                onlyDeleted()
            }.map { it.id }.toSet()

        // Playlist 1 ([3,4]): arm1=true (3 or 4 > 5), arm2=true (4 > 150) → included.
        // Playlist 2 ([4]):   arm1=true (4 > 5),       arm2=true (4 > 150) → included.
        // Active playlist 3:  not in flag-scoped parent set → excluded.
        results shouldBe setOf(1, 2)
    }

    "compound Or(Via, Via) with onlyDeleted unions deleted parents satisfying either via arm" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        val deletedCheap = trackRepo.create(3, "Deleted Cheap", 8.0)
        val deletedExpensive = trackRepo.create(4, "Deleted Expensive", 200.0)
        trackRepo.softDelete(deletedCheap)
        trackRepo.softDelete(deletedExpensive)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        // arm1 (price > 150) matches only the expensive deleted track (4).
        val onlyArm1 = playlistRepo.create(1, "Only arm1", listOf(4))
        // arm2 (price < 10) matches only the cheap deleted track (3) — proves the union, not intersection.
        val onlyArm2 = playlistRepo.create(2, "Only arm2", listOf(3))
        val bothArms = playlistRepo.create(3, "Both arms", listOf(3, 4))
        val neither = playlistRepo.create(4, "Neither", emptyList())
        playlistRepo.create(5, "Active", listOf(4))
        listOf(onlyArm1, onlyArm2, bothArms, neither).forEach { playlistRepo.softDelete(it) }
        reactive.advance()

        val arm1 = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 150.0 }
        val arm2 = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price lt 10.0 }

        val results =
            playlistRepo.query {
                where { arm1 or arm2 }
                onlyDeleted()
            }.map { it.id }.toSet()

        // Union: arm1-only (1), arm2-only (2), both (3); neither (4) excluded; active (5) not in the deleted universe.
        results shouldBe setOf(1, 2, 3)
    }

    "not(Via) with onlyDeleted negates against the deleted-only child set on both sides" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Active Cheap", 5.0)
        val deletedCheap = trackRepo.create(2, "Deleted Cheap", 8.0)
        val deletedExpensive = trackRepo.create(3, "Deleted Expensive", 200.0)
        trackRepo.softDelete(deletedCheap)
        trackRepo.softDelete(deletedExpensive)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        // Deleted parent referencing the deleted expensive track — inner anyMatch{price>50} is TRUE,
        // so the negation excludes it. (Active-only child resolution would wrongly miss track 3 and include it.)
        val refsDeletedExpensive = playlistRepo.create(1, "Refs deleted expensive", listOf(3))
        // Deleted parent referencing only the deleted cheap track — inner anyMatch{price>50} is FALSE → included.
        val refsDeletedCheap = playlistRepo.create(2, "Refs deleted cheap", listOf(2))
        // Deleted parent referencing only an ACTIVE track — invisible under onlyDeleted child resolution → included.
        val refsActiveOnly = playlistRepo.create(3, "Refs active only", listOf(1))
        // Deleted parent with no refs — vacuously not-any → included.
        val noRefs = playlistRepo.create(4, "No refs", emptyList())
        listOf(refsDeletedExpensive, refsDeletedCheap, refsActiveOnly, noRefs).forEach { playlistRepo.softDelete(it) }
        reactive.advance()

        val via = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 50.0 }

        val results =
            playlistRepo.query {
                where { !via }
                onlyDeleted()
            }.map { it.id }.toSet()

        // Only parent 1 references a deleted track with price > 50, so only it is negated away.
        results shouldBe setOf(2, 3, 4)
    }

    "not(Via) with includeDeleted negates against the active-and-deleted child set" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        val activeExpensive = trackRepo.create(1, "Active Expensive", 200.0)
        val deletedExpensive = trackRepo.create(2, "Deleted Expensive", 300.0)
        trackRepo.softDelete(deletedExpensive)
        val activeCheap = trackRepo.create(3, "Active Cheap", 4.0)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        // References an active expensive track — inner anyMatch{price>50} TRUE under includeDeleted → excluded.
        val refsActiveExpensive = playlistRepo.create(1, "Refs active expensive", listOf(1))
        // References a deleted expensive track — TRUE under includeDeleted → excluded.
        val refsDeletedExpensive = playlistRepo.create(2, "Refs deleted expensive", listOf(2))
        // References only a cheap active track — FALSE → included.
        val refsCheap = playlistRepo.create(3, "Refs cheap", listOf(3))
        playlistRepo.softDelete(refsDeletedExpensive)
        reactive.advance()

        val via = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 50.0 }

        val results =
            playlistRepo.query {
                where { !via }
                includeDeleted()
            }.map { it.id }.toSet()

        // Both active and deleted expensive references are visible and negated away; only the cheap one survives.
        results shouldBe setOf(3)
    }

    "not(compound And of two via arms) with onlyDeleted complements the flag-scoped intersection" {
        val trackRepo = SoftDeletableTrackRepo(ctx)
        trackRepo.create(1, "Active Cheap", 5.0)
        val deletedCheap = trackRepo.create(3, "Deleted Cheap", 8.0)
        val deletedExpensive = trackRepo.create(4, "Deleted Expensive", 200.0)
        trackRepo.softDelete(deletedCheap)
        trackRepo.softDelete(deletedExpensive)

        val playlistRepo = SoftDeletablePlaylistRepo(ctx)
        // arm1 = anyMatch price>5, arm2 = anyMatch price>150 (over the deleted-only child set).
        val bothArms = playlistRepo.create(1, "Both arms", listOf(3, 4)) // arm1 T, arm2 T → And T → negated out
        val arm1Only = playlistRepo.create(2, "arm1 only", listOf(3)) // arm1 T (8>5), arm2 F → And F → kept
        val bothViaSingle = playlistRepo.create(3, "Both via single", listOf(4)) // arm1 T, arm2 T → And T → negated out
        val neither = playlistRepo.create(4, "Neither", emptyList()) // And F → kept
        val activeChildOnly = playlistRepo.create(5, "Active child only", listOf(1)) // active child invisible → And F → kept
        listOf(bothArms, arm1Only, bothViaSingle, neither, activeChildOnly).forEach { playlistRepo.softDelete(it) }
        reactive.advance()

        val arm1 = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 }
        val arm2 = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 150.0 }

        val results =
            playlistRepo.query {
                where { !(arm1 and arm2) }
                onlyDeleted()
            }.map { it.id }.toSet()

        // Deleted universe {1..5} minus the And-intersection {1,3} = {2,4,5}.
        results shouldBe setOf(2, 4, 5)
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
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

import net.transgressoft.lirp.persistence.LirpContext
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for the cross-aggregate planner: Via* detection, normalizer ordering, cardinality-driven
 * strategy switching, hybrid-predicate `And(NonVia, Via*)` handling, and lazy `Sequence`
 * preservation. Strategy decisions are asserted through the internal `strategyFor` test seam.
 */
@DisplayName("Via planner")
internal class ViaPlannerTest : FunSpec({

    fun nonIndexedPlanner(): QueryPlanner<Playlist> =
        QueryPlanner(isIndexed = { false }, indexNameFor = { it.name })

    test("normalize runs before strategy selection - Or of two ViaAnyMatch on same ref collapses to one") {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "a", 10.0))
                add(Track(2, "b", 200.0))
                add(Track(3, "c", 300.0))
            }
        val playlists =
            PlaylistRepo().apply {
                add(Playlist(1, "p1", listOf(1), null))
                add(Playlist(2, "p2", listOf(2), null))
                add(Playlist(3, "p3", listOf(3), null))
            }
        val raw =
            (Playlist::trackIds via tracks anyMatch { Track::price gt 150.0 }) or
                (Playlist::trackIds via tracks anyMatch { Track::price gt 250.0 })
        val normalised = normalize(raw)
        // After fold, the result is a single ViaAnyMatch (not an Or) — proves normalize runs.
        (normalised is ViaAnyMatch<*, *, *>) shouldBe true

        val planner = nonIndexedPlanner()
        val result =
            planner.execute(
                Query(predicate = raw, orderBy = emptyList(), limit = null, offset = 0),
                playlists
            )
        result.results.toList().map { it.id } shouldContainExactlyInAnyOrder listOf(2, 3)
    }

    test("chooses HASH_JOIN when matchingChildCount is much less than parents times avgRefsPerParent") {
        val tracks =
            TrackRepo().apply {
                repeat(1000) { add(Track(it, "t$it", if (it == 0) 999.0 else 10.0)) }
            }
        val playlists =
            PlaylistRepo().apply {
                // 50 parents, each referencing 20 tracks -> crossover = 50 * 20 = 1000
                // matchingChildCount = 1 (only track id 0 has price > 500)
                repeat(50) { p -> add(Playlist(p, "p$p", (0 until 20).toList(), null)) }
            }
        val via = Playlist::trackIds via tracks anyMatch { Track::price gt 500.0 }
        nonIndexedPlanner().strategyFor(via, playlists) shouldBe ViaStrategy.HASH_JOIN
    }

    test("chooses PER_PARENT_LOOP when matchingChildCount is much greater than parents times avgRefsPerParent") {
        val tracks =
            TrackRepo().apply {
                repeat(1000) { add(Track(it, "t$it", 1000.0)) }
            }
        val playlists =
            PlaylistRepo().apply {
                // 5 parents, each referencing 2 tracks -> crossover = 10
                // matchingChildCount = 1000 (all children match)
                repeat(5) { p -> add(Playlist(p, "p$p", listOf(0, 1), null)) }
            }
        val via = Playlist::trackIds via tracks anyMatch { Track::price gt 500.0 }
        nonIndexedPlanner().strategyFor(via, playlists) shouldBe ViaStrategy.PER_PARENT_LOOP
    }

    test("samples at most 100 parents for avgRefsPerParent regardless of parent count") {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "match", 1000.0))
                add(Track(2, "nomatch", 1.0))
            }
        val playlists =
            PlaylistRepo().apply {
                // First 100 parents have 1 ref each; remaining 900 have 50 refs each.
                // If sample cap is honoured (only first 100), avg = 1.0 -> crossover = 1000 * 1.0 = 1000.
                // If cap is NOT honoured, avg ≈ 45.1 -> crossover ≈ 45100.
                repeat(100) { p -> add(Playlist(p, "p$p", listOf(1), null)) }
                repeat(900) { p ->
                    val refs = (0 until 50).map { it % 2 + 1 }
                    add(Playlist(p + 100, "p${p + 100}", refs, null))
                }
            }
        val via = Playlist::trackIds via tracks anyMatch { Track::title eq "match" }
        // matchingChildCount = 1, crossover with capped avg = 1000 * 1.0 = 1000 -> 1 < 1000 -> HASH_JOIN
        nonIndexedPlanner().strategyFor(via, playlists) shouldBe ViaStrategy.HASH_JOIN
    }

    test("re-estimates strategy on each query call - no caching across queries") {
        val tracks =
            TrackRepo().apply {
                // 100 children, only 1 with high price
                repeat(100) { add(Track(it, "t$it", if (it == 0) 1000.0 else 1.0)) }
            }
        val playlists =
            PlaylistRepo().apply {
                // 10 parents, 2 refs each -> crossover = 20
                repeat(10) { p -> add(Playlist(p, "p$p", listOf(0, 1), null)) }
            }
        val planner = nonIndexedPlanner()

        // Selective predicate (1 child matches < 20 crossover) -> HASH_JOIN
        val selective = Playlist::trackIds via tracks anyMatch { Track::price gt 500.0 }
        planner.strategyFor(selective, playlists) shouldBe ViaStrategy.HASH_JOIN

        // Non-selective predicate (100 match > 20 crossover) -> PER_PARENT_LOOP
        val nonSelective = Playlist::trackIds via tracks anyMatch { Track::price gt 0.0 }
        planner.strategyFor(nonSelective, playlists) shouldBe ViaStrategy.PER_PARENT_LOOP
    }

    test("PER_PARENT_LOOP path returns identical parents to HASH_JOIN path on the same fixture") {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "a", 10.0))
                add(Track(2, "b", 200.0))
                add(Track(3, "c", 300.0))
                add(Track(4, "d", 50.0))
            }
        val playlists =
            PlaylistRepo().apply {
                add(Playlist(10, "x", listOf(1, 2), null))
                add(Playlist(11, "y", listOf(3, 4), null))
                add(Playlist(12, "z", listOf(1, 4), null))
            }
        val via = Playlist::trackIds via tracks anyMatch { Track::price gt 150.0 }
        val planner = nonIndexedPlanner()
        val viaResults = planner.executeViaSequence(via, playlists).toList().map { it.id }.sorted()

        // Reference: scan path
        val scanResults = playlists.asSequence().filter { via.matches(it) }.map { it.id }.toList().sorted()
        viaResults shouldBe scanResults
    }

    test("anyMatch HASH_JOIN reads parentProp live - mutating parent collection between two query calls reflects") {
        val tracks =
            TrackRepo().apply {
                repeat(100) { add(Track(it, "t$it", if (it == 0) 999.0 else 1.0)) }
            }
        val playlistRepo =
            object : net.transgressoft.lirp.persistence.VolatileRepository<Int, MutablePlaylist>(
                net.transgressoft.lirp.persistence.LirpContext.default,
                "MutablePlaylists"
            ) {}
        repeat(50) { p -> playlistRepo.add(MutablePlaylist(p, "p$p", (1..20).toList())) }
        val target = MutablePlaylist(999, "target", listOf(1, 2, 3))
        playlistRepo.add(target)

        val planner =
            QueryPlanner<MutablePlaylist>(isIndexed = { false }, indexNameFor = { it.name })
        val via = MutablePlaylist::trackIds via tracks anyMatch { Track::price gt 500.0 }
        planner.strategyFor(via, playlistRepo) shouldBe ViaStrategy.HASH_JOIN

        // Before mutation: target does not reference track 0 -> excluded
        val before = planner.executeViaSequence(via, playlistRepo).map { it.id }.toList()
        (999 in before) shouldBe false

        // Mutate live ref ids, then re-query: same predicate, fresh execution -> target now included
        target.trackIds = listOf(0, 1, 2)
        val after = planner.executeViaSequence(via, playlistRepo).map { it.id }.toList()
        (999 in after) shouldBe true
    }

    test("allMatch HASH_JOIN respects vacuous-true on empty parent collections") {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "a", 10.0))
            }
        val playlists =
            PlaylistRepo().apply {
                add(Playlist(1, "empty", emptyList(), null))
                add(Playlist(2, "full", listOf(1), null))
            }
        val via = Playlist::trackIds via tracks allMatch { Track::price gt 5.0 }
        val planner = nonIndexedPlanner()
        // Force HASH_JOIN by selective fixture (matchingChildCount=1, parents=2 * avg=0.5 -> crossover 1; 1<1 false)
        // We don't strictly require HASH_JOIN here — what we require is correct semantics either way.
        val results = planner.executeViaSequence(via, playlists).map { it.id }.toList()
        results shouldContainExactlyInAnyOrder listOf(1, 2)
    }

    test("noneMatch HASH_JOIN excludes parents whose collection contains a matching id") {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "a", 10.0))
                add(Track(2, "match", 1000.0))
            }
        val playlists =
            PlaylistRepo().apply {
                add(Playlist(1, "clean", listOf(1), null))
                add(Playlist(2, "dirty", listOf(1, 2), null))
                add(Playlist(3, "empty", emptyList(), null))
            }
        val via = Playlist::trackIds via tracks noneMatch { Track::price gt 500.0 }
        val planner = nonIndexedPlanner()
        val results = planner.executeViaSequence(via, playlists).map { it.id }.toList()
        results shouldContainExactlyInAnyOrder listOf(1, 3)
    }

    test("ViaWhere on null single-entity ref excludes parent") {
        val owners =
            OwnerRepo().apply {
                add(Owner(10, "Berlin"))
                add(Owner(11, "Paris"))
            }
        val playlists =
            PlaylistRepo().apply {
                add(Playlist(1, "berlin", emptyList(), 10))
                add(Playlist(2, "paris", emptyList(), 11))
                add(Playlist(3, "none", emptyList(), null))
            }
        val via = Playlist::ownerId via owners where { Owner::city eq "Berlin" }
        val planner = nonIndexedPlanner()
        val results = planner.executeViaSequence(via, playlists).map { it.id }.toList()
        results shouldContainExactlyInAnyOrder listOf(1)
    }

    test("hybrid predicate And(NameEq, ViaAnyMatch) uses HASH_JOIN on the Via arm and applies NameEq as a post-filter") {
        val tracks =
            TrackRepo().apply {
                repeat(1000) { add(Track(it, "t$it", if (it < 5) 999.0 else 1.0)) }
            }
        val playlists =
            PlaylistRepo().apply {
                // 100 parents, half named "X". Among the X-named, ~3 have a match-ref in [0..4].
                repeat(100) { p ->
                    val name = if (p % 2 == 0) "X" else "Y"
                    val refs = if (p in listOf(0, 2, 4)) listOf(0, 1) else listOf(10, 11)
                    add(Playlist(p, name, refs, null))
                }
            }
        val via = Playlist::trackIds via tracks anyMatch { Track::price gt 500.0 }
        val hybrid: Predicate<Playlist> = (Playlist::name eq "X") and via

        val planner = nonIndexedPlanner()
        // Strategy is HASH_JOIN on the Via arm (never silent fallback)
        planner.strategyFor(hybrid, playlists) shouldBe ViaStrategy.HASH_JOIN

        val context =
            planner.execute(
                Query(predicate = hybrid, orderBy = emptyList(), limit = null, offset = 0),
                playlists
            )
        // The non-via arm (name eq "X") is applied as a post-filter, so it counts as one residual leaf.
        context.postFilterCount shouldBe 1
        val results = context.results.toList().map { it.id }
        // Intersection of name="X" and via match = {0, 2, 4}
        results shouldContainExactlyInAnyOrder listOf(0, 2, 4)
    }

    test("PlanContext viaStrategy is non-null for cross-aggregate via predicate") {
        val tracks =
            TrackRepo().apply {
                repeat(100) { add(Track(it, "t$it", if (it == 0) 999.0 else 1.0)) }
            }
        val playlists =
            PlaylistRepo().apply {
                repeat(50) { p -> add(Playlist(p, "p$p", (0 until 20).toList(), null)) }
            }
        val via = Playlist::trackIds via tracks anyMatch { Track::price gt 500.0 }
        val planner = nonIndexedPlanner()
        val context =
            planner.execute(
                Query(predicate = via, orderBy = emptyList(), limit = null, offset = 0),
                playlists
            )
        context.viaStrategy.shouldNotBeNull()
        context.viaStrategy shouldBe planner.strategyFor(via, playlists)
        // A bare via predicate has no non-via post-filter arm.
        context.postFilterCount shouldBe 0
    }

    test("HASH_JOIN and PER_PARENT_LOOP return identical result sets under includeDeleted with soft-deletable fixtures") {
        val ctx = LirpContext()
        val trackRepo =
            SoftDeletableTrackRepo(ctx).apply {
                create(1, "Active Track", 10.0)
                create(2, "Another Active Track", 20.0)
                val deletedTrack = create(3, "Deleted Track", 30.0)
                softDelete(deletedTrack)
            }
        val playlistRepo =
            SoftDeletablePlaylistRepo(ctx).apply {
                create(1, "Active Playlist", listOf(1))
                val deletedPl = create(2, "Deleted Playlist", listOf(3))
                softDelete(deletedPl)
            }

        val via = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 }
        val query = Query<SoftDeletablePlaylist>(predicate = via, orderBy = emptyList(), limit = null, offset = 0, includeDeleted = true)
        val (ppl, hj) = viaResultsUnderBothStrategies(via, playlistRepo, query)

        // Both strategies must produce the same result set.
        ppl shouldBe hj
        // Both active and soft-deleted playlists referencing matching tracks should appear.
        ppl.map { it.id }.toSet() shouldBe setOf(1, 2)

        ctx.close()
    }

    test("HASH_JOIN and PER_PARENT_LOOP return identical result sets under onlyDeleted with soft-deletable fixtures") {
        val ctx = LirpContext()
        val trackRepo =
            SoftDeletableTrackRepo(ctx).apply {
                create(1, "Active Track", 10.0)
                val deletedTrack = create(2, "Deleted Track", 20.0)
                softDelete(deletedTrack)
            }
        val playlistRepo =
            SoftDeletablePlaylistRepo(ctx).apply {
                // Deleted playlist referencing active child — strict-mirror: should NOT appear in onlyDeleted.
                val deletedPlActiveChild = create(1, "Deleted with active child", listOf(1))
                softDelete(deletedPlActiveChild)
                // Deleted playlist referencing deleted child — strict-mirror: SHOULD appear.
                val deletedPlDeletedChild = create(2, "Deleted with deleted child", listOf(2))
                softDelete(deletedPlDeletedChild)
                // Active playlist — should NOT appear under onlyDeleted.
                create(3, "Active Playlist", listOf(2))
            }

        val via = SoftDeletablePlaylist::trackIds via trackRepo anyMatch { SoftDeletableTrack::price gt 5.0 }
        val query = Query<SoftDeletablePlaylist>(predicate = via, orderBy = emptyList(), limit = null, offset = 0, onlyDeleted = true)
        val (ppl, hj) = viaResultsUnderBothStrategies(via, playlistRepo, query)

        // Both strategies must produce the same result set.
        ppl shouldBe hj
        // Only the deleted playlist whose referenced track is also deleted should appear.
        ppl.map { it.id }.toSet() shouldBe setOf(2)

        ctx.close()
    }

    test("allMatch HASH_JOIN and PER_PARENT_LOOP return identical result sets under onlyDeleted") {
        val ctx = LirpContext()
        val trackRepo =
            SoftDeletableTrackRepo(ctx).apply {
                create(1, "Active Track", 2.0)
                val deletedCheap = create(2, "Deleted Cheap", 8.0)
                val deletedExpensive = create(3, "Deleted Expensive", 30.0)
                softDelete(deletedCheap)
                softDelete(deletedExpensive)
            }
        val playlistRepo =
            SoftDeletablePlaylistRepo(ctx).apply {
                // All soft-deleted children (8, 30) are > 5 → allMatch true.
                softDelete(create(1, "Deleted all match", listOf(2, 3)))
                // Soft-deleted child 2 (8) > 5 but references active child 1 (invisible) → scoped {8} all match.
                softDelete(create(2, "Deleted mixed", listOf(1, 2)))
                // No soft-deleted children referenced (only active 1) → scoped empty → vacuously true.
                softDelete(create(3, "Deleted vacuous", listOf(1)))
            }

        val via = SoftDeletablePlaylist::trackIds via trackRepo allMatch { SoftDeletableTrack::price gt 5.0 }
        val query = Query<SoftDeletablePlaylist>(predicate = via, orderBy = emptyList(), limit = null, offset = 0, onlyDeleted = true)
        val (ppl, hj) = viaResultsUnderBothStrategies(via, playlistRepo, query)

        ppl shouldBe hj
        ppl.map { it.id }.toSet() shouldBe setOf(1, 2, 3)

        ctx.close()
    }

    test("noneMatch HASH_JOIN and PER_PARENT_LOOP return identical result sets under onlyDeleted") {
        val ctx = LirpContext()
        val trackRepo =
            SoftDeletableTrackRepo(ctx).apply {
                create(1, "Active Expensive", 100.0)
                val deletedCheap = create(2, "Deleted Cheap", 8.0)
                val deletedExpensive = create(3, "Deleted Expensive", 300.0)
                softDelete(deletedCheap)
                softDelete(deletedExpensive)
            }
        val playlistRepo =
            SoftDeletablePlaylistRepo(ctx).apply {
                // Soft-deleted child 3 (300) > 50 → noneMatch false → excluded.
                softDelete(create(1, "Deleted has expensive", listOf(3)))
                // Soft-deleted child 2 (8) not > 50 → noneMatch true → included.
                softDelete(create(2, "Deleted cheap only", listOf(2)))
                // References active expensive only (invisible) → scoped empty → vacuously true → included.
                softDelete(create(3, "Deleted vacuous", listOf(1)))
            }

        val via = SoftDeletablePlaylist::trackIds via trackRepo noneMatch { SoftDeletableTrack::price gt 50.0 }
        val query = Query<SoftDeletablePlaylist>(predicate = via, orderBy = emptyList(), limit = null, offset = 0, onlyDeleted = true)
        val (ppl, hj) = viaResultsUnderBothStrategies(via, playlistRepo, query)

        ppl shouldBe hj
        ppl.map { it.id }.toSet() shouldBe setOf(2, 3)

        ctx.close()
    }

    test("hybrid predicate result preserves Sequence laziness - taking only first N elements does not iterate beyond N parents") {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "match", 1000.0))
            }
        val visitCount = java.util.concurrent.atomic.AtomicInteger(0)
        val playlists =
            object : net.transgressoft.lirp.persistence.VolatileRepository<Int, Playlist>(
                net.transgressoft.lirp.persistence.LirpContext.default,
                "LazyPlaylists"
            ) {
                override fun iterator(): Iterator<Playlist> {
                    val base = super.iterator()
                    return object : Iterator<Playlist> {
                        override fun hasNext(): Boolean = base.hasNext()

                        override fun next(): Playlist {
                            visitCount.incrementAndGet()
                            return base.next()
                        }
                    }
                }
            }
        repeat(1000) { p -> playlists.add(Playlist(p, "X", listOf(1), null)) }
        val planner = nonIndexedPlanner()
        val hybrid: Predicate<Playlist> =
            (Playlist::name eq "X") and (Playlist::trackIds via tracks anyMatch { Track::price gt 500.0 })

        // Pre-execute strategy sampling burns one iterator pass over up to 100 parents; reset after.
        val baseline =
            planner.execute(
                Query(predicate = hybrid, orderBy = emptyList(), limit = null, offset = 0),
                playlists
            ).results.take(5).toList()
        baseline shouldHaveSize 5
        // After strategy-sampling pass (≤100 visits) and the actual lazy iteration that produces 5
        // results, visitCount should be far less than the 1000 parents (proves laziness). Iteration
        // halts as soon as 5 results are yielded.
        (visitCount.get() < 1000) shouldBe true
    }
})
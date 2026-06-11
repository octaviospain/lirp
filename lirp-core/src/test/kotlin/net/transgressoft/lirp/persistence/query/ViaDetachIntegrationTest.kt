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

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.BubbleUpAudioPlaylist
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Integration coverage of the live-read invariant under `@Aggregate(onDelete = DETACH)` lifecycle.
 * Verifies that FK-nulling semantics propagate through the `Via*` AST nodes and the planner
 * without any caching or stale snapshot.
 *
 * Cases 3 and 4 exercise the acceptance: truly mid-iteration mutation of a parent's
 * `referenceIds` between two iterator yields of the planner's lazy sequence is reflected
 * in the result. They use [QueryPlanner.executeViaSequence] — the internal seam — rather than two
 * separate `Registry.query` calls. The two-call fallback is REJECTED here because it does not
 * observe the predicate at the in-loop read boundary; only an in-flight iterator does.
 *
 * **Simulating DETACH at the in-memory layer.** In-memory cascade for collection refs is a
 * no-op (Phase 53's SQL DETACH nulls FKs at the persistence layer, observed on reload).
 * To exercise the same observable post-DETACH state inside a single JVM, the tests use
 * mutable parent entities and mutate the FK-bearing property directly — clearing the
 * matching id from the collection (cases 1, 3, 4) or setting the nullable single ref to
 * null (case 2). This produces the exact predicate input the planner sees after a SQL
 * `DETACH` round-trip and lets the in-memory test prove the live-read contract end-to-end.
 */
@DisplayName("Via × DETACH integration")
internal class ViaDetachIntegrationTest : FunSpec({

    fun nonIndexedPlannerForPlaylist(): QueryPlanner<MutablePlaylist> =
        QueryPlanner(isIndexed = { false }, indexNameFor = { it.name })

    fun nonIndexedPlannerForOwnerParent(): QueryPlanner<MutableSingleRefParent> =
        QueryPlanner(isIndexed = { false }, indexNameFor = { it.name })

    test("DETACH-deleted child no longer appears in via tracks anyMatch result") {
        val ctx = LirpContext()
        val tracks =
            TrackRepoIso(ctx).apply {
                add(Track(1, "rare", 999.0))
                add(Track(2, "cheap-a", 1.0))
                add(Track(3, "cheap-b", 1.0))
            }
        val playlists =
            MutablePlaylistRepo(ctx).apply {
                add(MutablePlaylist(100, "p", mutableListOf(1, 2, 3)))
            }
        val via = MutablePlaylist::trackIds via tracks anyMatch { Track::price gt 500.0 }

        val planner = nonIndexedPlannerForPlaylist()

        // Pre-DETACH: parent references the high-priced track → result includes the parent.
        val before = planner.executeViaSequence(via, playlists).toList().map { it.id }
        before shouldContain 100

        // Simulate DETACH: delete child Track(1) and reconcile the parent's collection
        // (mirrors what Phase 53 SQL DETACH produces after a junction-table reconciliation).
        tracks.remove(tracks.findById(1).orElseThrow())
        val parent = playlists.findById(100).orElseThrow()
        (parent.trackIds as MutableList<Int>).remove(1)

        // Post-DETACH: live read of trackIds excludes the detached id → parent dropped.
        val after = planner.executeViaSequence(via, playlists).toList().map { it.id }
        after shouldNotContain 100

        ctx.close()
    }

    test("DETACH-nulled single-entity ref drops parent from via where result") {
        val ctx = LirpContext()
        val owners =
            OwnerRepoIso(ctx).apply {
                add(Owner(10, "Berlin"))
                add(Owner(11, "Paris"))
            }
        val parents =
            MutableSingleRefParentRepo(ctx).apply {
                add(MutableSingleRefParent(1, "alice-order", 10))
            }
        val via = MutableSingleRefParent::ownerId via owners where { Owner::city eq "Berlin" }

        val planner = nonIndexedPlannerForOwnerParent()

        val before = planner.executeViaSequence(via, parents).toList().map { it.id }
        before shouldContain 1

        // Simulate DETACH single-ref: deleting the referenced owner sets the FK to null
        // (Phase 53 ON DELETE SET NULL on a nullable column).
        owners.remove(owners.findById(10).orElseThrow())
        val orphan = parents.findById(1).orElseThrow()
        orphan.ownerId = null

        val after = planner.executeViaSequence(via, parents).toList().map { it.id }
        after shouldNotContain 1

        ctx.close()
    }

    /**
     * acceptance: drives the planner's lazy [Sequence] via its iterator, mutates a
     * not-yet-yielded parent's `trackIds` between two `next()` calls, and asserts the
     * mutated parent is absent from the drained tail. Forces PER_PARENT_LOOP via fixture
     * sizing (many matching children, few refs per parent) so the per-parent `matches`
     * call reads `parentProp.get(p)` live at yield time.
     *
     * Uses [QueryPlanner.executeViaSequence] directly; two separate query calls are
     * REJECTED for this acceptance because they cannot observe the in-loop live read.
     */
    test("Mid-iteration mutation of parent referenceIds is reflected in result (live read invariant)") {
        val ctx = LirpContext()
        val tracks =
            TrackRepoIso(ctx).apply {
                // 100 matching children to force PER_PARENT_LOOP against 5 parents × 1 ref = 5 crossover.
                repeat(100) { add(Track(it + 1, "match", 100.0)) }
            }
        val playlists =
            MutablePlaylistRepo(ctx).apply {
                // 5 parents, each referencing one matching track id. Use ids 0..4 to control iteration.
                repeat(5) { p -> add(MutablePlaylist(p, "p$p", mutableListOf(p + 1))) }
            }
        val via = MutablePlaylist::trackIds via tracks anyMatch { Track::price gt 50.0 }

        val planner = nonIndexedPlannerForPlaylist()
        planner.strategyFor(via, playlists) shouldBe ViaStrategy.PER_PARENT_LOOP

        val iter = planner.executeViaSequence(via, playlists).iterator()

        // Advance one element so the iterator is mid-flight, capturing which parent was yielded.
        // `VolatileRepository` is backed by `ConcurrentHashMap`, whose iteration order is not
        // contractual, so a fixed-id mutation (e.g. always parent 3) risks being trivially satisfied
        // when 3 happens to be the first element yielded — the mutation never gets a chance to run
        // against the live-read path. Clearing every *not-yet-yielded* parent guarantees at least
        // four mutations happen mid-iteration regardless of iteration order.
        val firstYielded = iter.next().id
        val mutated = (0 until 5).filter { it != firstYielded }
        mutated.forEach { playlists.findById(it).orElseThrow().trackIds = mutableListOf() }

        val drained = mutableListOf<Int>()
        while (iter.hasNext()) drained.add(iter.next().id)

        // Every mutated parent's matches() now reads an empty collection live, so none of them
        // may appear in the drained tail. The drain therefore must be empty (the first parent
        // was already yielded before mutation).
        drained shouldBe emptyList()

        ctx.close()
    }

    /**
     * HASH_JOIN parallel to case 3. The strategy snapshots the matching-children id set once
     * — that's correct and is the strategy's contract. Parents are still iterated lazily and
     * each parent's `parentProp.get(p)` is read live, so a mid-iteration mutation of a parent's
     * `trackIds` still drops that parent from the result.
     */
    test("HASH_JOIN strategy with DETACH: deleting a child mid-iteration still drops the parent because parentProp is read live") {
        val ctx = LirpContext()
        val tracks =
            TrackRepoIso(ctx).apply {
                // High parent count + selective child predicate → HASH_JOIN.
                repeat(200) { add(Track(it, "t$it", if (it == 5) 999.0 else 1.0)) }
            }
        val playlists =
            MutablePlaylistRepo(ctx).apply {
                // 50 parents × 20 refs each → crossover 1000; matchingChildCount = 1 → HASH_JOIN.
                // Every parent gets id 5 in its collection so all 50 would match without mutation.
                repeat(50) { p ->
                    val refs = (0 until 20).map { (p * 20 + it) % 200 }.toMutableList()
                    if (5 !in refs) refs.add(5)
                    add(MutablePlaylist(p, "p$p", refs))
                }
            }
        val via = MutablePlaylist::trackIds via tracks anyMatch { Track::price gt 500.0 }

        val planner = nonIndexedPlannerForPlaylist()
        planner.strategyFor(via, playlists) shouldBe ViaStrategy.HASH_JOIN

        val iter = planner.executeViaSequence(via, playlists).iterator()

        // Advance one yield so the iterator is mid-flight.
        iter.next()

        // Pick a parent that very likely has not yet been yielded (high id) and clear id 5 from
        // its collection (simulating DETACH reconciliation of a deleted Track(5) reference).
        val late = playlists.findById(49).orElseThrow()
        (late.trackIds as MutableList<Int>).remove(5)

        val drained = mutableListOf<Int>()
        while (iter.hasNext()) drained.add(iter.next().id)

        // Parent 49 had id 5 cleared mid-iteration. The HASH_JOIN parent loop calls
        // parentProp.get(p).any { it in matchingIds } LIVE — so the mutated parent is dropped.
        drained shouldNotContain 49

        ctx.close()
    }

    test("viaAccessorFor returns descriptors that point at the same entity class the query uses") {
        // Sanity check: the KSP-generated accessor for BubbleUpAudioPlaylist declares its
        // single-ref `audioItem` as targeting AudioItem::class.java. That is the same AudioItem
        // class instance the user constructs a Registry<Int, AudioItem> around when writing
        // `BubbleUpAudioPlaylist::audioItemId via audioItems where { … }`. DETACH lifecycle
        // lives on the AudioItem Registry and is therefore the same source of truth for both
        // the accessor metadata and the Via* AST node.
        val accessor = RegistryBase.viaAccessorFor(BubbleUpAudioPlaylist::class.java)
        accessor.shouldNotBeNull()
        accessor.singleEntries.size shouldBe 1
        accessor.singleEntries[0].refName shouldBe "audioItem"
        accessor.singleEntries[0].referencedClass shouldBe AudioItem::class.java
    }
})

/** Mutable single-ref parent fixture for case 2: a nullable FK that can be cleared post-DETACH. */
internal data class MutableSingleRefParent(
    override val id: Int,
    val name: String,
    var ownerId: Int?,
    override val uniqueId: String = "msrp-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/** Isolated-context parent registry for the DETACH integration suite. */
internal class MutableSingleRefParentRepo(context: LirpContext) :
    VolatileRepository<Int, MutableSingleRefParent>(context, "DetachSingleRefParents")

/** Isolated-context mutable-playlist registry for the DETACH integration suite. */
internal class MutablePlaylistRepo(context: LirpContext) :
    VolatileRepository<Int, MutablePlaylist>(context, "DetachMutablePlaylists")

/** Isolated-context track registry — avoids clashing with the [TrackRepo] in [ViaOperatorsTest]. */
internal class TrackRepoIso(context: LirpContext) :
    VolatileRepository<Int, Track>(context, "DetachTracks")

/** Isolated-context owner registry — avoids clashing with the [OwnerRepo] in [ViaOperatorsTest]. */
internal class OwnerRepoIso(context: LirpContext) :
    VolatileRepository<Int, Owner>(context, "DetachOwners")
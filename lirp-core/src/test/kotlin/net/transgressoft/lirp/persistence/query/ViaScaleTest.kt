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

import net.transgressoft.lirp.testing.Stress
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * Spike-010 parity gate for Builds a 50,000-parent × 20-child-ref fixture with a
 * highly selective child predicate (~10 matching children) and asserts:
 *  - the cardinality-driven planner picks [ViaStrategy.HASH_JOIN] for this shape,
 *  - the result count matches the analytically expected number of parents,
 *  - wall-clock execution stays under a loose 2-second budget.
 *
 * Tagged `Stress` so it is excluded from the default `gradle test` run; opt in via
 * `gradle :lirp-core:test -Pkotest.tags.include=Stress`.
 */
@DisplayName("Via scale")
internal class ViaScaleTest : FunSpec({
    tags(Stress)

    test("HASH_JOIN strategy on 50k parents x 20 refs per parent with 10 matching children completes within budget") {
        val parentCount = 50_000
        val refsPerParent = 20
        val matchingChildren = 10
        val childPool = 200_000

        val tracks =
            TrackRepo().apply {
                // First `matchingChildren` tracks have a high price that the predicate matches.
                // Remaining tracks have low price (predicate filters them out).
                repeat(childPool) { i ->
                    val price = if (i < matchingChildren) 9999.0 else 1.0
                    add(Track(i, "t$i", price))
                }
            }

        // Each parent references 20 distinct track ids. Parents 0..(matchingChildren-1) own
        // exactly one matching id (parent p references tracks [p, p+matchingChildren, p+2*matchingChildren, ...]).
        // For p >= matchingChildren the references are deterministic and miss every matching id.
        val playlists =
            PlaylistRepo().apply {
                repeat(parentCount) { p ->
                    val refs =
                        (0 until refsPerParent).map { k ->
                            // Spread parent's refs across the child pool deterministically.
                            (p + k * matchingChildren) % childPool
                        }
                    add(Playlist(p, "p$p", refs, null))
                }
            }

        // Selective predicate: only the first 10 children match.
        val via = Playlist::trackIds via tracks anyMatch { Track::price gt 5000.0 }
        val planner =
            QueryPlanner<Playlist>(isIndexed = { false }, indexNameFor = { it.name })

        // Strategy assertion (Spike 010 parity): with crossover = 50000 * 20 = 1_000_000 and
        // matching children = 10, the planner must pick HASH_JOIN (10 << 1_000_000).
        planner.strategyFor(via, playlists) shouldBe ViaStrategy.HASH_JOIN

        // Expected result count: parents whose ref list intersects {0..9}. By construction parent p's
        // refs are { (p + k*10) mod 200_000 | k in 0..19 } — for any p, exactly the parents whose
        // (p mod 10) lands a multiple of 10 in their ref set match. Compute the truth set directly
        // from the fixture by scanning the live `referenceIds` once.
        val matchingIds = (0 until matchingChildren).toSet()
        val expected = playlists.asSequence().count { p -> p.trackIds.any { it in matchingIds } }

        val startNanos = System.nanoTime()
        val results = planner.executeViaSequence(via, playlists).toList()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L

        results.size shouldBe expected
        elapsedMs shouldBeLessThan 2_000L
    }
})
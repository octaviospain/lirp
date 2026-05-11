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
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

/** Two-level nested fixture: parent → child with its own collection ref → leaf. Used to exercise
 *  fold-result re-normalisation when the outer same-ref fold surfaces a foldable inner pair. */
internal data class Album(
    override val id: Int,
    val year: Int,
    override val uniqueId: String = "album-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

internal data class Mixtape(
    override val id: Int,
    val albumIds: List<Int>,
    override val uniqueId: String = "mixtape-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

internal data class Crate(
    override val id: Int,
    val mixtapeIds: List<Int>,
    override val uniqueId: String = "crate-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/**
 * Sibling parent type used to construct a predicate over a different `KProperty1`
 * so the normaliser sees a same-shape pair with non-equal parentProp.
 */
internal data class OtherPlaylist(
    override val id: Int,
    val trackIds: List<Int>,
    override val uniqueId: String = "other-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

internal class AlbumRepo : VolatileRepository<Int, Album>(LirpContext.default, "Albums")

internal class MixtapeRepo : VolatileRepository<Int, Mixtape>(LirpContext.default, "Mixtapes")

internal class CrateRepo : VolatileRepository<Int, Crate>(LirpContext.default, "Crates")

/**
 * Unit tests for the OR/AND-union AST normaliser. Verifies the three fold rules
 * (D-11, D-12, plus the orchestrator-confirmed AllMatch fold), the anti-rules for
 * mismatched quantifiers / boolean ops / refs (D-12, D-13), recursion into nested
 * compositions, and idempotency.
 */
@DisplayName("ViaNormalizer")
internal class ViaNormalizerTest : FunSpec({

    val tracks =
        TrackRepo().apply {
            add(Track(1, "a", 10.0))
            add(Track(2, "b", 50.0))
            add(Track(3, "c", 200.0))
        }
    val tracksAlt =
        TrackRepo().apply {
            add(Track(1, "a", 10.0))
        }
    val owners =
        OwnerRepo().apply {
            add(Owner(10, "Berlin"))
        }

    test("folds Or of two ViaAnyMatch on same ref") {
        val left = Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        val right = Playlist::trackIds via tracks anyMatch { Track::title eq "b" }
        val composed = left or right
        val result = normalize(composed)
        result.shouldBeInstanceOf<ViaAnyMatch<Playlist, Int, Track>>()
        result.parentProp shouldBe Playlist::trackIds
        result.childRegistry shouldBeSameInstanceAs tracks
        result.childPredicate.shouldBeInstanceOf<Predicate.Or<Track>>()
    }

    test("folds And of two ViaNoneMatch on same ref") {
        val left = Playlist::trackIds via tracks noneMatch { Track::price gt 100.0 }
        val right = Playlist::trackIds via tracks noneMatch { Track::title eq "b" }
        val composed = left and right
        val result = normalize(composed)
        result.shouldBeInstanceOf<ViaNoneMatch<Playlist, Int, Track>>()
        result.parentProp shouldBe Playlist::trackIds
        result.childRegistry shouldBeSameInstanceAs tracks
        result.childPredicate.shouldBeInstanceOf<Predicate.Or<Track>>()
    }

    test("folds And of two ViaAllMatch on same ref") {
        val left = Playlist::trackIds via tracks allMatch { Track::price gt 10.0 }
        val right = Playlist::trackIds via tracks allMatch { Track::price lt 1000.0 }
        val composed = left and right
        val result = normalize(composed)
        result.shouldBeInstanceOf<ViaAllMatch<Playlist, Int, Track>>()
        result.parentProp shouldBe Playlist::trackIds
        result.childRegistry shouldBeSameInstanceAs tracks
        result.childPredicate.shouldBeInstanceOf<Predicate.And<Track>>()
    }

    test("does not fold Or of two ViaAllMatch on same ref") {
        val left = Playlist::trackIds via tracks allMatch { Track::price gt 10.0 }
        val right = Playlist::trackIds via tracks allMatch { Track::price lt 1000.0 }
        val composed = left or right
        val result = normalize(composed)
        result.shouldBeInstanceOf<Predicate.Or<Playlist>>()
    }

    test("does not fold And of two ViaAnyMatch on same ref") {
        val left = Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        val right = Playlist::trackIds via tracks anyMatch { Track::title eq "b" }
        val composed = left and right
        val result = normalize(composed)
        result.shouldBeInstanceOf<Predicate.And<Playlist>>()
    }

    test("does not fold Or of mixed ViaAnyMatch and ViaAllMatch") {
        val left = Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        val right = Playlist::trackIds via tracks allMatch { Track::price gt 0.0 }
        val composed = left or right
        val result = normalize(composed)
        result.shouldBeInstanceOf<Predicate.Or<Playlist>>()
    }

    test("does not fold Or of two ViaAnyMatch on different parent properties") {
        val left = Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        // Construct a same-shape pair with a different parentProp KProperty1
        val right = OtherPlaylist::trackIds via tracks anyMatch { Track::title eq "b" }

        // Cast both to Predicate<Any> to allow OR composition over distinct parent types — only used to
        // verify normalize leaves them unfolded; the wrapper Or is what we assert on.
        @Suppress("UNCHECKED_CAST")
        val composed: Predicate<Playlist> = Predicate.Or(left, right as Predicate<Playlist>)
        val result = normalize(composed)
        result.shouldBeInstanceOf<Predicate.Or<Playlist>>()
    }

    test("does not fold Or of two ViaAnyMatch on different child registries") {
        val left = Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        val right = Playlist::trackIds via tracksAlt anyMatch { Track::title eq "b" }
        val composed = left or right
        val result = normalize(composed)
        result.shouldBeInstanceOf<Predicate.Or<Playlist>>()
    }

    test("recursively folds nested same-ref pair under outer And") {
        val innerLeft = Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        val innerRight = Playlist::trackIds via tracks anyMatch { Track::title eq "b" }
        val outerLeft = innerLeft or innerRight
        val outerRight: Predicate<Playlist> = Playlist::name eq "Mix"
        val composed = outerLeft and outerRight
        val result = normalize(composed)
        result.shouldBeInstanceOf<Predicate.And<Playlist>>()
        val and = result as Predicate.And<Playlist>
        and.left.shouldBeInstanceOf<ViaAnyMatch<Playlist, Int, Track>>()
        (and.left as ViaAnyMatch<Playlist, Int, Track>).childPredicate
            .shouldBeInstanceOf<Predicate.Or<Track>>()
    }

    test("leaves non-Via predicates untouched") {
        val pred: Predicate<Playlist> = (Playlist::name eq "Mix") and (Playlist::ownerId eq 10)
        val result = normalize(pred)
        result shouldBeSameInstanceAs pred
    }

    test("leaves single Via unchanged when its child predicate is already normalised") {
        val pred = Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        val result = normalize(pred)
        result shouldBeSameInstanceAs pred
    }

    test("normalises non-fold Via nested inside Not preserving the structure") {
        val pred: Predicate<Playlist> = !(Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 })
        val result = normalize(pred)
        result.shouldBeInstanceOf<Predicate.Not<Playlist>>()
    }

    test("is idempotent on already-normalised predicates") {
        val left = Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        val right = Playlist::trackIds via tracks anyMatch { Track::title eq "b" }
        val composed = left or right
        val once = normalize(composed)
        val twice = normalize(once)
        // Idempotent: shape of the second normalisation matches the first (a single ViaAnyMatch carrying Or).
        twice.shouldBeInstanceOf<ViaAnyMatch<Playlist, Int, Track>>()
        (twice as ViaAnyMatch<Playlist, Int, Track>).childPredicate
            .shouldBeInstanceOf<Predicate.Or<Track>>()
    }

    test("also normalises through Owner where predicate without folding it") {
        val pred = Playlist::ownerId via owners where { Owner::city eq "Berlin" }
        val result = normalize(pred)
        result.shouldBeInstanceOf<ViaWhere<Playlist, Int, Owner>>()
    }

    test("collapses nested same-ref pair surfaced by an outer fold in a single normalize pass") {
        // Bug fixed by the orchestrator-confirmed nitpick (#155 CodeRabbit review): after the
        // outer Or folds two same-ref Via* nodes into Via(outerRef, Or(innerVia1, innerVia2)),
        // the resulting fresh inner Or is itself a foldable same-ref pair. Without re-normalising
        // fold results, the inner collapse required a second normalize() pass — breaking
        // single-pass idempotency under nested same-ref nesting.
        val albums = AlbumRepo()
        val mixtapes = MixtapeRepo()
        val innerLeft: Predicate<Mixtape> = Mixtape::albumIds via albums anyMatch { Album::year gt 1990 }
        val innerRight: Predicate<Mixtape> = Mixtape::albumIds via albums anyMatch { Album::year lte 2000 }
        val outerLeft = Crate::mixtapeIds via mixtapes anyMatch { innerLeft }
        val outerRight = Crate::mixtapeIds via mixtapes anyMatch { innerRight }
        val composed = outerLeft or outerRight

        val once = normalize(composed)
        // Single normalize pass must already collapse both levels:
        //   Or(VAM(m, VAM(a, p1)), VAM(m, VAM(a, p2)))
        //     → VAM(m, Or(VAM(a, p1), VAM(a, p2)))   (outer fold)
        //     → VAM(m, VAM(a, Or(p1, p2)))           (inner re-normalisation of fold result)
        once.shouldBeInstanceOf<ViaAnyMatch<Crate, Int, Mixtape>>()
        val outerChild = (once as ViaAnyMatch<Crate, Int, Mixtape>).childPredicate
        outerChild.shouldBeInstanceOf<ViaAnyMatch<Mixtape, Int, Album>>()
        (outerChild as ViaAnyMatch<Mixtape, Int, Album>).childPredicate
            .shouldBeInstanceOf<Predicate.Or<Album>>()
    }
})
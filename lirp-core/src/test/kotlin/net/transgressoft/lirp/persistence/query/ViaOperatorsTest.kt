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
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

/** Test parent entity with a collection foreign-key (`trackIds`) and a nullable single-entity ref (`ownerId`). */
internal data class Playlist(
    override val id: Int,
    val name: String,
    val trackIds: List<Int>,
    val ownerId: Int?,
    override val uniqueId: String = "playlist-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/** Test child entity referenced by [Playlist.trackIds]. */
internal data class Track(
    override val id: Int,
    val title: String,
    val price: Double,
    override val uniqueId: String = "track-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/** Test child entity referenced by [Playlist.ownerId]. */
internal data class Owner(
    override val id: Int,
    val city: String,
    override val uniqueId: String = "owner-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/** Mutable parent variant used to verify the live-`referenceIds` invariant. */
internal data class MutablePlaylist(
    override val id: Int,
    val name: String,
    var trackIds: List<Int>,
    override val uniqueId: String = "mplaylist-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

internal class PlaylistRepo : VolatileRepository<Int, Playlist>(LirpContext.default, "Playlists")

internal class TrackRepo : VolatileRepository<Int, Track>(LirpContext.default, "Tracks")

internal class OwnerRepo : VolatileRepository<Int, Owner>(LirpContext.default, "Owners")

/**
 * Unit tests for the `via … anyMatch / allMatch / noneMatch / where` operator surface.
 *
 * Covers (null single-entity), (empty-collection semantics), and composition
 * with Phase 52 operators. Live-`referenceIds` invariant verified at unit level.
 */
@DisplayName("Via* operators")
internal class ViaOperatorsTest : FunSpec({

    fun fresh(): Triple<PlaylistRepo, TrackRepo, OwnerRepo> {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "cheap", 5.0))
                add(Track(2, "mid", 50.0))
                add(Track(3, "expensive", 150.0))
            }
        val owners =
            OwnerRepo().apply {
                add(Owner(10, "Berlin"))
                add(Owner(11, "Paris"))
            }
        val playlists =
            PlaylistRepo().apply {
                add(Playlist(100, "Mix", listOf(1, 2, 3), 10))
                add(Playlist(101, "Cheap", listOf(1, 2), 11))
                add(Playlist(102, "Empty", emptyList(), null))
            }
        return Triple(playlists, tracks, owners)
    }

    test("via tracks anyMatch returns parents with at least one matching child") {
        val (_, tracks, _) = fresh()
        val pred: Predicate<Playlist> = Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        pred.matches(Playlist(1, "p", listOf(1, 2, 3), null)).shouldBeTrue()
        pred.matches(Playlist(2, "p", listOf(1, 2), null)).shouldBeFalse()
    }

    test("via tracks anyMatch on empty collection excludes parent") {
        val (_, tracks, _) = fresh()
        val pred = Playlist::trackIds via tracks anyMatch { Track::price gt 0.0 }
        pred.matches(Playlist(1, "p", emptyList(), null)).shouldBeFalse()
    }

    test("via tracks allMatch on empty collection includes parent vacuously") {
        val (_, tracks, _) = fresh()
        val pred = Playlist::trackIds via tracks allMatch { Track::price gt 1000.0 }
        pred.matches(Playlist(1, "p", emptyList(), null)).shouldBeTrue()
    }

    test("via tracks allMatch returns true when every resolved child matches") {
        val (_, tracks, _) = fresh()
        val pred = Playlist::trackIds via tracks allMatch { Track::price gt 4.0 }
        pred.matches(Playlist(1, "p", listOf(1, 2, 3), null)).shouldBeTrue()
        pred.matches(Playlist(2, "p", listOf(1, 2), null)).shouldBeTrue()
        val withFailing = Playlist(3, "p", listOf(1, 2, 3), null)
        (Playlist::trackIds via tracks allMatch { Track::price gt 100.0 }).matches(withFailing).shouldBeFalse()
    }

    test("via tracks allMatch returns false when any id is unresolved") {
        val (_, tracks, _) = fresh()
        val pred = Playlist::trackIds via tracks allMatch { Track::price gt 0.0 }
        pred.matches(Playlist(1, "p", listOf(1, 999), null)).shouldBeFalse()
    }

    test("via tracks noneMatch on empty collection includes parent vacuously") {
        val (_, tracks, _) = fresh()
        val pred = Playlist::trackIds via tracks noneMatch { Track::price gt 0.0 }
        pred.matches(Playlist(1, "p", emptyList(), null)).shouldBeTrue()
    }

    test("via tracks noneMatch returns true when no resolved child matches") {
        val (_, tracks, _) = fresh()
        val pred = Playlist::trackIds via tracks noneMatch { Track::price gt 1000.0 }
        pred.matches(Playlist(1, "p", listOf(1, 2, 3), null)).shouldBeTrue()
        (Playlist::trackIds via tracks noneMatch { Track::price gt 100.0 })
            .matches(Playlist(2, "p", listOf(1, 2, 3), null)).shouldBeFalse()
    }

    test("via customers where on null single-entity ref excludes parent") {
        val (_, _, owners) = fresh()
        val pred = Playlist::ownerId via owners where { Owner::city eq "Berlin" }
        pred.matches(Playlist(1, "p", emptyList(), null)).shouldBeFalse()
    }

    test("via customers where returns true when resolved child matches") {
        val (_, _, owners) = fresh()
        val pred = Playlist::ownerId via owners where { Owner::city eq "Berlin" }
        pred.matches(Playlist(1, "p", emptyList(), 10)).shouldBeTrue()
        pred.matches(Playlist(2, "p", emptyList(), 11)).shouldBeFalse()
    }

    test("via customers where returns false when id does not resolve") {
        val (_, _, owners) = fresh()
        val pred = Playlist::ownerId via owners where { Owner::city eq "Berlin" }
        pred.matches(Playlist(1, "p", emptyList(), 999)).shouldBeFalse()
    }

    test("via tracks anyMatch composes with eq using and") {
        val (_, tracks, _) = fresh()
        val composed: Predicate<Playlist> =
            (Playlist::name eq "Mix") and (Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 })
        composed.matches(Playlist(1, "Mix", listOf(1, 2, 3), null)).shouldBeTrue()
        composed.matches(Playlist(2, "Other", listOf(1, 2, 3), null)).shouldBeFalse()
        composed.matches(Playlist(3, "Mix", listOf(1, 2), null)).shouldBeFalse()
    }

    test("via tracks anyMatch composes with not") {
        val (_, tracks, _) = fresh()
        val negated: Predicate<Playlist> = !(Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 })
        negated.matches(Playlist(1, "p", listOf(1, 2), null)).shouldBeTrue()
        negated.matches(Playlist(2, "p", listOf(1, 2, 3), null)).shouldBeFalse()
    }

    test("via tracks anyMatch reads referenceIds live on each invocation") {
        val (_, tracks, _) = fresh()
        val pred = MutablePlaylist::trackIds via tracks anyMatch { Track::price gt 100.0 }
        val parent = MutablePlaylist(1, "p", listOf(1, 2))
        pred.matches(parent).shouldBeFalse()
        parent.trackIds = listOf(1, 2, 3)
        pred.matches(parent).shouldBeTrue()
        parent.trackIds = listOf(1)
        pred.matches(parent).shouldBeFalse()
    }

    /*
     * COMPILE-FAIL negatives — uncommenting any of the following lines must fail to compile.
     * Kept here as documentation; never enable.
     *
     * // (1) Wrong K: Order::customerId is String but tracks is Registry<Int, Track>
     * // val tracks: TrackRepo = TODO()
     * // val badKey: KProperty1<Playlist, Collection<String>> = TODO()
     * // badKey via tracks anyMatch { Track::price gt 100.0 }
     *
     * // (2) Track::price is Double — not Collection<K> and not K?, so no `via` overload applies
     * // Track::price via tracks anyMatch { Track::price gt 100.0 }
     *
     * // (3) `anyMatch` is collection-only — single-entity refs must use `where`
     * // Playlist::ownerId via owners anyMatch { Owner::city eq "Berlin" }
     */
})
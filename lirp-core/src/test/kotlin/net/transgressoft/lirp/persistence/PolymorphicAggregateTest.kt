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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Music-domain test fixture that holds a polymorphic reference to either an [AudioItem]
 * (arm "item") or a [MutableAudioPlaylist] (arm "playlist"), but never both and never neither.
 * Used by [PolymorphicAggregateTest] to exercise the exactly-one-non-null invariant.
 */
private class AudioContribution(
    override val id: Int,
    var audioItemId: Int? = null,
    var playlistId: Int? = null
) : ReactiveEntityBase<Int, AudioContribution>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "audio-contribution-$id"

    val target by polymorphicAggregate(
        arm<Int, AudioItem>("item") { audioItemId },
        arm<Int, MutableAudioPlaylist>("playlist") { playlistId }
    )

    override fun clone(): AudioContribution = AudioContribution(id, audioItemId, playlistId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioContribution) return false
        return id == other.id && audioItemId == other.audioItemId && playlistId == other.playlistId
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + (audioItemId?.hashCode() ?: 0)) + (playlistId?.hashCode() ?: 0)

    override fun toString(): String = "AudioContribution(id=$id, audioItemId=$audioItemId, playlistId=$playlistId)"
}

/**
 * Tests for the [PolymorphicAggregateDelegate] runtime delegate, covering the four invariant
 * states of the exactly-one-non-null rule and the lazy-access contract.
 */
@DisplayName("PolymorphicAggregateDelegate")
internal class PolymorphicAggregateTest : FunSpec({

    // ---------------------------------------------------------------------------
    // Invariant cases (POLY-10)
    // ---------------------------------------------------------------------------

    test("PolymorphicAggregateDelegate resolve returns the active arm's entity when only the AudioItem arm is set") {
        val ctx = LirpContext()
        val itemRepo = AudioItemVolatileRepository(ctx)
        val item = itemRepo.create(id = 1, title = "Track A")

        val contribution = AudioContribution(id = 10, audioItemId = 1)
        val contributionRepo = VolatileRepository<Int, AudioContribution>(ctx, "Contributions")
        contributionRepo.add(contribution)

        val resolved = contribution.target.resolve()
        resolved shouldBe item
        ctx.close()
    }

    test("PolymorphicAggregateDelegate resolve returns the active arm's entity when only the AudioPlaylist arm is set") {
        val ctx = LirpContext()
        val playlistRepo = AudioPlaylistVolatileRepository(ctx)
        val playlist = DefaultAudioPlaylist(id = 5, name = "My Playlist")
        playlistRepo.add(playlist)

        val contribution = AudioContribution(id = 20, playlistId = 5)
        val contributionRepo = VolatileRepository<Int, AudioContribution>(ctx, "Contributions")
        contributionRepo.add(contribution)

        val resolved = contribution.target.resolve()
        resolved shouldBe playlist
        ctx.close()
    }

    test("PolymorphicAggregateDelegate resolve throws IllegalStateException when both arms are non-null") {
        val contribution = AudioContribution(id = 30, audioItemId = 1, playlistId = 5)

        shouldThrow<IllegalStateException> {
            contribution.target.resolve()
        }
    }

    test("PolymorphicAggregateDelegate resolve throws IllegalStateException when no arm is set") {
        val contribution = AudioContribution(id = 40)

        shouldThrow<IllegalStateException> {
            contribution.target.resolve()
        }
    }

    // ---------------------------------------------------------------------------
    // Lazy-access case: constructing the entity and accessing the property does
    // NOT throw — the invariant is only checked on resolve()/activeArm() calls.
    // ---------------------------------------------------------------------------

    test("PolymorphicAggregateDelegate property access before resolve does not throw") {
        val contribution = AudioContribution(id = 50, audioItemId = null, playlistId = null)

        // Accessing the property (getValue) returns the delegate without firing the invariant check
        val delegate = contribution.target
        delegate.shouldBeInstanceOf<PolymorphicAggregateDelegate>()
    }

    // ---------------------------------------------------------------------------
    // activeArm() label accessor cases
    // ---------------------------------------------------------------------------

    test("PolymorphicResolution activeArm returns label of the single non-null arm") {
        val ctx = LirpContext()
        val itemRepo = AudioItemVolatileRepository(ctx)
        itemRepo.create(id = 1, title = "Track A")

        val contribution = AudioContribution(id = 60, audioItemId = 1)
        val contributionRepo = VolatileRepository<Int, AudioContribution>(ctx, "Contributions")
        contributionRepo.add(contribution)

        contribution.target.resolution().resolveActiveLabel() shouldBe "item"
        ctx.close()
    }

    test("PolymorphicResolution activeArm throws IllegalStateException when both arms are set") {
        val contribution = AudioContribution(id = 70, audioItemId = 1, playlistId = 5)

        shouldThrow<IllegalStateException> {
            contribution.target.resolution().resolveActiveLabel()
        }
    }

    // ---------------------------------------------------------------------------
    // resolveArm() by label
    // ---------------------------------------------------------------------------

    test("PolymorphicResolution resolveArm by label returns the correct arm's entity") {
        val ctx = LirpContext()
        val itemRepo = AudioItemVolatileRepository(ctx)
        val item = itemRepo.create(id = 1, title = "Track B")

        val contribution = AudioContribution(id = 80, audioItemId = 1)
        val contributionRepo = VolatileRepository<Int, AudioContribution>(ctx, "Contributions")
        contributionRepo.add(contribution)

        contribution.target.resolution().resolveArm("item") shouldBe item
        ctx.close()
    }

    // ---------------------------------------------------------------------------
    // armDelegate() accessor
    // ---------------------------------------------------------------------------

    test("PolymorphicAggregateDelegate armDelegate returns the inner AggregateRefDelegate for the named arm") {
        val ctx = LirpContext()
        val itemRepo = AudioItemVolatileRepository(ctx)
        itemRepo.create(id = 1, title = "Track C")

        val contribution = AudioContribution(id = 85, audioItemId = 1)
        val contributionRepo = VolatileRepository<Int, AudioContribution>(ctx, "Contributions")
        contributionRepo.add(contribution)

        val delegate = contribution.target.armDelegate("item")
        delegate.shouldBeInstanceOf<AggregateRefDelegate<*, *>>()
        ctx.close()
    }

    // ---------------------------------------------------------------------------
    // Pre-persist gate (Task 2 acceptance criteria)
    // Entities with a both-set or none-set polymorphic property are rejected by
    // PersistentRepositoryBase.add() before the insert is enqueued.
    // ---------------------------------------------------------------------------

    test("PersistentRepositoryBase add rejects an entity with both arms set") {
        val bothSet = AudioContribution(id = 90, audioItemId = 1, playlistId = 5)
        val repo = AudioContributionPersistentRepo()

        shouldThrow<IllegalStateException> {
            repo.add(bothSet)
        }
        // A rejected add must not leave the entity registered — no partial repository mutation.
        repo.findById(90).isPresent shouldBe false
        repo.quiesceAndClose()
    }

    test("PersistentRepositoryBase add rejects an entity with no arm set") {
        val noneSet = AudioContribution(id = 100)
        val repo = AudioContributionPersistentRepo()

        shouldThrow<IllegalStateException> {
            repo.add(noneSet)
        }
        // A rejected add must not leave the entity registered — no partial repository mutation.
        repo.findById(100).isPresent shouldBe false
        repo.quiesceAndClose()
    }

    test("PersistentRepositoryBase add accepts an entity with exactly one arm set") {
        val valid = AudioContribution(id = 110, audioItemId = 42)
        val repo = AudioContributionPersistentRepo()

        shouldNotThrowAny {
            repo.add(valid)
        }
        repo.quiesceAndClose()
    }

    // ---------------------------------------------------------------------------
    // Label validation: duplicate and blank arm labels are rejected at construction
    // ---------------------------------------------------------------------------

    test("polymorphicAggregate rejects duplicate arm labels at construction") {
        var itemId: Int? = 1
        var playlistId: Int? = null
        shouldThrow<IllegalArgumentException> {
            polymorphicAggregate(
                arm<Int, AudioItem>("dup") { itemId },
                arm<Int, MutableAudioPlaylist>("dup") { playlistId }
            )
        }
    }

    test("polymorphicAggregate rejects a blank arm label at construction") {
        var itemId: Int? = 1
        shouldThrow<IllegalArgumentException> {
            polymorphicAggregate(
                arm<Int, AudioItem>("  ") { itemId }
            )
        }
    }

    test("polymorphicAggregate rejects an empty arm set at construction") {
        shouldThrow<IllegalArgumentException> {
            polymorphicAggregate()
        }
    }

    // ---------------------------------------------------------------------------
    // Unresolved-arm contract: resolving an arm whose referenced entity is absent
    // from its registry throws the documented IllegalStateException, not the
    // implementation-internal NoSuchElementException from Optional.get().
    // ---------------------------------------------------------------------------

    test("resolve throws IllegalStateException when the active arm references an absent entity") {
        val ctx = LirpContext()
        // Register the item registry so binding succeeds, but never add the referenced item.
        AudioItemVolatileRepository(ctx)

        val contribution = AudioContribution(id = 120, audioItemId = 999)
        val contributionRepo = VolatileRepository<Int, AudioContribution>(ctx, "Contributions")
        contributionRepo.add(contribution)

        shouldThrow<IllegalStateException> {
            contribution.target.resolve()
        }
        ctx.close()
    }

    // ---------------------------------------------------------------------------
    // Atomic single-scan resolution: resolveActive returns label + entity from one
    // invariant scan so dispatch and resolution cannot diverge under mutation.
    // ---------------------------------------------------------------------------

    test("resolveActive returns the active arm label paired with its resolved entity from a single scan") {
        val ctx = LirpContext()
        val itemRepo = AudioItemVolatileRepository(ctx)
        val item = itemRepo.create(id = 1, title = "Track D")

        val contribution = AudioContribution(id = 130, audioItemId = 1)
        val contributionRepo = VolatileRepository<Int, AudioContribution>(ctx, "Contributions")
        contributionRepo.add(contribution)

        val (label, entity) = contribution.target.resolution().resolveActive()
        label shouldBe "item"
        entity shouldBe item
        ctx.close()
    }
})

/**
 * Minimal [PersistentRepositoryBase] subclass for [AudioContribution] entities, used to test
 * that the pre-persist polymorphic validation fires before any insert is enqueued.
 *
 * [writePending] is a no-op so no backing store is touched; [loadOnInit] is false so tests
 * call [load] explicitly (via [init]).
 */
private class AudioContributionPersistentRepo :
    PersistentRepositoryBase<Int, AudioContribution>(
        name = "AudioContributions",
        loadOnInit = false
    ) {
    init {
        load()
    }

    override fun loadFromStore(): Map<Int, AudioContribution> = emptyMap()

    override fun writePending(
        inserts: List<AudioContribution>,
        updates: List<PendingUpdate<Int, AudioContribution>>,
        deletes: List<Pair<Int, Long?>>,
        hadClear: Boolean
    ) {
        // No-op: tests only care about the pre-persist validation, not the backing store.
    }

    fun quiesceAndClose() = close()
}
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

package net.transgressoft.lirp.persistence.fx

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.Aggregate
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.FxObservableCollection
import net.transgressoft.lirp.persistence.LirpRepository
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javafx.collections.ListChangeListener
import javafx.collections.SetChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Test entity that uses lazy-snapshot FxAggregateList and FxAggregateSet delegates.
 * The lazy-snapshot mode eliminates the local element cache, resolving entities from the
 * registry on demand. Registry binding is triggered when this entity is added to its repository.
 */
class LazyFxPlaylistEntity(
    override val id: Int,
    initialAudioItemIds: List<Int> = emptyList(),
    initialRelatedIds: Set<Int> = emptySet()
) : ReactiveEntityBase<Int, LazyFxPlaylistEntity>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "lazy-fx-playlist-$id"

    @Aggregate(onDelete = CascadeAction.DETACH)
    val audioItems by fxAggregateList<Int, AudioItem>(initialAudioItemIds, dispatchToFxThread = false, lazySnapshot = true)

    @Aggregate(onDelete = CascadeAction.DETACH)
    val relatedItems by fxAggregateSet<Int, AudioItem>(initialRelatedIds, dispatchToFxThread = false, lazySnapshot = true)

    override fun clone(): LazyFxPlaylistEntity = LazyFxPlaylistEntity(id, audioItems.referenceIds.toList(), LinkedHashSet(relatedItems.referenceIds))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LazyFxPlaylistEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "LazyFxPlaylistEntity(id=$id)"
}

/**
 * In-memory repository for [LazyFxPlaylistEntity] entities.
 * Adding an entity to this repository triggers registry binding for the entity's lazy FxAggregate delegates.
 */
@LirpRepository
class LazyFxPlaylistRepo : VolatileRepository<Int, LazyFxPlaylistEntity>("LazyFxPlaylists") {
    fun create(id: Int, audioItemIds: List<Int> = emptyList(), relatedIds: Set<Int> = emptySet()): LazyFxPlaylistEntity =
        LazyFxPlaylistEntity(id, audioItemIds, relatedIds).also(::add)
}

/**
 * Tests for [FxAggregateList] and [FxAggregateSet] in lazy-snapshot mode.
 *
 * Validates correctness with 10k+ entity collections and covers all mutation operations:
 * add, addAll, remove, removeAt, removeAll, retainAll, clear, set, setAll, and remove(from, to).
 * Change listener notifications are verified for each operation. Repositories are shared
 * across tests and cleared between each test to avoid duplicate-registration errors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FxAggregateLazySnapshotTest : StringSpec({

    val testScope = CoroutineScope(UnconfinedTestDispatcher())

    // Repositories registered once for the entire spec; cleared between tests
    val audioItemRepo = FxAudioItemVolatileRepository()
    val playlistRepo = LazyFxPlaylistRepo()

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        audioItemRepo.close()
        playlistRepo.close()
    }

    afterEach {
        audioItemRepo.clear()
        playlistRepo.clear()
    }

    "FxAggregateList in lazy-snapshot mode with 10000 entities returns correct size and elements" {
        val items = (1..10_000).map { audioItemRepo.create(it, "Song $it") }
        val playlist = playlistRepo.create(1)
        playlist.audioItems.addAll(items)

        playlist.audioItems.size shouldBe 10_000
        playlist.audioItems[0].id shouldBe 1
        playlist.audioItems[9_999].id shouldBe 10_000
    }

    "FxAggregateList lazy-snapshot fires AddChange on single add" {
        val item = audioItemRepo.create(1, "Song A")
        val playlist = playlistRepo.create(1)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        playlist.audioItems.addListener(ListChangeListener(changes::add))

        playlist.audioItems.add(0, item)

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasAdded() shouldBe true
        change.from shouldBe 0
        change.to shouldBe 1
    }

    "FxAggregateList lazy-snapshot fires RemoveChange on removeAt" {
        val item1 = audioItemRepo.create(1, "Song A")
        val item2 = audioItemRepo.create(2, "Song B")
        val playlist = playlistRepo.create(1)
        playlist.audioItems.addAll(listOf(item1, item2))

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        playlist.audioItems.addListener(ListChangeListener(changes::add))

        playlist.audioItems.removeAt(0)

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.removed.size shouldBe 1
    }

    "FxAggregateList lazy-snapshot fires SetChange on set" {
        val item1 = audioItemRepo.create(1, "Song A")
        val item2 = audioItemRepo.create(2, "Song B")
        val playlist = playlistRepo.create(1)
        playlist.audioItems.add(0, item1)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        playlist.audioItems.addListener(ListChangeListener(changes::add))

        playlist.audioItems[0] = item2

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasReplaced() shouldBe true
    }

    "FxAggregateList lazy-snapshot fires single AddChange on addAll" {
        val items =
            listOf(
                audioItemRepo.create(1, "A"),
                audioItemRepo.create(2, "B"),
                audioItemRepo.create(3, "C")
            )
        val playlist = playlistRepo.create(1)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        playlist.audioItems.addListener(ListChangeListener(changes::add))

        playlist.audioItems.addAll(items)

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasAdded() shouldBe true
        change.from shouldBe 0
        change.to shouldBe 3
    }

    "FxAggregateList lazy-snapshot fires RemoveChange on clear" {
        val item1 = audioItemRepo.create(1, "A")
        val item2 = audioItemRepo.create(2, "B")
        val playlist = playlistRepo.create(1)
        playlist.audioItems.addAll(listOf(item1, item2))

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        playlist.audioItems.addListener(ListChangeListener(changes::add))

        playlist.audioItems.clear()

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.removed.size shouldBe 2
    }

    "FxAggregateList lazy-snapshot fires ReplaceAllChange on setAll" {
        val item1 = audioItemRepo.create(1, "A")
        val item2 = audioItemRepo.create(2, "B")
        val playlist = playlistRepo.create(1)
        playlist.audioItems.add(0, item1)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        playlist.audioItems.addListener(ListChangeListener(changes::add))

        playlist.audioItems.setAll(item2)

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasReplaced() shouldBe true
    }

    "FxAggregateList lazy-snapshot fires MultiRemoveChange on removeAll" {
        val item1 = audioItemRepo.create(1, "A")
        val item2 = audioItemRepo.create(2, "B")
        val item3 = audioItemRepo.create(3, "C")
        val playlist = playlistRepo.create(1)
        playlist.audioItems.addAll(listOf(item1, item2, item3))

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        playlist.audioItems.addListener(ListChangeListener(changes::add))

        playlist.audioItems.removeAll(listOf(item1, item3))

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.from shouldBe 0
        change.removed shouldBe listOf(item1)
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.from shouldBe 1
        change.removed shouldBe listOf(item3)
        change.next() shouldBe false
    }

    "FxAggregateList lazy-snapshot retainAll removes non-matching" {
        val item1 = audioItemRepo.create(1, "A")
        val item2 = audioItemRepo.create(2, "B")
        val item3 = audioItemRepo.create(3, "C")
        val playlist = playlistRepo.create(1)
        playlist.audioItems.addAll(listOf(item1, item2, item3))

        playlist.audioItems.retainAll(listOf(item2))

        playlist.audioItems.size shouldBe 1
        playlist.audioItems[0].id shouldBe item2.id
    }

    "FxAggregateList lazy-snapshot remove range fires Change" {
        val items =
            listOf(
                audioItemRepo.create(1, "A"),
                audioItemRepo.create(2, "B"),
                audioItemRepo.create(3, "C")
            )
        val playlist = playlistRepo.create(1)
        playlist.audioItems.addAll(items)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        playlist.audioItems.addListener(ListChangeListener(changes::add))

        playlist.audioItems.remove(0, 2)

        playlist.audioItems.size shouldBe 1
        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.removedSize shouldBe 2
    }

    "FxAggregateList lazy-snapshot syncLocalCache is no-op" {
        val item = audioItemRepo.create(1, "Song A")
        val playlist = playlistRepo.create(1)
        playlist.audioItems.add(0, item)

        val fxCollection = playlist.audioItems as FxObservableCollection<*, *>
        fxCollection.syncLocalCache()

        playlist.audioItems.size shouldBe 1
    }

    "FxAggregateSet in lazy-snapshot mode with 10000 entities returns correct size and contains" {
        val items = (1..10_000).map { audioItemRepo.create(it, "Song $it") }
        val playlist = playlistRepo.create(1)
        playlist.relatedItems.addAll(items)

        playlist.relatedItems.size shouldBe 10_000
        playlist.relatedItems.contains(items[0]) shouldBe true
        playlist.relatedItems.contains(items[9_999]) shouldBe true
    }

    "FxAggregateSet lazy-snapshot fires added Change on add" {
        val item = audioItemRepo.create(1, "Song A")
        val playlist = playlistRepo.create(1)

        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        playlist.relatedItems.addListener(SetChangeListener(changes::add))

        playlist.relatedItems.add(item)

        changes.size shouldBe 1
        changes[0].wasAdded() shouldBe true
        changes[0].elementAdded.id shouldBe item.id
    }

    "FxAggregateSet lazy-snapshot fires removed Change on remove" {
        val item = audioItemRepo.create(1, "Song A")
        val playlist = playlistRepo.create(1)
        playlist.relatedItems.add(item)

        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        playlist.relatedItems.addListener(SetChangeListener(changes::add))

        playlist.relatedItems.remove(item)

        changes.size shouldBe 1
        changes[0].wasRemoved() shouldBe true
        changes[0].elementRemoved.id shouldBe item.id
    }

    "FxAggregateSet lazy-snapshot iterator resolves all elements" {
        val item1 = audioItemRepo.create(1, "A")
        val item2 = audioItemRepo.create(2, "B")
        val item3 = audioItemRepo.create(3, "C")
        val playlist = playlistRepo.create(1)
        playlist.relatedItems.addAll(listOf(item1, item2, item3))

        val collected = mutableListOf<AudioItem>()
        playlist.relatedItems.iterator().forEach { collected.add(it) }

        collected.size shouldBe 3
        collected.map { it.id }.toSet() shouldBe setOf(1, 2, 3)
    }

    "FxAggregateSet lazy-snapshot clear fires removed for each element" {
        val item1 = audioItemRepo.create(1, "A")
        val item2 = audioItemRepo.create(2, "B")
        val playlist = playlistRepo.create(1)
        playlist.relatedItems.addAll(listOf(item1, item2))

        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        playlist.relatedItems.addListener(SetChangeListener(changes::add))

        playlist.relatedItems.clear()

        changes.size shouldBe 2
        changes.all { it.wasRemoved() } shouldBe true
    }

    "FxAggregateSet lazy-snapshot retainAll removes non-matching" {
        val item1 = audioItemRepo.create(1, "A")
        val item2 = audioItemRepo.create(2, "B")
        val item3 = audioItemRepo.create(3, "C")
        val playlist = playlistRepo.create(1)
        playlist.relatedItems.addAll(listOf(item1, item2, item3))

        playlist.relatedItems.retainAll(listOf(item2))

        playlist.relatedItems.size shouldBe 1
        playlist.relatedItems.contains(item2) shouldBe true
    }

    "fxAggregateList factory creates lazy-snapshot list" {
        val proxy = fxAggregateList<Int, AudioItem>(lazySnapshot = true, dispatchToFxThread = false)
        proxy.shouldBeInstanceOf<FxAggregateList<Int, AudioItem>>()
        proxy.lazySnapshot shouldBe true
    }

    "fxAggregateSet factory creates lazy-snapshot set" {
        val proxy = fxAggregateSet<Int, AudioItem>(lazySnapshot = true, dispatchToFxThread = false)
        proxy.shouldBeInstanceOf<FxAggregateSet<Int, AudioItem>>()
        proxy.lazySnapshot shouldBe true
    }
})
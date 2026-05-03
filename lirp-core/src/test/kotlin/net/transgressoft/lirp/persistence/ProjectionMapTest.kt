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

import net.transgressoft.lirp.testing.Stress
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tests for [ProjectionMap], verifying grouping behavior, incremental auto-updates from
 * mutable aggregate sources, sorted key ordering, onChange callback, and lazy initialization semantics.
 */
@DisplayName("ProjectionMap")
internal class ProjectionMapTest : StringSpec({

    lateinit var ctx: LirpContext
    lateinit var trackRepo: AudioItemVolatileRepository
    lateinit var playlistRepo: AudioPlaylistVolatileRepository

    beforeEach {
        ctx = LirpContext()
        trackRepo = AudioItemVolatileRepository(ctx)
        playlistRepo = AudioPlaylistVolatileRepository(ctx)
    }

    afterEach {
        ctx.close()
    }

    "ProjectionMap groups entities by key extractor into buckets" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Jazz")
        val t3 = trackRepo.create(3, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id, t3.id)).also(playlistRepo::add)

        val itemsByTitle by ProjectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        itemsByTitle.size shouldBe 2
        itemsByTitle["Jazz"]!!.size shouldBe 2
        itemsByTitle["Rock"]!!.size shouldBe 1
    }

    "ProjectionMap builds initial state from source contents on first access" {
        val t1 = trackRepo.create(1, "Pop")
        val t2 = trackRepo.create(2, "Jazz")
        val t3 = trackRepo.create(3, "Pop")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id, t3.id)).also(playlistRepo::add)

        val itemsByTitle by ProjectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        itemsByTitle.size shouldBe 2
        itemsByTitle["Pop"]!!.size shouldBe 2
        itemsByTitle["Jazz"]!!.size shouldBe 1
    }

    "ProjectionMap auto-updates bucket when entity added to source" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
        projection.size shouldBe 2

        val t3 = trackRepo.create(3, "Jazz")
        playlist.audioItems.add(t3)

        projection["Jazz"]!!.size shouldBe 2
        projection["Jazz"]!! shouldContainExactlyInAnyOrder listOf(t1, t3)
    }

    "ProjectionMap auto-removes empty bucket when last entity removed from source" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
        projection.containsKey("Rock") shouldBe true

        playlist.audioItems.remove(t2)

        projection.containsKey("Rock") shouldBe false
        projection.size shouldBe 1
    }

    "ProjectionMap auto-updates bucket without removing on partial remove" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Jazz")
        val t3 = trackRepo.create(3, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id, t3.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        playlist.audioItems.remove(t1)

        projection.containsKey("Jazz") shouldBe true
        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!! shouldContainExactly listOf(t2)
    }

    "ProjectionMap removes entity from original bucket when grouping key changed before removal" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
        projection["Jazz"]!!.size shouldBe 1

        // Mutate the grouping field BEFORE removing from source
        (t1 as MutableAudioItem).title = "Classical"
        playlist.audioItems.remove(t1)

        // The entity should be removed from the original "Jazz" bucket via fallback search
        projection.containsKey("Jazz") shouldBe false
        projection.containsKey("Classical") shouldBe false
        projection.size shouldBe 1
        projection.containsKey("Rock") shouldBe true
    }

    "ProjectionMap keeps remaining bucket members when fallback removal leaves the bucket non-empty" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Jazz")
        val t3 = trackRepo.create(3, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id, t3.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
        projection["Jazz"]!!.size shouldBe 2

        // Mutate t1's grouping key, then remove from source. handleRemoved looks under "Classical",
        // misses, and falls back to removeFromAnyBucket which finds t1 under "Jazz" and rewrites the bucket
        // to [t2] — exercises the filtered.isNotEmpty() branch (would throw with CSLM if entry.setValue
        // were still in use, since the iterator entries are SimpleImmutableEntry).
        (t1 as MutableAudioItem).title = "Classical"
        playlist.audioItems.remove(t1)

        projection.containsKey("Classical") shouldBe false
        projection["Jazz"]!! shouldContainExactly listOf(t2)
        projection["Rock"]!! shouldContainExactly listOf(t3)
        projection.size shouldBe 2
    }

    "ProjectionMap keys are in natural sorted order" {
        val t1 = trackRepo.create(1, "Rock")
        val t2 = trackRepo.create(2, "Classical")
        val t3 = trackRepo.create(3, "Blues")
        val t4 = trackRepo.create(4, "Jazz")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id, t3.id, t4.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        projection.keys.toList() shouldContainExactly listOf("Blues", "Classical", "Jazz", "Rock")
    }

    "ProjectionMap auto-clears when source collection is cleared" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
        projection.size shouldBe 2

        playlist.audioItems.clear()

        projection.shouldBeEmpty()
    }

    "ProjectionMap fires onChange callback when projection changes on add" {
        val t1 = trackRepo.create(1, "Jazz")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id)).also(playlistRepo::add)
        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        // trigger initialization before registering callback so auto-subscription is active
        projection["Jazz"]!!.size shouldBe 1

        var callbackFiredCount = 0
        var lastMapSnapshot: Map<String, List<AudioItem>>? = null
        projection.onChange = { currentMap ->
            callbackFiredCount++
            lastMapSnapshot = currentMap
        }

        val t2 = trackRepo.create(2, "Rock")
        playlist.audioItems.add(t2)

        callbackFiredCount shouldBe 1
        lastMapSnapshot shouldNotBe null
        lastMapSnapshot!!["Rock"]!!.size shouldBe 1
    }

    "ProjectionMap fires onChange callback when projection changes on remove" {
        val t1 = trackRepo.create(1, "Jazz")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id)).also(playlistRepo::add)
        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        // trigger initialization then register callback
        projection["Jazz"]!!.size shouldBe 1

        var callbackFiredCount = 0
        projection.onChange = { callbackFiredCount++ }

        playlist.audioItems.remove(t1)

        callbackFiredCount shouldBe 1
    }

    "ProjectionMap with MutableAggregateSet auto-updates on add and remove" {
        val p1 = DefaultAudioPlaylist(1, "Jazz Playlist").also(playlistRepo::add)
        val p2 = DefaultAudioPlaylist(2, "Rock Playlist").also(playlistRepo::add)
        val parent = DefaultAudioPlaylist(10, "Parent", emptyList(), setOf(p1.id, p2.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, MutableAudioPlaylist>({ parent.playlists }, { it.name })
        projection.size shouldBe 2

        parent.playlists.remove(p2)

        projection.size shouldBe 1
        projection.containsKey("Rock Playlist") shouldBe false
    }

    "ProjectionMap entries contains all key-value pairs after population" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        val entries = projection.entries
        entries.size shouldBe 2
        entries.map { it.key }.toSet() shouldBe setOf("Jazz", "Rock")
        entries.first { it.key == "Jazz" }.value shouldContainExactly listOf(t1)
    }

    "ProjectionMap values contains all bucket lists" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Jazz")
        val t3 = trackRepo.create(3, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id, t3.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        val values = projection.values
        values.size shouldBe 2
        values.any { it.size == 2 } shouldBe true
        values.any { it.size == 1 } shouldBe true
    }

    "ProjectionMap containsValue returns true for a matching bucket" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        projection.containsValue(listOf(t1)) shouldBe true
        projection.containsValue(listOf(t2)) shouldBe true
        projection.containsValue(listOf(t1, t2)) shouldBe false
    }

    "ProjectionMap fires onChange callback on MutableAggregateSet remove" {
        val p1 = DefaultAudioPlaylist(1, "Jazz Playlist").also(playlistRepo::add)
        val p2 = DefaultAudioPlaylist(2, "Rock Playlist").also(playlistRepo::add)
        val parent = DefaultAudioPlaylist(10, "Parent", emptyList(), setOf(p1.id, p2.id)).also(playlistRepo::add)
        val projection = projectionMap<Int, String, MutableAudioPlaylist>({ parent.playlists }, { it.name })

        projection.size shouldBe 2

        var callbackFiredCount = 0
        projection.onChange = { callbackFiredCount++ }

        parent.playlists.remove(p1)

        callbackFiredCount shouldBe 1
        projection.containsKey("Jazz Playlist") shouldBe false
    }

    "ProjectionMap fires onChange callback on MutableAggregateSet add" {
        val p1 = DefaultAudioPlaylist(1, "Jazz Playlist").also(playlistRepo::add)
        val parent = DefaultAudioPlaylist(10, "Parent", emptyList(), setOf(p1.id)).also(playlistRepo::add)
        val projection = projectionMap<Int, String, MutableAudioPlaylist>({ parent.playlists }, { it.name })

        projection.size shouldBe 1

        var lastSnapshot: Map<String, List<MutableAudioPlaylist>>? = null
        projection.onChange = { lastSnapshot = it }

        val p2 = DefaultAudioPlaylist(2, "Rock Playlist").also(playlistRepo::add)
        parent.playlists.add(p2)

        lastSnapshot shouldNotBe null
        lastSnapshot!!.containsKey("Rock Playlist") shouldBe true
    }

    "ProjectionMap reflects writer state in reader iteration after writer completes" {
        val titles = listOf("Alpha", "Bravo", "Charlie", "Delta")
        val totalItems = 200
        val seedTracks = (1..totalItems).map { i -> trackRepo.create(i, titles[i % titles.size]) }
        val playlist = DefaultAudioPlaylist(1, "Test", emptyList()).also(playlistRepo::add)

        val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
        // Trigger init before writer starts so the source-callback subscription is live.
        projection.size shouldBe 0

        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(totalItems)

        // Reader runs as a coroutine so a writer failure trips latch.await(10s) instead of looping forever.
        val readerJob =
            launch(Dispatchers.Default) {
                while (latch.count > 0L) {
                    projection.keys.toList()
                    projection.entries.forEach { it.value.size }
                }
            }

        try {
            for (track in seedTracks) {
                executor.submit {
                    playlist.audioItems.add(track)
                    latch.countDown()
                }
            }

            latch.await(10, TimeUnit.SECONDS) shouldBe true
            projection.size shouldBe titles.size
            projection.values.sumOf { it.size } shouldBe totalItems
            projection.keys.toList() shouldContainExactly titles.sorted()
        } finally {
            readerJob.cancel()
            readerJob.join()
            executor.shutdownNow()
        }
    }

    "ProjectionMap iterates without ConcurrentModificationException under concurrent reader and writer stress"
        .config(tags = setOf(Stress)) {
            val totalMutations = 5000
            val readerIterations = 1000
            val seedSize = 100

            val seedTracks = (1..seedSize).map { i -> trackRepo.create(i, "Title-${i % 8}") }
            val playlist = DefaultAudioPlaylist(1, "Stress", seedTracks.map { it.id }).also(playlistRepo::add)

            val projection = projectionMap<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
            // Trigger init so the source-callback subscription is live before writers start.
            projection.size shouldBe 8

            shouldNotThrowAny {
                // Single writer coroutine: MutableAggregateList serializes mutations internally
                // via a ReentrantLock; concurrent writes from multiple threads are not supported.
                // The CME regression tripwire is on the reader side — a TreeMap revert causes
                // ConcurrentModificationException when the backing map is mutated by the writer
                // while the reader coroutine iterates projection.keys / projection.entries.
                val writerJob =
                    launch(Dispatchers.Default) {
                        repeat(totalMutations) { i ->
                            val extra = trackRepo.create(seedSize + i + 1, "Title-${i % 8}")
                            playlist.audioItems.add(extra)
                            playlist.audioItems.remove(extra)
                        }
                    }

                val readerJob =
                    launch(Dispatchers.Default) {
                        repeat(readerIterations) {
                            projection.keys.toList()
                            projection.entries.forEach { it.value.size }
                        }
                    }

                writerJob.join()
                readerJob.join()
            }
        }
})
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

import net.transgressoft.lirp.persistence.projection.ProjectionMap
import net.transgressoft.lirp.persistence.projection.multiKeyProjection
import net.transgressoft.lirp.persistence.projection.projection
import net.transgressoft.lirp.testing.Stress
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    lateinit var multiKeyRepo: MultiKeyAudioItemVolatileRepository
    lateinit var mkPlaylistRepo: MultiKeyAudioPlaylistRepo

    beforeEach {
        ctx = LirpContext()
        trackRepo = AudioItemVolatileRepository(ctx)
        playlistRepo = AudioPlaylistVolatileRepository(ctx)
        multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        mkPlaylistRepo = MultiKeyAudioPlaylistRepo(ctx)
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

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
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

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
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

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        playlist.audioItems.remove(t1)

        projection.containsKey("Jazz") shouldBe true
        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!! shouldContainExactly listOf(t2)
    }

    "ProjectionMap removes entity from original bucket when grouping key changed before removal" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
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

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
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

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        projection.keys.toList() shouldContainExactly listOf("Blues", "Classical", "Jazz", "Rock")
    }

    "ProjectionMap auto-clears when source collection is cleared" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
        projection.size shouldBe 2

        playlist.audioItems.clear()

        projection.shouldBeEmpty()
    }

    "ProjectionMap fires onChange callback when projection changes on add" {
        val t1 = trackRepo.create(1, "Jazz")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id)).also(playlistRepo::add)
        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        // trigger initialization before registering callback so auto-subscription is active
        projection["Jazz"]!!.size shouldBe 1

        var callbackFiredCount = 0
        var lastMapSnapshot: Map<String, List<AudioItem>>? = null
        projection.addOnChangeListener { currentMap ->
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
        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        // trigger initialization then register callback
        projection["Jazz"]!!.size shouldBe 1

        var callbackFiredCount = 0
        projection.addOnChangeListener { callbackFiredCount++ }

        playlist.audioItems.remove(t1)

        callbackFiredCount shouldBe 1
    }

    "ProjectionMap two independent addOnBucketsChangedListener registrations both fire for one mutation" {
        val t1 = trackRepo.create(1, "Jazz")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id)).also(playlistRepo::add)
        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        projection["Jazz"]!!.size shouldBe 1

        var firstFired = 0
        var secondFired = 0
        projection.addOnBucketsChangedListener { firstFired++ }
        projection.addOnBucketsChangedListener { secondFired++ }

        val t2 = trackRepo.create(2, "Rock")
        playlist.audioItems.add(t2)

        firstFired shouldBe 1
        secondFired shouldBe 1
    }

    "ProjectionMap closing one addOnBucketsChangedListener registration leaves the other active" {
        val t1 = trackRepo.create(1, "Jazz")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id)).also(playlistRepo::add)
        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        projection["Jazz"]!!.size shouldBe 1

        var firstFired = 0
        var secondFired = 0
        val firstReg = projection.addOnBucketsChangedListener { firstFired++ }
        projection.addOnBucketsChangedListener { secondFired++ }

        firstReg.close()

        val t2 = trackRepo.create(2, "Rock")
        playlist.audioItems.add(t2)

        firstFired shouldBe 0
        secondFired shouldBe 1
    }

    "ProjectionMap with MutableAggregateSet auto-updates on add and remove" {
        val p1 = DefaultAudioPlaylist(1, "Jazz Playlist").also(playlistRepo::add)
        val p2 = DefaultAudioPlaylist(2, "Rock Playlist").also(playlistRepo::add)
        val parent = DefaultAudioPlaylist(10, "Parent", emptyList(), setOf(p1.id, p2.id)).also(playlistRepo::add)

        val projection = projection<Int, String, MutableAudioPlaylist>({ parent.playlists }, { it.name })
        projection.size shouldBe 2

        parent.playlists.remove(p2)

        projection.size shouldBe 1
        projection.containsKey("Rock Playlist") shouldBe false
    }

    "ProjectionMap entries contains all key-value pairs after population" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

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

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        val values = projection.values
        values.size shouldBe 2
        values.any { it.size == 2 } shouldBe true
        values.any { it.size == 1 } shouldBe true
    }

    "ProjectionMap containsValue returns true for a matching bucket" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })

        projection.containsValue(listOf(t1)) shouldBe true
        projection.containsValue(listOf(t2)) shouldBe true
        projection.containsValue(listOf(t1, t2)) shouldBe false
    }

    "ProjectionMap fires onChange callback on MutableAggregateSet remove" {
        val p1 = DefaultAudioPlaylist(1, "Jazz Playlist").also(playlistRepo::add)
        val p2 = DefaultAudioPlaylist(2, "Rock Playlist").also(playlistRepo::add)
        val parent = DefaultAudioPlaylist(10, "Parent", emptyList(), setOf(p1.id, p2.id)).also(playlistRepo::add)
        val projection = projection<Int, String, MutableAudioPlaylist>({ parent.playlists }, { it.name })

        projection.size shouldBe 2

        var callbackFiredCount = 0
        projection.addOnChangeListener { callbackFiredCount++ }

        parent.playlists.remove(p1)

        callbackFiredCount shouldBe 1
        projection.containsKey("Jazz Playlist") shouldBe false
    }

    "ProjectionMap fires onChange callback on MutableAggregateSet add" {
        val p1 = DefaultAudioPlaylist(1, "Jazz Playlist").also(playlistRepo::add)
        val parent = DefaultAudioPlaylist(10, "Parent", emptyList(), setOf(p1.id)).also(playlistRepo::add)
        val projection = projection<Int, String, MutableAudioPlaylist>({ parent.playlists }, { it.name })

        projection.size shouldBe 1

        var lastSnapshot: Map<String, List<MutableAudioPlaylist>>? = null
        projection.addOnChangeListener { lastSnapshot = it }

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

        val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
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

    "ProjectionMap with valueTransform produces Map<PK, V> with correct transformed values for each bucket" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Jazz")
        val t3 = trackRepo.create(3, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id, t3.id)).also(playlistRepo::add)

        val transformed =
            projection<Int, String, AudioItem, String>({ playlist.audioItems }, { it.title }) { pk, items ->
                "$pk:${items.size}"
            }

        transformed["Jazz"] shouldBe "Jazz:2"
        transformed["Rock"] shouldBe "Rock:1"
        transformed.size shouldBe 2
    }

    "ProjectionMap with valueTransform recomputes only the affected bucket on a delta" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        var jazzTransformCount = 0
        var rockTransformCount = 0
        val transformed =
            projection<Int, String, AudioItem, String>({ playlist.audioItems }, { it.title }) { pk, items ->
                if (pk == "Jazz") jazzTransformCount++ else rockTransformCount++
                "$pk:${items.size}"
            }

        // Trigger initialization — both buckets computed once
        transformed["Jazz"] shouldBe "Jazz:1"
        transformed["Rock"] shouldBe "Rock:1"
        val jazzCountAfterInit = jazzTransformCount
        val rockCountAfterInit = rockTransformCount

        // Add a Jazz track — only Jazz bucket should be recomputed
        val t3 = trackRepo.create(3, "Jazz")
        playlist.audioItems.add(t3)

        transformed["Jazz"] shouldBe "Jazz:2"
        // Jazz transform invoked one more time, Rock not re-invoked
        jazzTransformCount shouldBe jazzCountAfterInit + 1
        rockTransformCount shouldBe rockCountAfterInit
    }

    "ProjectionMap with valueTransform removes emptied bucket key from transformed view" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id)).also(playlistRepo::add)

        val transformed =
            projection<Int, String, AudioItem, String>({ playlist.audioItems }, { it.title }) { pk, items ->
                "$pk:${items.size}"
            }

        transformed.containsKey("Rock") shouldBe true

        playlist.audioItems.remove(t2)

        transformed.containsKey("Rock") shouldBe false
        transformed.size shouldBe 1
        transformed["Jazz"] shouldBe "Jazz:1"
    }

    // -------------------------------------------------------------------------
    // Multi-key projection (PROJ-04) — aggregate source
    // -------------------------------------------------------------------------

    "MultiKeyProjectionMap places entity in every genre bucket" {
        val item1 = multiKeyRepo.create(1, "Track One", setOf("Rock", "Jazz"))
        val item2 = multiKeyRepo.create(2, "Track Two", setOf("Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id, item2.id))
        mkPlaylistRepo.add(mkPlaylist)

        val projection = multiKeyProjection<Int, String, MutableMultiKeyAudioItem>({ mkPlaylist.audioItems }) { it.genres }

        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 2
        projection["Rock"]!!.first().id shouldBe 1
    }

    "MultiKeyProjectionMap removes entity from all genre buckets when removed from source" {
        val item1 = multiKeyRepo.create(1, "Track One", setOf("Rock", "Jazz"))
        val item2 = multiKeyRepo.create(2, "Track Two", setOf("Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id, item2.id))
        mkPlaylistRepo.add(mkPlaylist)

        val projection = multiKeyProjection<Int, String, MutableMultiKeyAudioItem>({ mkPlaylist.audioItems }) { it.genres }
        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 2

        // Remove the dual-genre item from the source
        mkPlaylist.audioItems.remove(item1)

        projection.containsKey("Rock") shouldBe false
        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!!.first().id shouldBe 2
    }

    "MultiKeyProjectionMap auto-clears and rebuilds when source is cleared" {
        val item1 = multiKeyRepo.create(1, "Track One", setOf("Rock", "Jazz"))
        val item2 = multiKeyRepo.create(2, "Track Two", setOf("Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id, item2.id))
        mkPlaylistRepo.add(mkPlaylist)

        val projection = multiKeyProjection<Int, String, MutableMultiKeyAudioItem>({ mkPlaylist.audioItems }) { it.genres }
        projection.size shouldBe 2

        mkPlaylist.audioItems.clear()

        projection.isEmpty() shouldBe true
    }

    "MultiKeyProjectionMap places entity with empty genres in zero buckets" {
        val item1 = multiKeyRepo.create(1, "No Genre Track", emptySet())
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id))
        mkPlaylistRepo.add(mkPlaylist)

        val projection = multiKeyProjection<Int, String, MutableMultiKeyAudioItem>({ mkPlaylist.audioItems }) { it.genres }

        projection.isEmpty() shouldBe true
    }

    "MultiKeyProjectionMap exposes correct read-only accessors" {
        val item1 = multiKeyRepo.create(1, "Track One", setOf("Rock", "Jazz"))
        val item2 = multiKeyRepo.create(2, "Track Two", setOf("Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id, item2.id))
        mkPlaylistRepo.add(mkPlaylist)

        val projection = multiKeyProjection<Int, String, MutableMultiKeyAudioItem>({ mkPlaylist.audioItems }) { it.genres }

        projection.size shouldBe 2
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Pop") shouldBe false
        projection.containsValue(projection["Jazz"]!!) shouldBe true
        projection.keys.toSet() shouldBe setOf("Rock", "Jazz")
        projection.values.sumOf { it.size } shouldBe 3 // Rock:1 + Jazz:2
        projection.entries.size shouldBe 2
    }

    "multiKeyProjection with valueTransform produces Map<PK, V> with transformed bucket values" {
        val item1 = multiKeyRepo.create(1, "Track One", setOf("Rock", "Jazz"))
        val item2 = multiKeyRepo.create(2, "Track Two", setOf("Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id, item2.id))
        mkPlaylistRepo.add(mkPlaylist)

        val transformed =
            multiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                { mkPlaylist.audioItems },
                { it.genres }
            ) { pk, items ->
                "$pk:${items.size}"
            }

        transformed["Rock"] shouldBe "Rock:1"
        transformed["Jazz"] shouldBe "Jazz:2"
        transformed.size shouldBe 2
        transformed.containsKey("Rock") shouldBe true
        transformed.containsKey("Pop") shouldBe false
        transformed.containsValue("Rock:1") shouldBe true
        transformed.isEmpty() shouldBe false
        transformed.keys.toSet() shouldBe setOf("Rock", "Jazz")
        transformed.values.toSet() shouldBe setOf("Rock:1", "Jazz:2")
    }

    "multiKeyProjection with valueTransform removes emptied genre bucket from transformed view" {
        val item1 = multiKeyRepo.create(1, "Track One", setOf("Rock", "Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id))
        mkPlaylistRepo.add(mkPlaylist)

        val transformed =
            multiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                { mkPlaylist.audioItems },
                { it.genres }
            ) { pk, items ->
                "$pk:${items.size}"
            }

        transformed.containsKey("Rock") shouldBe true
        transformed.containsKey("Jazz") shouldBe true

        mkPlaylist.audioItems.remove(item1)

        transformed.containsKey("Rock") shouldBe false
        transformed.containsKey("Jazz") shouldBe false
        transformed.isEmpty() shouldBe true
    }

    "MultiKeyProjectionMap reconciles key-set delta when an already-bucketed entity is re-added" {
        val item = multiKeyRepo.create(1, "Track One", setOf("Rock", "Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item.id))
        mkPlaylistRepo.add(mkPlaylist)

        val projection = multiKeyProjection<Int, String, MutableMultiKeyAudioItem>({ mkPlaylist.audioItems }) { it.genres }
        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 1

        // Change the entity's key set in place, then re-add it to the (list-semantics) source so the added
        // callback delivers an already-indexed id → routes through applyKeyDelta with {Rock,Jazz}→{Rock,Indie}.
        item.genres = setOf("Rock", "Indie")
        mkPlaylist.audioItems.add(item)

        // Rock retained, Jazz removed (reverse index cleaned, bucket gone), Indie added.
        projection["Rock"]!!.any { it.id == item.id } shouldBe true
        projection.containsKey("Jazz") shouldBe false
        projection["Indie"]!!.size shouldBe 1
        projection["Indie"]!!.first().id shouldBe item.id
    }

    "MultiKeyProjectionMap re-add reconcile replaces unchanged-key content and does not orphan the entity" {
        val item = multiKeyRepo.create(1, "Old Title", setOf("Rock", "Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item.id))
        mkPlaylistRepo.add(mkPlaylist)

        val projection = multiKeyProjection<Int, String, MutableMultiKeyAudioItem>({ mkPlaylist.audioItems }) { it.genres }
        projection["Rock"]!!.first().title shouldBe "Old Title"

        // Mutate a non-key field while keeping a key (Rock) unchanged so the unchanged-bucket branch
        // routes through replaceInBucketSilent with a real change; Jazz is dropped from the key set.
        item.title = "New Title"
        item.genres = setOf("Rock")
        mkPlaylist.audioItems.add(item)

        projection["Rock"]!!.size shouldBe 1
        projection["Rock"]!!.first().title shouldBe "New Title"
        projection.containsKey("Jazz") shouldBe false
    }

    "MultiKeyProjectionMap re-add reconcile to empty key set removes the entity from all buckets" {
        val item = multiKeyRepo.create(1, "Track One", setOf("Rock", "Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item.id))
        mkPlaylistRepo.add(mkPlaylist)

        val projection = multiKeyProjection<Int, String, MutableMultiKeyAudioItem>({ mkPlaylist.audioItems }) { it.genres }
        projection.size shouldBe 2

        item.genres = emptySet()
        mkPlaylist.audioItems.add(item)

        projection.isEmpty() shouldBe true
    }

    "projection valueTransform replays current entries as adds when a listener registers" {
        val t1 = trackRepo.create(1, "Jazz")
        val t2 = trackRepo.create(2, "Jazz")
        val t3 = trackRepo.create(3, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id, t2.id, t3.id)).also(playlistRepo::add)

        val transformed =
            projection<Int, String, AudioItem, String>({ playlist.audioItems }, { it.title }) { pk, items ->
                "$pk:${items.size}"
            }

        val replayed = mutableMapOf<String, Pair<String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { replayed[it.key] = it.oldValue to it.newValue }
        }

        // Each current entry is replayed as an add (oldValue == null) so a late subscriber sees full state.
        replayed.keys shouldBe setOf("Jazz", "Rock")
        replayed["Jazz"] shouldBe (null to "Jazz:2")
        replayed["Rock"] shouldBe (null to "Rock:1")
    }

    "projection valueTransform emits add, replace and remove entry changes on deltas" {
        val t1 = trackRepo.create(1, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id)).also(playlistRepo::add)

        val transformed =
            projection<Int, String, AudioItem, String>({ playlist.audioItems }, { it.title }) { pk, items ->
                "$pk:${items.size}"
            }

        val changesLog = mutableListOf<Triple<String, String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { changesLog += Triple(it.key, it.oldValue, it.newValue) }
        }
        changesLog.clear() // drop the initial replay of the seeded "Rock" bucket

        val t2 = trackRepo.create(2, "Jazz")
        playlist.audioItems.add(t2) // add a new bucket

        val t3 = trackRepo.create(3, "Rock")
        playlist.audioItems.add(t3) // recompute an existing bucket

        playlist.audioItems.remove(t2) // empty and drop the Jazz bucket

        changesLog shouldContainExactly
            listOf(
                Triple("Jazz", null, "Jazz:1"),
                Triple("Rock", "Rock:1", "Rock:2"),
                Triple("Jazz", "Jazz:1", null)
            )
    }

    "projection valueTransform stops delivering entry changes after the listener handle is closed" {
        val t1 = trackRepo.create(1, "Rock")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(t1.id)).also(playlistRepo::add)

        val transformed =
            projection<Int, String, AudioItem, String>({ playlist.audioItems }, { it.title }) { pk, items ->
                "$pk:${items.size}"
            }

        val changesLog = mutableListOf<Triple<String, String?, String?>>()
        val handle =
            transformed.addOnEntriesChangedListener { changes ->
                changes.forEach { changesLog += Triple(it.key, it.oldValue, it.newValue) }
            }
        changesLog.clear()

        handle.close()
        val t2 = trackRepo.create(2, "Jazz")
        playlist.audioItems.add(t2)

        changesLog shouldBe emptyList()
    }

    "multiKeyProjection valueTransform replays current entries as adds when a listener registers" {
        val item1 = multiKeyRepo.create(1, "Track One", setOf("Rock", "Jazz"))
        val item2 = multiKeyRepo.create(2, "Track Two", setOf("Jazz"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id, item2.id))
        mkPlaylistRepo.add(mkPlaylist)

        val transformed =
            multiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                { mkPlaylist.audioItems },
                { it.genres }
            ) { pk, items -> "$pk:${items.size}" }

        val replayed = mutableMapOf<String, Pair<String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { replayed[it.key] = it.oldValue to it.newValue }
        }

        // Each current entry is replayed as an add (oldValue == null) so a late subscriber sees full state.
        replayed.keys shouldBe setOf("Rock", "Jazz")
        replayed["Rock"] shouldBe (null to "Rock:1")
        replayed["Jazz"] shouldBe (null to "Jazz:2")
    }

    "multiKeyProjection valueTransform emits add, replace and remove entry changes on deltas" {
        val item1 = multiKeyRepo.create(1, "Track One", setOf("Rock"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id))
        mkPlaylistRepo.add(mkPlaylist)

        val transformed =
            multiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                { mkPlaylist.audioItems },
                { it.genres }
            ) { pk, items -> "$pk:${items.size}" }

        val changesLog = mutableListOf<Triple<String, String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { changesLog += Triple(it.key, it.oldValue, it.newValue) }
        }
        changesLog.clear() // drop the initial replay of the seeded "Rock" bucket

        val item2 = multiKeyRepo.create(2, "Track Two", setOf("Jazz"))
        mkPlaylist.audioItems.add(item2) // add a new bucket

        val item3 = multiKeyRepo.create(3, "Track Three", setOf("Rock"))
        mkPlaylist.audioItems.add(item3) // recompute an existing bucket

        mkPlaylist.audioItems.remove(item2) // empty and drop the Jazz bucket

        changesLog shouldContainExactly
            listOf(
                Triple("Jazz", null, "Jazz:1"),
                Triple("Rock", "Rock:1", "Rock:2"),
                Triple("Jazz", "Jazz:1", null)
            )
    }

    "multiKeyProjection valueTransform stops delivering entry changes after the listener handle is closed" {
        val item1 = multiKeyRepo.create(1, "Track One", setOf("Rock"))
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id))
        mkPlaylistRepo.add(mkPlaylist)

        val transformed =
            multiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                { mkPlaylist.audioItems },
                { it.genres }
            ) { pk, items -> "$pk:${items.size}" }

        val changesLog = mutableListOf<Triple<String, String?, String?>>()
        val handle =
            transformed.addOnEntriesChangedListener { changes ->
                changes.forEach { changesLog += Triple(it.key, it.oldValue, it.newValue) }
            }
        changesLog.clear()

        handle.close()
        val item2 = multiKeyRepo.create(2, "Track Two", setOf("Jazz"))
        mkPlaylist.audioItems.add(item2)

        changesLog shouldBe emptyList()
    }

    "multiKeyProjection valueTransform fires no delta when entity key-extractor returns empty set" {
        val item1 = multiKeyRepo.create(1, "No Genre Track", emptySet())
        val mkPlaylist = MultiKeyAudioPlaylist(1, "Test", listOf(item1.id))
        mkPlaylistRepo.add(mkPlaylist)

        val transformed =
            multiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                { mkPlaylist.audioItems },
                { it.genres }
            ) { pk, items -> "$pk:${items.size}" }

        var listenerCallCount = 0
        transformed.addOnEntriesChangedListener { batch -> listenerCallCount += batch.size }

        // The replay on register must be zero since no bucket was created.
        listenerCallCount shouldBe 0

        // Adding another entity with empty genres also fires no delta.
        val item2 = multiKeyRepo.create(2, "Another No Genre Track", emptySet())
        mkPlaylist.audioItems.add(item2)

        listenerCallCount shouldBe 0
    }

    "projection valueTransform observes each bucket create exactly once with replay never preceded by a delta under stress"
        .config(tags = setOf(Stress)) {
            val seedSize = 40
            val addCount = 400

            val seedTracks = (1..seedSize).map { i -> trackRepo.create(i, "Seed-$i") }
            val playlist = DefaultAudioPlaylist(1, "Stress", seedTracks.map { it.id }).also(playlistRepo::add)

            val transformed =
                projection<Int, String, AudioItem, String>({ playlist.audioItems }, { it.title }) { pk, items ->
                    "$pk:${items.size}"
                }
            transformed.size shouldBe seedSize

            val perKeyAddObservations = ConcurrentHashMap<String, AtomicInteger>()
            val seenAsAdd = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            val sawDeltaBeforeReplay = AtomicBoolean(false)

            // The MutableAggregateList serializes mutations internally, so a single writer adds new
            // distinct-title buckets while a registrar registers a listener concurrently. Each
            // bucket's transform delta fires synchronously on the writer thread under the cache lock;
            // registration replays under the same lock — so a new listener must see each bucket as a
            // single add (replay or first delta) and never a replace/remove before its replay add.
            val registrarStarted = CountDownLatch(1)
            val writer =
                Thread {
                    registrarStarted.await()
                    for (i in 1..addCount) {
                        playlist.audioItems.add(trackRepo.create(seedSize + i, "New-$i"))
                    }
                }
            val registrar =
                Thread {
                    registrarStarted.countDown()
                    transformed.addOnEntriesChangedListener { changes ->
                        for (change in changes) {
                            if (change.oldValue == null) {
                                seenAsAdd.add(change.key)
                                perKeyAddObservations.computeIfAbsent(change.key) { AtomicInteger(0) }.incrementAndGet()
                            } else if (change.key !in seenAsAdd) {
                                sawDeltaBeforeReplay.set(true)
                            }
                        }
                    }
                }

            writer.start()
            registrar.start()
            writer.join()
            registrar.join()

            sawDeltaBeforeReplay.get() shouldBe false
            // Every seed bucket and every added bucket is observed as an add exactly once.
            perKeyAddObservations.size shouldBe seedSize + addCount
            perKeyAddObservations.values.all { it.get() == 1 } shouldBe true
        }

    "ProjectionMap iterates without ConcurrentModificationException under concurrent reader and writer stress"
        .config(tags = setOf(Stress)) {
            val totalMutations = 5000
            val readerIterations = 1000
            val seedSize = 100

            val seedTracks = (1..seedSize).map { i -> trackRepo.create(i, "Title-${i % 8}") }
            val playlist = DefaultAudioPlaylist(1, "Stress", seedTracks.map { it.id }).also(playlistRepo::add)

            val projection = projection<Int, String, AudioItem>({ playlist.audioItems }, { it.title })
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
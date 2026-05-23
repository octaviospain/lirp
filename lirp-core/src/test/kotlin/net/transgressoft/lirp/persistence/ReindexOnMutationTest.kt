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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Reactive test entity with a mutable hash-indexed [label] and a mutable sorted-indexed [score].
 *
 * Both properties are reactive (backed by [reactiveProperty]) so mutation events fire when they
 * are reassigned. The hand-written [ReactiveIndexEntity_LirpIndexAccessor] registers the index
 * entries using the naming convention expected by
 * [net.transgressoft.lirp.persistence.RegistryBase.discoverIndexes].
 */
class ReactiveIndexEntity(
    override val id: Int,
    initialLabel: String,
    initialScore: Int
) : ReactiveEntityBase<Int, ReactiveIndexEntity>() {
    var label: String by reactiveProperty(initialLabel)
    var score: Int by reactiveProperty(initialScore)

    override val uniqueId: String get() = "reactive-index-$id"

    override fun clone(): ReactiveIndexEntity = ReactiveIndexEntity(id, label, score)
}

/**
 * Hand-written [LirpIndexAccessor] for [ReactiveIndexEntity].
 *
 * Registers a hash-bucketed index on [ReactiveIndexEntity.label] and a sorted-bucketed index on
 * [ReactiveIndexEntity.score], matching the two bucket kinds exercised by [ReindexOnMutationTest].
 */
@Suppress("ClassName")
class `ReactiveIndexEntity_LirpIndexAccessor` : LirpIndexAccessor<ReactiveIndexEntity> {
    override val entries: List<IndexEntry<ReactiveIndexEntity>> =
        listOf(
            IndexEntry("label") { it.label },
            IndexEntry("score", "score", sorted = true) { it.score }
        )
}

/**
 * Minimal [PersistentRepositoryBase] subclass used for reindex tests.
 *
 * [loadFromStore] returns an empty map (no persistent backing store). [writePending] is a no-op:
 * the test verifies the in-memory index state only.
 */
private open class ReactiveIndexRepo :
    PersistentRepositoryBase<Int, ReactiveIndexEntity>(name = "ReactiveIndexRepo", loadOnInit = false) {
    init {
        load()
    }

    override fun loadFromStore(): Map<Int, ReactiveIndexEntity> = emptyMap()

    override fun writePending(
        inserts: List<ReactiveIndexEntity>,
        updates: List<PendingUpdate<Int, ReactiveIndexEntity>>,
        deletes: List<Pair<Int, Long?>>,
        hadClear: Boolean
    ) {
        // No-op: only in-memory index state is under test.
    }
}

// Subclass whose onEntityMutated records the bucket-membership state visible from the hook.
// If reindex ran before the hook, the hook sees the entity in the NEW bucket and absent from the OLD.
private class OrderProbeRepo : ReactiveIndexRepo() {
    @Volatile var observedInNewBucket: Boolean? = null

    @Volatile var observedInOldBucket: Boolean? = null

    override fun onEntityMutated(event: net.transgressoft.lirp.event.MutationEvent<Int, ReactiveIndexEntity>) {
        observedInNewBucket = findByIndex("label", "after").any { it.id == event.newEntity.id }
        observedInOldBucket = findByIndex("label", "before").any { it.id == event.newEntity.id }
    }
}

/**
 * Tests that secondary indexes are kept in sync with reactive property mutations for entities
 * backed by [PersistentRepositoryBase].
 *
 * Each test adds an entity, mutates an `@Indexed` property, and then verifies that:
 * - The old index bucket no longer contains the entity.
 * - The new index bucket contains the entity.
 *
 * The [reactiveScope] extension wires an [kotlinx.coroutines.test.UnconfinedTestDispatcher] so
 * that coroutine-dispatched mutation handlers execute synchronously during the test.
 */
@DisplayName("ReindexOnMutation")
internal class ReindexOnMutationTest : StringSpec({

    reactiveScope()

    "[RegistryBase] reindexes hash-indexed property on reactive mutation" {
        val repo = ReactiveIndexRepo()
        try {
            val entity = ReactiveIndexEntity(1, "alpha", 10)
            repo.add(entity)

            // Before mutation: old bucket populated.
            repo.findByIndex("label", "alpha") shouldContain entity
            repo.findByIndex("label", "beta").shouldBeEmpty()

            entity.label = "beta"

            // After mutation: entity moved from "alpha" bucket to "beta" bucket.
            repo.findByIndex("label", "alpha") shouldNotContain entity
            repo.findByIndex("label", "beta") shouldContain entity
        } finally {
            repo.close()
        }
    }

    "[RegistryBase] reindexes sorted-indexed property on reactive mutation" {
        val repo = ReactiveIndexRepo()
        try {
            val entity = ReactiveIndexEntity(2, "x", 30)
            repo.add(entity)

            repo.findByIndex("score", 30) shouldContain entity
            repo.findByIndex("score", 99).shouldBeEmpty()

            entity.score = 99

            repo.findByIndex("score", 30) shouldNotContain entity
            repo.findByIndex("score", 99) shouldContain entity
        } finally {
            repo.close()
        }
    }

    "[RegistryBase] reindexes before invoking the onEntityMutated subclass hook" {
        // Regression: reindex must run BEFORE the subclass hook so a throwing override does not
        // leave the secondary index stale. Verifying the hook observes the post-reindex state is
        // a clean proxy for the ordering without needing to actually throw from the hook.
        val repo = OrderProbeRepo()
        try {
            val entity = ReactiveIndexEntity(99, "before", 7)
            repo.add(entity)
            entity.label = "after"

            repo.observedInNewBucket shouldBe true
            repo.observedInOldBucket shouldBe false
        } finally {
            repo.close()
        }
    }

    "[RegistryBase] reindexes hash- and sorted-indexed properties on reactive mutation" {
        val repo = ReactiveIndexRepo()
        try {
            val e1 = ReactiveIndexEntity(10, "cat-a", 10)
            val e2 = ReactiveIndexEntity(20, "cat-b", 20)
            repo.add(e1)
            repo.add(e2)

            // Mutate both indexed properties on e1.
            e1.label = "cat-b"
            e1.score = 20

            // e1 must leave cat-a/10 buckets and join cat-b/20 buckets alongside e2.
            repo.findByIndex("label", "cat-a") shouldNotContain e1
            repo.findByIndex("label", "cat-b") shouldContain e1
            repo.findByIndex("label", "cat-b") shouldContain e2
            repo.findByIndex("score", 10) shouldNotContain e1
            repo.findByIndex("score", 20) shouldContain e1
            repo.findByIndex("score", 20) shouldContain e2
        } finally {
            repo.close()
        }
    }
})
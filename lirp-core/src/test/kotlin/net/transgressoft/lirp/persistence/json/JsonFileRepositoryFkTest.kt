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

package net.transgressoft.lirp.persistence.json

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.persistence.Aggregate
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.LirpDeserializationException
import net.transgressoft.lirp.persistence.LirpRepository
import net.transgressoft.lirp.persistence.VolatileRepository
import net.transgressoft.lirp.persistence.mutableAggregateSet
import net.transgressoft.lirp.persistence.optionalAggregate
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Tests for [JsonFileRepository] foreign-key reconciliation behavior driven by [JsonFkPolicy].
 *
 * Verifies the silent-reconciliation contract: when a parent's `@Aggregate`
 * reference points to an entity that is missing from its registry at load time,
 * [JsonFkPolicy.LOG_AND_RECONCILE] (the default) drops dangling collection IDs and nulls
 * dangling nullable scalar refs without emitting `CrudEvent.UPDATE` and without bumping
 * `@Version`. [JsonFkPolicy.STRICT] surfaces the same condition as a
 * [LirpDeserializationException].
 */
internal class JsonFileRepositoryFkTest : StringSpec({

    val reactive = reactiveScope()

    "JsonFileRepository drops dangling collection IDs on load" {
        val ctx1 = LirpContext()
        val tagFile = tempfile("fk-tag-repo", ".json").also { it.deleteOnExit() }
        val itemFile = tempfile("fk-item-repo", ".json").also { it.deleteOnExit() }
        FkTagJsonRepo(ctx1, tagFile, 50L).apply {
            add(FkTag(10))
            add(FkTag(11))
        }
        FkItemJsonRepo(ctx1, itemFile, 50L).apply {
            add(FkItem(1, setOf(10, 99)))
        }
        reactive.advance()
        ctx1.close()

        // Reload with only tag 10 present (tag 11 still in file but tag 99 was never added).
        val ctx2 = LirpContext()
        val tagRepo2 = FkTagJsonRepo(ctx2, tagFile, 50L)
        // Remove tag 11 so tags become a strict subset of what item references
        tagRepo2.findById(11).ifPresent { tagRepo2.remove(it) }

        val updateEvents = AtomicInteger(0)
        val itemRepo2 =
            FkItemJsonRepo(ctx2, itemFile, 50L, loadOnInit = false) { event ->
                if (event.type == CrudEvent.Type.UPDATE) updateEvents.incrementAndGet()
            }
        itemRepo2.load()

        reactive.advance()
        val item = itemRepo2.findById(1).get()
        item.tags.referenceIds shouldBe setOf(10)
        updateEvents.get() shouldBe 0

        ctx2.close()
    }

    "JsonFileRepository nulls dangling single-entity scalar on load" {
        val ctx1 = LirpContext()
        val childFile = tempfile("fk-child-repo", ".json").also { it.deleteOnExit() }
        val parentFile = tempfile("fk-parent-repo", ".json").also { it.deleteOnExit() }
        FkChildJsonRepo(ctx1, childFile, 50L).apply { add(FkChild(5)) }
        FkParentJsonRepo(ctx1, parentFile, 50L).apply { add(FkParent(100, 5)) }
        reactive.advance()
        ctx1.close()

        // Reload, but remove child 5 so the parent has a dangling reference
        val ctx2 = LirpContext()
        val childRepo2 = FkChildJsonRepo(ctx2, childFile, 50L)
        childRepo2.findById(5).ifPresent { childRepo2.remove(it) }

        val updateEvents = AtomicInteger(0)
        val parentRepo2 =
            FkParentJsonRepo(ctx2, parentFile, 50L, loadOnInit = false) { event ->
                if (event.type == CrudEvent.Type.UPDATE) updateEvents.incrementAndGet()
            }
        parentRepo2.load()

        reactive.advance()
        val parent = parentRepo2.findById(100).get()
        parent.childId shouldBe null
        updateEvents.get() shouldBe 0

        ctx2.close()
    }

    "JsonFileRepository leaves version unchanged after reconcile" {
        val ctx1 = LirpContext()
        val childFile = tempfile("fk-version-child", ".json").also { it.deleteOnExit() }
        val parentFile = tempfile("fk-version-parent", ".json").also { it.deleteOnExit() }
        FkChildJsonRepo(ctx1, childFile, 50L).apply { add(FkChild(7)) }
        FkParentJsonRepo(ctx1, parentFile, 50L).apply { add(FkParent(200, 7)) }
        reactive.advance()
        ctx1.close()

        val ctx2 = LirpContext()
        val childRepo2 = FkChildJsonRepo(ctx2, childFile, 50L)
        childRepo2.findById(7).ifPresent { childRepo2.remove(it) }
        val parentRepo2 = FkParentJsonRepo(ctx2, parentFile, 50L)

        reactive.advance()
        val parent = parentRepo2.findById(200).get()
        parent.childId shouldBe null
        parent.version shouldBe 0L

        ctx2.close()
    }

    "JsonFileRepository STRICT throws on dangling collection ID" {
        val ctx1 = LirpContext()
        val tagFile = tempfile("fk-strict-tag", ".json").also { it.deleteOnExit() }
        val itemFile = tempfile("fk-strict-item", ".json").also { it.deleteOnExit() }
        FkTagJsonRepo(ctx1, tagFile, 50L).apply {
            add(FkTag(10))
            add(FkTag(11))
        }
        FkItemJsonRepo(ctx1, itemFile, 50L).apply { add(FkItem(2, setOf(10, 99))) }
        reactive.advance()
        ctx1.close()

        val ctx2 = LirpContext()
        FkTagJsonRepo(ctx2, tagFile, 50L)
        val exception =
            shouldThrow<LirpDeserializationException> {
                FkItemJsonRepo(ctx2, itemFile, 50L, fkPolicy = JsonFkPolicy.STRICT)
            }
        exception.message!! shouldContain "Dangling @Aggregate reference"

        ctx2.close()
    }

    "JsonFileRepository STRICT throws on dangling single-entity ref" {
        val ctx1 = LirpContext()
        val childFile = tempfile("fk-strict-child", ".json").also { it.deleteOnExit() }
        val parentFile = tempfile("fk-strict-parent", ".json").also { it.deleteOnExit() }
        FkChildJsonRepo(ctx1, childFile, 50L).apply { add(FkChild(5)) }
        FkParentJsonRepo(ctx1, parentFile, 50L).apply { add(FkParent(300, 5)) }
        reactive.advance()
        ctx1.close()

        val ctx2 = LirpContext()
        val childRepo2 = FkChildJsonRepo(ctx2, childFile, 50L)
        childRepo2.findById(5).ifPresent { childRepo2.remove(it) }
        val exception =
            shouldThrow<LirpDeserializationException> {
                FkParentJsonRepo(ctx2, parentFile, 50L, fkPolicy = JsonFkPolicy.STRICT)
            }
        exception.message!! shouldContain "Dangling @Aggregate reference"

        ctx2.close()
    }
})

// ---------------------------------------------------------------------------
// Test fixtures — declared at file scope so KSP generates RefAccessors.
// ---------------------------------------------------------------------------

/** Leaf entity referenced by [FkItem] via a mutable aggregate set. */
class FkTag(override val id: Int) : ReactiveEntityBase<Int, FkTag>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "fk-tag-$id"

    override fun clone(): FkTag = FkTag(id)
}

/** Aggregate root holding a mutable set of tag IDs. Used to exercise collection reconciliation. */
class FkItem(
    override val id: Int,
    initialTagIds: Set<Int> = emptySet()
) : ReactiveEntityBase<Int, FkItem>(), IdentifiableEntity<Int> {

    override val uniqueId: String get() = "fk-item-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    val tags by mutableAggregateSet<Int, FkTag>(initialTagIds)

    override fun clone(): FkItem = FkItem(id, LinkedHashSet(tags.referenceIds))
}

/** Leaf entity referenced by [FkParent] via an optional scalar aggregate. */
class FkChild(override val id: Int) : ReactiveEntityBase<Int, FkChild>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "fk-child-$id"

    override fun clone(): FkChild = FkChild(id)
}

/**
 * Aggregate root with a nullable scalar `@Aggregate` reference and a manually-tracked
 * `version` field. The version field is incremented only by domain mutations performed via
 * [bumpVersion], never by reconciliation, so the reconciliation-silence contract can be asserted.
 */
class FkParent(
    override val id: Int,
    var childId: Int? = null,
    var version: Long = 0L
) : ReactiveEntityBase<Int, FkParent>(), IdentifiableEntity<Int> {

    override val uniqueId: String get() = "fk-parent-$id"

    @Aggregate(onDelete = CascadeAction.DETACH)
    val child by optionalAggregate<Int, FkChild> { childId }

    override fun clone(): FkParent = FkParent(id, childId, version)
}

@LirpRepository
internal class FkTagVolatileRepo(context: LirpContext) :
    VolatileRepository<Int, FkTag>(context, "FkTags")

@LirpRepository
internal class FkChildVolatileRepo(context: LirpContext) :
    VolatileRepository<Int, FkChild>(context, "FkChildren")

@LirpRepository
internal class FkTagJsonRepo(
    context: LirpContext,
    file: File,
    serializationDelayMs: Long = 50L
) : JsonFileRepository<Int, FkTag>(
        context,
        file,
        MapSerializer(Int.serializer(), lirpSerializer(FkTag(0))),
        serializationDelay = serializationDelayMs.milliseconds
    )

@LirpRepository
internal class FkItemJsonRepo(
    context: LirpContext,
    file: File,
    serializationDelayMs: Long = 50L,
    loadOnInit: Boolean = true,
    fkPolicy: JsonFkPolicy = JsonFkPolicy.LOG_AND_RECONCILE,
    onCrudEvent: ((CrudEvent<Int, FkItem>) -> Unit)? = null
) : JsonFileRepository<Int, FkItem>(
        context,
        file,
        MapSerializer(Int.serializer(), lirpSerializer(FkItem(0))),
        serializationDelay = serializationDelayMs.milliseconds,
        loadOnInit = loadOnInit,
        fkPolicy = fkPolicy
    ) {
    init {
        if (onCrudEvent != null) {
            subscribe { event -> onCrudEvent(event) }
        }
    }
}

@LirpRepository
internal class FkChildJsonRepo(
    context: LirpContext,
    file: File,
    serializationDelayMs: Long = 50L
) : JsonFileRepository<Int, FkChild>(
        context,
        file,
        MapSerializer(Int.serializer(), lirpSerializer(FkChild(0))),
        serializationDelay = serializationDelayMs.milliseconds
    )

@LirpRepository
internal class FkParentJsonRepo(
    context: LirpContext,
    file: File,
    serializationDelayMs: Long = 50L,
    loadOnInit: Boolean = true,
    fkPolicy: JsonFkPolicy = JsonFkPolicy.LOG_AND_RECONCILE,
    onCrudEvent: ((CrudEvent<Int, FkParent>) -> Unit)? = null
) : JsonFileRepository<Int, FkParent>(
        context,
        file,
        MapSerializer(Int.serializer(), lirpSerializer(FkParent(0))),
        serializationDelay = serializationDelayMs.milliseconds,
        loadOnInit = loadOnInit,
        fkPolicy = fkPolicy
    ) {
    init {
        if (onCrudEvent != null) {
            subscribe { event -> onCrudEvent(event) }
        }
    }
}
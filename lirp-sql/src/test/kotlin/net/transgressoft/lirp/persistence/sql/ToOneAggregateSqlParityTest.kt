/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.AggregateMutationEvent
import net.transgressoft.lirp.persistence.LirpRegistryInfo
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.ToOneAggregate
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.optional.shouldNotBePresent
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// ---------------------------------------------------------------------------
// Test-local fixture entities for @ToOneAggregate SQL parity.
// Music-domain naming follows the canonical fixture vocabulary.
// These entities carry @PersistenceMapping so KSP generates their table defs
// and extension accessors, enabling full SqlRepository round-trip tests.
// ---------------------------------------------------------------------------

/**
 * Referenced music label entity used as FK target in SQL parity tests.
 */
@PersistenceMapping(name = "sql_parity_label")
internal class SqlParityLabel(override val id: Int, name: String) :
    ReactiveEntityBase<Int, SqlParityLabel>() {
    var name: String by reactiveProperty(name)

    override val uniqueId: String get() = "sql-parity-label-$id"

    override fun clone(): SqlParityLabel = SqlParityLabel(id, name)
}

/**
 * Owning release entity with optional FK scalar to [SqlParityLabel] and DETACH cascade.
 */
@PersistenceMapping(name = "sql_parity_release")
internal class SqlParityRelease(override val id: Int, title: String, labelId: Int? = null) :
    ReactiveEntityBase<Int, SqlParityRelease>() {
    var title: String by reactiveProperty(title)

    @ToOneAggregate(target = SqlParityLabel::class, onDelete = CascadeAction.DETACH)
    var labelId: Int? by reactiveProperty(labelId)

    override val uniqueId: String get() = "sql-parity-release-$id"

    override fun clone(): SqlParityRelease = SqlParityRelease(id, title, labelId)
}

/**
 * Release entity with `bubbleUp = true` and no-FK cascade (non-nullable scalar).
 */
@PersistenceMapping(name = "sql_parity_bubble_release")
internal class SqlParityBubbleRelease(override val id: Int, title: String, labelId: Int) :
    ReactiveEntityBase<Int, SqlParityBubbleRelease>() {
    var title: String by reactiveProperty(title)

    @ToOneAggregate(target = SqlParityLabel::class, bubbleUp = true, onDelete = CascadeAction.NONE)
    var labelId: Int by reactiveProperty(labelId)

    override val uniqueId: String get() = "sql-parity-bubble-release-$id"

    override fun clone(): SqlParityBubbleRelease = SqlParityBubbleRelease(id, title, labelId)
}

/**
 * Release entity with `onDelete = CASCADE` to [SqlParityLabel].
 */
@PersistenceMapping(name = "sql_parity_cascade_release")
internal class SqlParityCascadeRelease(override val id: Int, title: String, labelId: Int? = null) :
    ReactiveEntityBase<Int, SqlParityCascadeRelease>() {
    var title: String by reactiveProperty(title)

    @ToOneAggregate(target = SqlParityLabel::class, onDelete = CascadeAction.CASCADE)
    var labelId: Int? by reactiveProperty(labelId)

    override val uniqueId: String get() = "sql-parity-cascade-release-$id"

    override fun clone(): SqlParityCascadeRelease = SqlParityCascadeRelease(id, title, labelId)
}

// ---------------------------------------------------------------------------
// Named repository subclasses + manual _LirpRegistryInfo companions.
// RegistryBase.init resolves the entity class from a KspAccessorLoader lookup
// keyed on the concrete class name. Anonymous SqlRepository<K,E> instances have
// no _LirpRegistryInfo, so they are never registered in LirpContext, causing
// bindEntityRefs to skip FK entries. Naming the subclass and providing the info
// class follows the existing SqlRepository test pattern (see FxSqlTestFixtures).
// ---------------------------------------------------------------------------

/** Named [SqlRepository] subclass for [SqlParityLabel]. */
internal class SqlParityLabelRepo(jdbcUrl: String) :
    SqlRepository<Int, SqlParityLabel>(jdbcUrl, SqlParityLabel_LirpTableDef)

@Suppress("ClassName")
internal class `SqlParityLabelRepo_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = SqlParityLabel::class.java
}

/** Named [SqlRepository] subclass for [SqlParityRelease]. */
internal class SqlParityReleaseRepo(jdbcUrl: String) :
    SqlRepository<Int, SqlParityRelease>(jdbcUrl, SqlParityRelease_LirpTableDef)

@Suppress("ClassName")
internal class `SqlParityReleaseRepo_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = SqlParityRelease::class.java
}

/** Named [SqlRepository] subclass for [SqlParityBubbleRelease]. */
internal class SqlParityBubbleReleaseRepo(jdbcUrl: String) :
    SqlRepository<Int, SqlParityBubbleRelease>(jdbcUrl, SqlParityBubbleRelease_LirpTableDef)

@Suppress("ClassName")
internal class `SqlParityBubbleReleaseRepo_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = SqlParityBubbleRelease::class.java
}

/** Named [SqlRepository] subclass for [SqlParityCascadeRelease]. */
internal class SqlParityCascadeReleaseRepo(jdbcUrl: String) :
    SqlRepository<Int, SqlParityCascadeRelease>(jdbcUrl, SqlParityCascadeRelease_LirpTableDef)

@Suppress("ClassName")
internal class `SqlParityCascadeReleaseRepo_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = SqlParityCascadeRelease::class.java
}

/**
 * SQL parity tests proving that `@ToOneAggregate` extension accessor navigation, bubble-up
 * propagation, and cascade deletion work identically against a [SqlRepository] (H2 in-memory)
 * as they do against the volatile/JSON backends verified in `ToOneAggregateExtAccessorTest`.
 *
 * Each test creates a fresh H2 database URL to ensure isolation.
 */
internal class ToOneAggregateSqlParityTest : StringSpec({

    reactiveScope()

    fun freshH2Url() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    // Tracks every SqlRepository created during the current test so afterEach can close
    // all connection pools deterministically, even when a test fails mid-assertion.
    val openRepos = mutableListOf<SqlRepository<*, *>>()

    afterEach {
        openRepos.forEach { it.close() }
        openRepos.clear()
        RegistryBase.deregisterRepository(SqlParityLabel::class.java)
        RegistryBase.deregisterRepository(SqlParityRelease::class.java)
        RegistryBase.deregisterRepository(SqlParityBubbleRelease::class.java)
        RegistryBase.deregisterRepository(SqlParityCascadeRelease::class.java)
    }

    "SqlParityRelease.label resolves referenced SqlParityLabel via SqlRepository" {
        val jdbcUrl = freshH2Url()
        val labelRepo = SqlParityLabelRepo(jdbcUrl).also { openRepos.add(it) }
        val releaseRepo = SqlParityReleaseRepo(jdbcUrl).also { openRepos.add(it) }

        val label = SqlParityLabel(1, "Impulse Records")
        labelRepo.add(label)

        val release = SqlParityRelease(100, "A Love Supreme", labelId = 1)
        releaseRepo.add(release)

        val ref = release.label
        ref.referenceId shouldBe 1
        val resolved = ref.resolve()
        resolved.shouldBePresent()
        resolved.get().name shouldBe "Impulse Records"
    }

    "SqlParityRelease.label referenceId matches the stored scalar under SqlRepository" {
        val jdbcUrl = freshH2Url()
        val labelRepo = SqlParityLabelRepo(jdbcUrl).also { openRepos.add(it) }
        val releaseRepo = SqlParityReleaseRepo(jdbcUrl).also { openRepos.add(it) }

        val label = SqlParityLabel(2, "Prestige")
        labelRepo.add(label)

        val release = SqlParityRelease(200, "Workin", labelId = 2)
        releaseRepo.add(release)

        release.label.referenceId shouldBe release.labelId
    }

    "SqlParityRelease.label resolves Optional.empty when FK scalar is null under SqlRepository" {
        val jdbcUrl = freshH2Url()
        val labelRepo = SqlParityLabelRepo(jdbcUrl).also { openRepos.add(it) }
        val releaseRepo = SqlParityReleaseRepo(jdbcUrl).also { openRepos.add(it) }

        val release = SqlParityRelease(300, "Unknown", labelId = null)
        releaseRepo.add(release)

        release.label.resolve().shouldNotBePresent()
    }

    "SqlParityBubbleRelease.label bubbleUp=true propagates SqlParityLabel mutation to subscriber via SqlRepository" {
        val jdbcUrl = freshH2Url()
        val labelRepo = SqlParityLabelRepo(jdbcUrl).also { openRepos.add(it) }
        val releaseRepo = SqlParityBubbleReleaseRepo(jdbcUrl).also { openRepos.add(it) }

        val label = SqlParityLabel(10, "Blue Note")
        labelRepo.add(label)

        val release = SqlParityBubbleRelease(1000, "Kind of Blue", labelId = 10)
        releaseRepo.add(release)

        val receivedEvent = AtomicReference<AggregateMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        release.subscribeAsync { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvent.set(event)
                latch.countDown()
            }
        }

        label.name = "Blue Note Records"

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvent.get().refName shouldBe "label"
    }

    "SqlParityCascadeRelease CASCADE deletion removes the referenced SqlParityLabel via SqlRepository" {
        val jdbcUrl = freshH2Url()
        val labelRepo = SqlParityLabelRepo(jdbcUrl).also { openRepos.add(it) }
        val cascadeReleaseRepo = SqlParityCascadeReleaseRepo(jdbcUrl).also { openRepos.add(it) }

        val label = SqlParityLabel(20, "Columbia")
        labelRepo.add(label)

        val release = SqlParityCascadeRelease(2000, "Kind of Blue", labelId = 20)
        cascadeReleaseRepo.add(release)

        cascadeReleaseRepo.remove(release)

        labelRepo.findById(20).shouldNotBePresent()
        cascadeReleaseRepo.findById(2000).shouldNotBePresent()
    }

    "SqlParityRelease.label DETACH removal leaves referenced SqlParityLabel intact via SqlRepository" {
        val jdbcUrl = freshH2Url()
        val labelRepo = SqlParityLabelRepo(jdbcUrl).also { openRepos.add(it) }
        val releaseRepo = SqlParityReleaseRepo(jdbcUrl).also { openRepos.add(it) }

        val label = SqlParityLabel(30, "Verve")
        labelRepo.add(label)

        val release = SqlParityRelease(3000, "Saxophone Colossus", labelId = 30)
        releaseRepo.add(release)

        releaseRepo.remove(release)

        labelRepo.findById(30).shouldBePresent()
        releaseRepo.findById(3000).shouldNotBePresent()
    }
})
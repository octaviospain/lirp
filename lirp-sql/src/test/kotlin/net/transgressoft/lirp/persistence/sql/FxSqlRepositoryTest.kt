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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.persistence.RegistryBase
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * SQL persistence tests for entities combining fx scalar delegates ([fxString], [fxInteger],
 * [fxBoolean], [fxDouble], [fxObject]) with [fxAggregateList] collection delegates.
 * Uses H2 in-memory databases for fast isolated tests.
 */
@DisplayName("FxSqlRepositoryTest")
internal class FxSqlRepositoryTest : FunSpec({

    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    beforeSpec {
        FxToolkitInit.ensureInitialized()
    }

    afterEach {
        RegistryBase.deregisterRepository(FxSqlTestItem::class.java)
        RegistryBase.deregisterRepository(FxSqlTestEntity::class.java)
    }

    test("adds entity with fx scalar properties and persists to database") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val entity = FxSqlTestEntity(1, "Test", 2024, true, 8.5, "rock")
        entityRepo.add(entity)

        entityRepo.close()
        itemRepo.close()

        val itemRepo2 = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo2 = FxSqlTestEntityRepository(jdbcUrl)
        entityRepo2.findById(1).shouldBePresent {
            it.nameProperty.get() shouldBe "Test"
            it.yearProperty.get() shouldBe 2024
            it.activeProperty.get() shouldBe true
            it.ratingProperty.get() shouldBe 8.5
            it.tagProperty.get() shouldBe "rock"
        }

        entityRepo2.close()
        itemRepo2.close()
    }

    test("persists fx scalar property mutation via flush") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val entity = FxSqlTestEntity(1, "Original")
        entityRepo.add(entity)
        entity.nameProperty.set("Mutated")

        entityRepo.close()
        itemRepo.close()

        val itemRepo2 = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo2 = FxSqlTestEntityRepository(jdbcUrl)
        entityRepo2.findById(1).shouldBePresent {
            it.nameProperty.get() shouldBe "Mutated"
        }

        entityRepo2.close()
        itemRepo2.close()
    }

    test("persists all fx scalar types correctly") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val entity = FxSqlTestEntity(1, "AllTypes", 2025, true, 9.99, "jazz")
        entityRepo.add(entity)

        entityRepo.close()
        itemRepo.close()

        val itemRepo2 = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo2 = FxSqlTestEntityRepository(jdbcUrl)
        entityRepo2.findById(1).shouldBePresent {
            it.nameProperty.get() shouldBe "AllTypes"
            it.yearProperty.get() shouldBe 2025
            it.activeProperty.get() shouldBe true
            it.ratingProperty.get() shouldBe 9.99
            it.tagProperty.get() shouldBe "jazz"
        }

        entityRepo2.close()
        itemRepo2.close()
    }

    test("null tag property persists and reloads as null") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val entity = FxSqlTestEntity(1, "NullTag", initialTag = null)
        entityRepo.add(entity)

        entityRepo.close()
        itemRepo.close()

        val itemRepo2 = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo2 = FxSqlTestEntityRepository(jdbcUrl)
        entityRepo2.findById(1).shouldBePresent {
            it.tagProperty.get() shouldBe null
        }

        entityRepo2.close()
        itemRepo2.close()
    }

    test("persists fx aggregate itemIds and reloads them") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val item1 = FxSqlTestItem(1, "Item A")
        val item2 = FxSqlTestItem(2, "Item B")
        itemRepo.add(item1)
        itemRepo.add(item2)

        val entity = FxSqlTestEntity(1, "WithItems")
        entityRepo.add(entity)
        entity.items.add(item1)
        entity.items.add(item2)

        entityRepo.close()
        itemRepo.close()

        val itemRepo2 = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo2 = FxSqlTestEntityRepository(jdbcUrl)
        entityRepo2.findById(1).shouldBePresent {
            it.items.referenceIds shouldBe listOf(1, 2)
            it.items.resolveAll().size shouldBe 2
        }

        entityRepo2.close()
        itemRepo2.close()
    }

    test("persists mutations on fx aggregate after reload") {
        val jdbcUrl = freshJdbcUrl()
        var itemRepo = FxSqlTestItemRepository(jdbcUrl)
        var entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val item1 = FxSqlTestItem(1, "A")
        val item2 = FxSqlTestItem(2, "B")
        val item3 = FxSqlTestItem(3, "C")
        itemRepo.add(item1)
        itemRepo.add(item2)
        itemRepo.add(item3)

        val entity = FxSqlTestEntity(1, "Mutable", initialItemIds = listOf(1, 2))
        entityRepo.add(entity)

        entityRepo.close()
        itemRepo.close()

        itemRepo = FxSqlTestItemRepository(jdbcUrl)
        entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val reloaded = entityRepo.findById(1).get()
        reloaded.items.referenceIds shouldBe listOf(1, 2)

        val reloadedItem3 = itemRepo.findById(3).get()
        reloaded.items.add(reloadedItem3)
        val reloadedItem1 = itemRepo.findById(1).get()
        reloaded.items.remove(reloadedItem1)

        entityRepo.close()
        itemRepo.close()

        itemRepo = FxSqlTestItemRepository(jdbcUrl)
        entityRepo = FxSqlTestEntityRepository(jdbcUrl)
        entityRepo.findById(1).shouldBePresent {
            it.items.referenceIds shouldBe listOf(2, 3)
        }

        entityRepo.close()
        itemRepo.close()
    }

    test("clear on fx aggregate persists empty state") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val item1 = FxSqlTestItem(1, "X")
        itemRepo.add(item1)
        val entity = FxSqlTestEntity(1, "ToClear")
        entityRepo.add(entity)
        entity.items.add(item1)
        entity.items.clear()

        entityRepo.close()
        itemRepo.close()

        val itemRepo2 = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo2 = FxSqlTestEntityRepository(jdbcUrl)
        entityRepo2.findById(1).shouldBePresent {
            it.items.referenceIds shouldBe emptyList()
        }

        entityRepo2.close()
        itemRepo2.close()
    }

    test("fx scalar property mutation emits UPDATE CrudEvent from SqlRepository") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)
        val received = AtomicReference<CrudEvent.Type?>()

        val entity = FxSqlTestEntity(1, "EventTest")
        entityRepo.add(entity)

        entityRepo.subscribe { event -> received.set(event.type) }
        delay(50.milliseconds)

        entity.nameProperty.set("Updated")

        eventually(5.seconds) {
            received.get() shouldBe CrudEvent.Type.UPDATE
        }

        entityRepo.close()
        itemRepo.close()
    }

    test("entity with both scalar and collection mutations persists all changes") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val item1 = FxSqlTestItem(1, "Combined")
        itemRepo.add(item1)

        val entity = FxSqlTestEntity(1, "Initial", 2020, false, 5.0, null)
        entityRepo.add(entity)

        entity.nameProperty.set("Updated")
        entity.yearProperty.set(2025)
        entity.activeProperty.set(true)
        entity.ratingProperty.set(9.5)
        entity.tagProperty.set("best")
        entity.items.add(item1)

        entityRepo.close()
        itemRepo.close()

        val itemRepo2 = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo2 = FxSqlTestEntityRepository(jdbcUrl)
        entityRepo2.findById(1).shouldBePresent {
            it.nameProperty.get() shouldBe "Updated"
            it.yearProperty.get() shouldBe 2025
            it.activeProperty.get() shouldBe true
            it.ratingProperty.get() shouldBe 9.5
            it.tagProperty.get() shouldBe "best"
            it.items.referenceIds shouldBe listOf(1)
        }

        entityRepo2.close()
        itemRepo2.close()
    }
})
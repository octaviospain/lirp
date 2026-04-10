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

import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.fx.fxAggregateList
import net.transgressoft.lirp.persistence.fx.fxProjectionMap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Integration tests verifying that [fxProjectionMap] correctly groups entities from
 * an [net.transgressoft.lirp.persistence.fx.FxAggregateList] holding references to
 * entities loaded from a [SqlRepository].
 *
 * The projection operates on the in-memory aggregate list — the SQL layer provides persistence
 * and round-trip fidelity, while the projection map groups entities by [FxSqlTestEntity.groupProperty].
 */
@DisplayName("FxSqlProjectionMapTest")
internal class FxSqlProjectionMapTest : FunSpec({

    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = CoroutineScope(Dispatchers.Unconfined)
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
    }

    afterEach {
        RegistryBase.deregisterRepository(FxSqlTestItem::class.java)
        RegistryBase.deregisterRepository(FxSqlTestEntity::class.java)
    }

    test("fxProjectionMap groups SQL-persisted entities by groupProperty") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val entity1 = FxSqlTestEntity(1, "Entity A", initialGroup = "Alpha")
        val entity2 = FxSqlTestEntity(2, "Entity B", initialGroup = "Alpha")
        val entity3 = FxSqlTestEntity(3, "Entity C", initialGroup = "Beta")
        entityRepo.add(entity1)
        entityRepo.add(entity2)
        entityRepo.add(entity3)

        val list = fxAggregateList<Int, FxSqlTestEntity>(dispatchToFxThread = false)
        list.addAll(listOf(entity1, entity2, entity3))

        val projection =
            fxProjectionMap<Int, String, FxSqlTestEntity>(
                { list },
                { it.groupProperty.get() },
                false
            )

        projection.size shouldBe 2
        projection["Alpha"]!!.size shouldBe 2
        projection["Beta"]!!.size shouldBe 1

        entityRepo.close()
        itemRepo.close()
    }

    test("fxProjectionMap updates when entity is added after SQL persistence") {
        val jdbcUrl = freshJdbcUrl()
        val itemRepo = FxSqlTestItemRepository(jdbcUrl)
        val entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val entity1 = FxSqlTestEntity(1, "E1", initialGroup = "GroupA")
        entityRepo.add(entity1)

        val list = fxAggregateList<Int, FxSqlTestEntity>(dispatchToFxThread = false)
        list.add(entity1)

        val projection =
            fxProjectionMap<Int, String, FxSqlTestEntity>(
                { list },
                { it.groupProperty.get() },
                false
            )

        projection["GroupA"]!!.size shouldBe 1

        val entity2 = FxSqlTestEntity(2, "E2", initialGroup = "GroupA")
        entityRepo.add(entity2)
        list.add(entity2)

        projection["GroupA"]!!.size shouldBe 2

        entityRepo.close()
        itemRepo.close()
    }

    test("fxProjectionMap reloads and groups entities from SQL round-trip") {
        val jdbcUrl = freshJdbcUrl()
        var itemRepo = FxSqlTestItemRepository(jdbcUrl)
        var entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        entityRepo.add(FxSqlTestEntity(1, "E1", initialGroup = "Alpha"))
        entityRepo.add(FxSqlTestEntity(2, "E2", initialGroup = "Beta"))
        entityRepo.add(FxSqlTestEntity(3, "E3", initialGroup = "Alpha"))

        entityRepo.close()
        itemRepo.close()

        itemRepo = FxSqlTestItemRepository(jdbcUrl)
        entityRepo = FxSqlTestEntityRepository(jdbcUrl)

        val reloaded = entityRepo.search { true }
        val list = fxAggregateList<Int, FxSqlTestEntity>(dispatchToFxThread = false)
        list.addAll(reloaded)

        val projection =
            fxProjectionMap<Int, String, FxSqlTestEntity>(
                { list },
                { it.groupProperty.get() },
                false
            )

        projection.size shouldBe 2
        projection["Alpha"]!!.size shouldBe 2
        projection["Beta"]!!.size shouldBe 1

        entityRepo.close()
        itemRepo.close()
    }
})
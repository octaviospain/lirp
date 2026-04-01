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
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Verifies that [RegistryBase] gracefully handles anonymous, local, and nested inner class
 * entities without throwing [ClassNotFoundException].
 *
 * Inner class entities lack KSP-generated accessors in this test context because no processor
 * runs during unit tests. The defensive guards in [RegistryBase.discoverRefs] and
 * [RegistryBase.discoverIndexes] short-circuit before the [Class.forName] lookup for anonymous
 * and local classes, while inner class entities fall through to the normal discovery path
 * (which finds no accessor and proceeds with empty ref/index lists).
 */
@DisplayName("RegistryBase inner class and anonymous entity handling")
@OptIn(ExperimentalCoroutinesApi::class)
internal class InnerClassRegistryTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var repo: VolatileRepository<Int, SimpleTestEntity>

    beforeEach {
        repo = object : VolatileRepository<Int, SimpleTestEntity>("inner-class-test") {}
    }

    afterEach {
        repo.close()
    }

    // --- Anonymous entity tests ---

    "RegistryBase adds anonymous entity without throwing" {
        val anonymousEntity =
            object : SimpleTestEntity(1) {
                override val uniqueId = "anon-1"
            }

        shouldNotThrow<Exception> {
            repo.add(anonymousEntity)
        }
    }

    "RegistryBase stores anonymous entity after add" {
        val anonymousEntity =
            object : SimpleTestEntity(2) {
                override val uniqueId = "anon-2"
            }

        repo.add(anonymousEntity)

        repo.size() shouldBe 1
        repo.findById(2).get() shouldBe anonymousEntity
    }

    // --- Local class entity tests ---

    "RegistryBase adds local class entity without throwing" {
        class LocalEntity(id: Int) : SimpleTestEntity(id) {
            override val uniqueId = "local-$id"
        }

        shouldNotThrow<Exception> {
            repo.add(LocalEntity(3))
        }
    }

    "RegistryBase stores local class entity after add" {
        class LocalEntity(id: Int) : SimpleTestEntity(id) {
            override val uniqueId = "local-$id"
        }

        repo.add(LocalEntity(4))

        repo.size() shouldBe 1
    }

    // --- Single-level inner class entity tests ---

    "RegistryBase adds single-level inner class entity without throwing" {
        val entity = OuterEntity.InnerEntity(10)

        shouldNotThrow<Exception> {
            repo.add(entity)
        }
    }

    "RegistryBase stores and retrieves single-level inner class entity" {
        val entity = OuterEntity.InnerEntity(11)

        repo.add(entity)

        repo.size() shouldBe 1
        repo.findById(11).get() shouldBe entity
    }

    // --- Two-level nested inner class entity tests ---

    "RegistryBase adds two-level nested inner class entity without throwing" {
        val entity = OuterEntity.MiddleEntity.DeepEntity(20)

        shouldNotThrow<Exception> {
            repo.add(entity)
        }
    }

    "RegistryBase stores and retrieves two-level nested inner class entity" {
        val entity = OuterEntity.MiddleEntity.DeepEntity(21)

        repo.add(entity)

        repo.size() shouldBe 1
        repo.findById(21).get() shouldBe entity
    }

    // --- Three-level nested inner class entity tests ---

    "RegistryBase adds three-level nested inner class entity without throwing" {
        val entity = OuterEntity.MiddleEntity.DeepEntity.DeeperEntity(30)

        shouldNotThrow<Exception> {
            repo.add(entity)
        }
    }

    "RegistryBase stores and retrieves three-level nested inner class entity" {
        val entity = OuterEntity.MiddleEntity.DeepEntity.DeeperEntity(31)

        repo.add(entity)

        repo.size() shouldBe 1
        repo.findById(31).get() shouldBe entity
    }

    // --- Mixed entity types in a single repository ---

    "RegistryBase stores top-level, inner class, and anonymous entities together" {
        val topLevel = SimpleTestEntity(100)
        val innerEntity = OuterEntity.InnerEntity(101)
        val deepEntity = OuterEntity.MiddleEntity.DeepEntity(102)
        val anonymousEntity =
            object : SimpleTestEntity(103) {
                override val uniqueId = "anon-103"
            }

        repo.add(topLevel)
        repo.add(innerEntity)
        repo.add(deepEntity)
        repo.add(anonymousEntity)

        repo.size() shouldBe 4
        repo.findById(100).get() shouldBe topLevel
        repo.findById(101).get() shouldBe innerEntity
        repo.findById(102).get() shouldBe deepEntity
        repo.findById(103).get() shouldBe anonymousEntity
    }

    // --- Anonymous entity created from an inner class ---

    "RegistryBase adds anonymous entity subclassing an inner class without throwing" {
        val anonymousInner =
            object : OuterEntity.InnerEntity(40) {
                override val uniqueId = "anon-inner-40"
            }

        shouldNotThrow<Exception> {
            repo.add(anonymousInner)
        }
        repo.size() shouldBe 1
    }

    // --- refAccessorFor companion method guards ---

    "refAccessorFor returns null for anonymous class entity" {
        val anonymousEntity =
            object : SimpleTestEntity(50) {
                override val uniqueId = "anon-50"
            }

        RegistryBase.refAccessorFor(anonymousEntity.javaClass).shouldBeNull()
    }

    "refAccessorFor returns null for local class entity" {
        class LocalEntity(id: Int) : SimpleTestEntity(id) {
            override val uniqueId = "local-$id"
        }

        RegistryBase.refAccessorFor(LocalEntity(51).javaClass).shouldBeNull()
    }

    // --- Inner class JVM binary name verification ---

    "inner class entity has dollar-separated JVM binary name" {
        val entity = OuterEntity.InnerEntity(60)

        entity.javaClass.name shouldBe
            "net.transgressoft.lirp.persistence.OuterEntity\$InnerEntity"
    }

    "two-level nested entity has multi-dollar JVM binary name" {
        val entity = OuterEntity.MiddleEntity.DeepEntity(61)

        entity.javaClass.name shouldBe
            "net.transgressoft.lirp.persistence.OuterEntity\$MiddleEntity\$DeepEntity"
    }

    "three-level nested entity has triple-dollar JVM binary name" {
        val entity = OuterEntity.MiddleEntity.DeepEntity.DeeperEntity(62)

        entity.javaClass.name shouldBe
            "net.transgressoft.lirp.persistence.OuterEntity\$MiddleEntity\$DeepEntity\$DeeperEntity"
    }
})

/**
 * Minimal entity base for [InnerClassRegistryTest] — has no [Aggregate][net.transgressoft.lirp.entity.Aggregate]
 * delegate properties, so no KSP accessor is expected.
 * Used to create anonymous, local, and inner class subtypes.
 */
internal open class SimpleTestEntity(override val id: Int) : ReactiveEntityBase<Int, SimpleTestEntity>() {
    override val uniqueId: String get() = "simple-$id"

    override fun clone() = SimpleTestEntity(id)
}

/**
 * Outer class containing nested inner class entity hierarchies for testing [RegistryBase]
 * discovery behavior with inner classes at various nesting depths.
 *
 * Mirrors real-world patterns where domain entities are organized as inner classes
 * inside aggregate containers (e.g. `PlaylistHierarchy.Playlist`).
 */
internal class OuterEntity {

    /**
     * Single-level inner class entity (depth 1).
     * JVM binary name: `OuterEntity$InnerEntity`
     */
    open class InnerEntity(override val id: Int) : SimpleTestEntity(id) {
        override val uniqueId: String get() = "inner-$id"

        override fun clone() = InnerEntity(id)
    }

    /**
     * Middle-level container for deeper nesting (depth 2+).
     */
    class MiddleEntity {

        /**
         * Two-level nested inner class entity (depth 2).
         * JVM binary name: `OuterEntity$MiddleEntity$DeepEntity`
         */
        class DeepEntity(override val id: Int) : SimpleTestEntity(id) {
            override val uniqueId: String get() = "deep-$id"

            override fun clone() = DeepEntity(id)

            /**
             * Three-level nested inner class entity (depth 3).
             * JVM binary name: `OuterEntity$MiddleEntity$DeepEntity$DeeperEntity`
             */
            class DeeperEntity(override val id: Int) : SimpleTestEntity(id) {
                override val uniqueId: String get() = "deeper-$id"

                override fun clone() = DeeperEntity(id)
            }
        }
    }
}
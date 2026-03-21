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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Verifies fail-fast behaviour in KSP accessor discovery: when an entity has [AggregateRefDelegate]
 * backing fields but no KSP-generated [LirpRefAccessor] exists, [IllegalStateException] is thrown.
 * When no delegate fields are present, the accessor absence is silently accepted.
 *
 * The [RegistryBase.failFastIfDelegatePresent] method is tested via reflection because it is private
 * (narrowest scope) but its contract is critical: it must distinguish entities with aggregate ref
 * delegates from those without, so that legitimate plain entities are never rejected.
 *
 * Note: [@ReactiveEntityRef][ReactiveEntityRef] uses [AnnotationRetention.BINARY] — invisible to
 * runtime reflection. The detection therefore inspects JVM backing fields (named `${'$'}delegate`
 * of type [AggregateRefDelegate]) which are always visible regardless of annotation retention.
 */
@DisplayName("RegistryBase")
@OptIn(ExperimentalCoroutinesApi::class)
internal class AccessorDiscoveryTest : FunSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var repo: VolatileRepository<Int, PlainEntity>

    beforeEach {
        repo = object : VolatileRepository<Int, PlainEntity>("test") {}
    }

    afterEach {
        repo.close()
    }

    test("RegistryBase fails fast with IllegalStateException when entity has AggregateRefDelegate field but no KSP accessor") {
        // Retrieve the private failFastIfDelegatePresent method via reflection to test its contract directly
        val method =
            RegistryBase::class.java.getDeclaredMethod(
                "failFastIfDelegatePresent",
                Class::class.java,
                Class::class.java,
                String::class.java
            )
        method.isAccessible = true

        // EntityWithDelegate has an aggregateRef() delegate field — it would normally have a KSP accessor,
        // but we simulate the "accessor missing" case by calling failFastIfDelegatePresent directly
        val ex =
            shouldThrow<java.lang.reflect.InvocationTargetException> {
                method.invoke(repo, EntityWithDelegate::class.java, AggregateRefDelegate::class.java, "LirpRefAccessor")
            }

        ex.cause.shouldBeInstanceOf<IllegalStateException>()
        (ex.cause as IllegalStateException).message shouldContain "KSP-generated"
        (ex.cause as IllegalStateException).message shouldContain "LirpRefAccessor"
    }

    test("RegistryBase silently accepts entity without AggregateRefDelegate fields when no KSP accessor exists") {
        val method =
            RegistryBase::class.java.getDeclaredMethod(
                "failFastIfDelegatePresent",
                Class::class.java,
                Class::class.java,
                String::class.java
            )
        method.isAccessible = true

        // PlainEntity has no AggregateRefDelegate fields — missing accessor is silently accepted
        method.invoke(repo, PlainEntity::class.java, AggregateRefDelegate::class.java, "LirpRefAccessor")
    }
})

/** Entity with no aggregate reference delegates — accessor absence must be accepted silently. */
internal class PlainEntity(override val id: Int) : ReactiveEntityBase<Int, PlainEntity>() {
    override val uniqueId: String get() = "plain-$id"

    override fun clone() = PlainEntity(id)
}

/**
 * Entity with an [AggregateRefDelegate] backing field — KSP would generate an accessor for this
 * class in real usage, but the test calls [RegistryBase.failFastIfDelegatePresent] directly to
 * simulate the case where the accessor is absent.
 */
@LirpRepository
class EntityWithDelegate(override val id: Int, val customerId: Int) : ReactiveEntityBase<Int, EntityWithDelegate>() {
    override val uniqueId: String get() = "delegate-$id"

    @ReactiveEntityRef
    val customer by aggregateRef<Customer, Int> { customerId }

    override fun clone() = EntityWithDelegate(id, customerId)
}
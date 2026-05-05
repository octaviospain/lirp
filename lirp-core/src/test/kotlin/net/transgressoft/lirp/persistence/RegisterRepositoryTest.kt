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

import net.transgressoft.lirp.event.AggregateMutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.json.BubbleUpOrderJsonFileRepository
import net.transgressoft.lirp.persistence.json.JsonFileRepository
import net.transgressoft.lirp.persistence.json.MutableAudioPlaylistJsonFileRepository
import net.transgressoft.lirp.persistence.json.lirpSerializer
import net.transgressoft.lirp.testing.SerializeWithReactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Unit tests for [RegistryBase.registerRepository], the public API for delegation-based
 * repository registration into [LirpContext.default].
 *
 * Verifies registration succeeds, idempotent same-instance calls are allowed, different-instance
 * duplicates throw [IllegalStateException], non-RegistryBase instances throw [IllegalArgumentException],
 * close() deregisters, and re-registration after close succeeds.
 */
@ExperimentalCoroutinesApi
@DisplayName("RegistryBase.registerRepository()")
@SerializeWithReactiveScope
internal class RegisterRepositoryTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        ReactiveScope.resetDefaultIoScope()
    }

    afterEach {
        LirpContext.resetDefault()
    }

    "registers delegate RegistryBase in LirpContext.default keyed by entity class" {
        val delegate = VolatileRepository<Int, Customer>("Customers")

        RegistryBase.registerRepository(Customer::class.java, delegate)

        LirpContext.default.registryFor(Customer::class.java) shouldBe delegate
    }

    "registerRepository() called twice with the same instance is idempotent" {
        val delegate = VolatileRepository<Int, Customer>("Customers")

        RegistryBase.registerRepository(Customer::class.java, delegate)
        RegistryBase.registerRepository(Customer::class.java, delegate)

        LirpContext.default.registryFor(Customer::class.java) shouldBe delegate
    }

    "registerRepository() with a different instance for same entity class throws ISE" {
        val delegate1 = VolatileRepository<Int, Customer>("Customers1")
        val delegate2 = VolatileRepository<Int, Customer>("Customers2")

        RegistryBase.registerRepository(Customer::class.java, delegate1)

        shouldThrow<IllegalStateException> {
            RegistryBase.registerRepository(Customer::class.java, delegate2)
        }.message shouldBe "A repository for Customer is already registered. Only one @LirpRepository per entity type is allowed."
    }

    "registerRepository() with a non-RegistryBase Registry instance throws IAE" {
        val nonRegistryBase = mockk<Repository<Int, Customer>>()

        shouldThrow<IllegalArgumentException> {
            RegistryBase.registerRepository(Customer::class.java, nonRegistryBase)
        }.message shouldContain "Only RegistryBase instances can be registered"
    }

    "close() on delegate deregisters from LirpContext.default" {
        val delegate = VolatileRepository<Int, Customer>("Customers")

        RegistryBase.registerRepository(Customer::class.java, delegate)
        LirpContext.default.registryFor(Customer::class.java) shouldBe delegate

        delegate.close()

        LirpContext.default.registryFor(Customer::class.java) shouldBe null
    }

    "registerRepository() with a delegate from a non-default context throws IAE" {
        val customContext = LirpContext()
        val delegate = VolatileRepository<Int, Customer>(customContext, "Customers")

        try {
            shouldThrow<IllegalArgumentException> {
                RegistryBase.registerRepository(Customer::class.java, delegate)
            }.message shouldContain "registerRepository() only supports RegistryBase instances created in LirpContext.default"
        } finally {
            delegate.close()
        }
    }

    "registerRepository() succeeds after close() and re-registration" {
        val delegate1 = VolatileRepository<Int, Customer>("Customers")

        RegistryBase.registerRepository(Customer::class.java, delegate1)
        delegate1.close()

        LirpContext.default.registryFor(Customer::class.java) shouldBe null

        val delegate2 = VolatileRepository<Int, Customer>("Customers2")
        RegistryBase.registerRepository(Customer::class.java, delegate2)

        LirpContext.default.registryFor(Customer::class.java) shouldBe delegate2
    }

    "registerRepository() rebinds collection aggregate refs of entities loaded before registration" {
        // Reproduces the case where a JsonFileRepository (no @LirpRepository auto-registration)
        // loads entities whose aggregate refs target a class whose registry has not yet been
        // registered. Without rebinding on registerRepository(), the delegates remain unbound
        // forever and resolveAll() returns an empty set even after the registry is registered.
        //
        // The minimal failing scenario: a self-referential type loaded via a plain
        // JsonFileRepository, where registerRepository() is called manually after construction
        // (mirroring the music-commons FXPlaylistHierarchy pattern).
        val playlistFile = tempfile("playlist-late-register", ".json").also { it.deleteOnExit() }

        // Phase 1: write self-referencing playlists via the auto-registered repo so the
        // serialized JSON encodes the parent->child relationship.
        val authoringRepo = MutableAudioPlaylistJsonFileRepository(LirpContext.default, playlistFile, serializationDelayMs = 5L)
        val child = authoringRepo.create(20, "Child")
        val parent = authoringRepo.create(10, "Parent")
        parent.playlists.add(child)
        testDispatcher.scheduler.advanceUntilIdle()
        authoringRepo.close()
        LirpContext.resetDefault()

        // Phase 2: load via PLAIN JsonFileRepository (no @LirpRepository annotation, so no
        // auto-registration of the playlist registry), then manually call registerRepository()
        // afterwards. This is exactly the pattern music-commons FXPlaylistHierarchy uses.
        @Suppress("UNCHECKED_CAST")
        val mapSerializer: KSerializer<Map<Int, MutableAudioPlaylist>> =
            MapSerializer(Int.serializer(), lirpSerializer(DefaultAudioPlaylist(0, ""))) as KSerializer<Map<Int, MutableAudioPlaylist>>
        val plainRepo = JsonFileRepository<Int, MutableAudioPlaylist>(playlistFile, mapSerializer)

        // At this point, plainRepo loaded both entities. bindEntityRefs ran during load but
        // skipped each entity's `playlists` aggregate ref because no registry was registered
        // for MutableAudioPlaylist when it was called.

        RegistryBase.registerRepository(MutableAudioPlaylist::class.java, plainRepo)

        // After registerRepository(), the previously-skipped aggregate refs must now resolve.
        val reloadedParent = plainRepo.findById(10).get() as DefaultAudioPlaylist
        reloadedParent.playlists.referenceIds shouldBe setOf(20)
        reloadedParent.playlists.resolveAll().map { it.id } shouldBe listOf(20)

        plainRepo.close()
    }

    "registerRepository() rebinds scalar aggregate refs and rewires bubble-up" {
        // Mirrors the collection-ref scenario for scalar refs declared with `bubbleUp = true`,
        // verifying both that resolve() succeeds AND that the bubble-up subscription fires.
        // Without re-running wireRefBubbleUp() inside registerRepository(), parent subscribers
        // would not receive AggregateMutationEvent from the late-registered child.
        val orderFile = tempfile("order-scalar-late-register", ".json").also { it.deleteOnExit() }

        // Phase 1: write a customer + order via auto-registered repos so the JSON encodes
        // the customerId scalar ref.
        val authoringCustomers = CustomerVolatileRepo(LirpContext.default)
        val authoringOrders = BubbleUpOrderJsonFileRepository(LirpContext.default, orderFile, 5L)
        authoringCustomers.create(1, "Alice")
        authoringOrders.create(10L, 1)
        testDispatcher.scheduler.advanceUntilIdle()
        authoringOrders.close()
        authoringCustomers.close()
        LirpContext.resetDefault()

        // Phase 2: reload orders WITHOUT registering the Customer registry first.
        // BubbleUpOrderJsonFileRepository auto-registers BubbleUpOrder, but Customer remains
        // absent — each loaded order's customer scalar ref delegate is left unbound, and
        // wireRefBubbleUp() ran with bubbleUpParent set but no resolvable child, so no
        // bubble-up subscription was created.
        val orderRepo = BubbleUpOrderJsonFileRepository(LirpContext.default, orderFile, 5L)
        val loadedOrder = orderRepo.findById(10L).get()
        loadedOrder.customer.resolve().isPresent shouldBe false

        // Phase 3: late-register Customer via registerRepository(). The rebinding pass must
        // walk all registries (including orderRepo), rebind scalar refs whose target class is
        // now resolvable, AND re-wire bubble-up subscriptions for `bubbleUp = true` refs.
        val lateCustomers = VolatileRepository<Int, Customer>(LirpContext.default, "CustomersLate")
        val customer = Customer(1, "Alice").also(lateCustomers::add)
        RegistryBase.registerRepository(Customer::class.java, lateCustomers)

        // resolve() now returns the customer — confirms the scalar bind branch works.
        loadedOrder.customer.resolve().isPresent shouldBe true
        loadedOrder.customer.resolve().get().name shouldBe "Alice"

        // Bubble-up must fire: a mutation on the customer should reach the order's subscribers
        // as an AggregateMutationEvent. This would NOT happen if rebindReferencesTo() only
        // re-ran bindEntityRefs() and skipped wireRefBubbleUp().
        val bubbleUpReceived = AtomicBoolean(false)
        loadedOrder.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) bubbleUpReceived.set(true)
        }
        customer.updateName("Alice Updated")
        testDispatcher.scheduler.advanceUntilIdle()

        bubbleUpReceived.get() shouldBe true

        orderRepo.close()
        lateCustomers.close()
    }
})
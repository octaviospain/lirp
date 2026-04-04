package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.CrudEvent.Type.DELETE
import net.transgressoft.lirp.event.CrudEvent.Type.READ
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.LirpEventSubscriberBase
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.positiveInt
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import java.util.Collections
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

private fun arbitraryCustomer(id: Int = -1) =
    io.kotest.property.arbitrary.arbitrary {
        Customer(
            id = if (id == -1) Arb.positiveInt(500_000).bind() else id,
            initialName = Arb.stringPattern("[a-z]{5} [a-z]{5}").bind()
        )
    }

@ExperimentalCoroutinesApi
internal class VolatileRepositoryTest : FunSpec({

    class SomeClassSubscribedToEvents() : LirpEventSubscriberBase<Customer, CrudEvent.Type, CrudEvent<Int, Customer>>("Some Name") {
        val createEventEntities = AtomicInteger(0)
        val deletedEventEntities = AtomicInteger(0)
        val receivedEvents = mutableMapOf<EventType, CrudEvent<Int, Customer>>()

        init {
            addOnNextEventAction(CREATE, UPDATE) { event ->
                receivedEvents[event.type] = event
                createEventEntities.getAndUpdate { it + event.entities.size }
            }
            addOnNextEventAction(DELETE) { event ->
                receivedEvents[event.type] = event
                deletedEventEntities.getAndUpdate { it + event.entities.size }
            }
            addOnNextEventAction(READ) { receivedEvents[it.type] = it }
        }
    }

    lateinit var ctx: LirpContext
    lateinit var repository: CustomerVolatileRepo
    lateinit var subscriber: SomeClassSubscribedToEvents

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    beforeTest {
        ctx = LirpContext()
        repository =
            CustomerVolatileRepo(ctx).apply {
                activateEvents(READ)
            }
        subscriber = SomeClassSubscribedToEvents()
        repository.subscribe(subscriber)
    }

    afterTest {
        ctx.close()
    }

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    test("Repository reflects addition and deletion of entities") {
        checkAll(arbitraryCustomer()) { customer ->
            repository.isEmpty shouldBe true
            repository.create(customer.id, customer.name) shouldNotBe null
            repository.isEmpty shouldBe false
            repository.findById(customer.id) shouldBe Optional.of(customer)
            repository.findByUniqueId(customer.uniqueId) shouldBePresent { it shouldBe customer }
            repository.search { it.name == customer.name }.shouldContainOnly(customer)
            repository.contains(customer.id) shouldBe true
            repository.contains { it == customer } shouldBe true

            repository.size() shouldBe 1

            repository.remove(customer) shouldBe true
            repository.isEmpty shouldBe true
        }
    }

    test("Registry iterates over all entities via Iterable") {
        val customers = Arb.set(arbitraryCustomer(), 3..3).next()
        customers.forEach { repository.create(it.id, it.name) }

        val iterated = mutableSetOf<Customer>()
        repository.forEach(iterated::add)

        iterated shouldContainOnly customers
    }

    test("Repository publishes CRUD events received by a subscriber") {
        val customer = arbitraryCustomer().next()
        val customer2 = arbitraryCustomer().next()
        repository.create(customer.id, customer.name)
        repository.create(customer2.id, customer2.name)

        testDispatcher.scheduler.advanceUntilIdle()

        // Two separate create() calls produce two separate CREATE events;
        // receivedEvents[CREATE] holds only the last one, so check count via createEventEntities
        subscriber.receivedEvents[CREATE]?.isCreate() shouldBe true
        subscriber.createEventEntities.get() shouldBe 2
        subscriber.deletedEventEntities.get() shouldBe 0

        repository.removeAll(setOf(customer, customer2)) shouldBe true

        testDispatcher.scheduler.advanceUntilIdle()

        assertSoftly(subscriber.receivedEvents[DELETE]) {
            this?.isDelete() shouldBe true
            this?.entities?.values shouldContainOnly setOf(customer, customer2)
        }
        subscriber.createEventEntities.get() shouldBe 2
        subscriber.deletedEventEntities.get() shouldBe 2

        repository.create(customer.id, customer.name)
        repository.findById(customer.id) shouldBePresent { it shouldBe customer }

        testDispatcher.scheduler.advanceUntilIdle()

        assertSoftly(subscriber.receivedEvents[READ]) {
            this?.isRead() shouldBe true
            this?.entities?.values shouldContainOnly setOf(customer)
        }
        subscriber.createEventEntities.get() shouldBe 3
        subscriber.deletedEventEntities.get() shouldBe 2

        repository.clear()

        testDispatcher.scheduler.advanceUntilIdle()

        assertSoftly(subscriber.receivedEvents[DELETE]) {
            this?.isDelete() shouldBe true
            this?.entities?.values.shouldContainOnly(customer)
        }
        subscriber.createEventEntities.get() shouldBe 3
        subscriber.deletedEventEntities.get() shouldBe 3
    }

    test("Repository disableEvents method prevents events from being published") {
        val customer = arbitraryCustomer().next()

        repository.create(customer.id, customer.name)

        testDispatcher.scheduler.advanceUntilIdle()

        subscriber.receivedEvents[CREATE] shouldNotBe null
        subscriber.createEventEntities.get() shouldBe 1

        subscriber.receivedEvents.clear()
        subscriber.createEventEntities.set(0)

        repository.disableEvents(CREATE)

        val customer2 = arbitraryCustomer().next()
        repository.create(customer2.id, customer2.name)

        testDispatcher.scheduler.advanceUntilIdle()

        subscriber.receivedEvents[CREATE] shouldBe null
        subscriber.createEventEntities.get() shouldBe 0

        repository.activateEvents(CREATE)
        val customer3 = arbitraryCustomer().next()
        repository.create(customer3.id, customer3.name)

        testDispatcher.scheduler.advanceUntilIdle()

        subscriber.receivedEvents[CREATE] shouldNotBe null
        subscriber.createEventEntities.get() shouldBe 1
    }

    test("LirpEventSubscriber error and complete actions are triggered correctly") {
        val errorFired = AtomicInteger(0)
        val completeFired = AtomicInteger(0)
        val errorMsg = mutableListOf<String>()

        val testSubscriber =
            object : LirpEventSubscriberBase<Customer, CrudEvent.Type, CrudEvent<Int, Customer>>("ErrorCompleteSubscriber") {
                init {
                    addOnNextEventAction(CREATE) { /* Just observe */ }

                    addOnErrorEventAction { error ->
                        errorFired.incrementAndGet()
                        errorMsg.add(error.message ?: "Unknown error")
                    }

                    addOnCompleteEventAction {
                        completeFired.incrementAndGet()
                    }
                }
            }

        repository.subscribe(testSubscriber)

        val testError = RuntimeException("Test error message")
        testSubscriber.onError(testError)

        errorFired.get() shouldBe 1
        errorMsg.first() shouldBe "Test error message"

        testSubscriber.onComplete()

        completeFired.get() shouldBe 1

        testSubscriber.clearSubscriptionActions()

        testSubscriber.onError(RuntimeException("Another error"))
        testSubscriber.onComplete()

        errorFired.get() shouldBe 1
        completeFired.get() shouldBe 1
    }

    test("Anonymous subscription test") {
        val createEventsReceived = AtomicInteger(0)
        val updateEventsReceived = AtomicInteger(0)
        val receivedCustomerIds = mutableSetOf<Int>()

        val createSubscription =
            repository.subscribe(CREATE) { event ->
                createEventsReceived.incrementAndGet()
                event.entities.keys.forEach { receivedCustomerIds.add(it) }
            }

        val updateSubscription =
            repository.subscribe(UPDATE) { event ->
                updateEventsReceived.incrementAndGet()
            }

        val customer = arbitraryCustomer().next()
        repository.create(customer.id, customer.name) shouldNotBe null

        testDispatcher.scheduler.advanceUntilIdle()

        createEventsReceived.get() shouldBe 1
        receivedCustomerIds shouldContainOnly setOf(customer.id)
        updateEventsReceived.get() shouldBe 0

        createSubscription.cancel()

        val customer2 = arbitraryCustomer().next()
        repository.create(customer2.id, customer2.name)

        testDispatcher.scheduler.advanceUntilIdle()

        createEventsReceived.get() shouldBe 1
        receivedCustomerIds shouldContainOnly setOf(customer.id)

        updateSubscription.cancel()
    }

    test("RegistryBase equals handles null and different types") {
        repository.equals(null) shouldBe false
        repository.equals("not a repository") shouldBe false
        repository.equals(repository) shouldBe true
    }

    test("RegistryBase hashCode is consistent with equals") {
        val ctx2 = LirpContext()
        val repository2 = CustomerVolatileRepo(ctx2)
        val customer = arbitraryCustomer(1).next()

        repository.create(customer.id, customer.name)
        repository2.create(customer.id, customer.name)

        repository.hashCode() shouldBe repository2.hashCode()
        ctx2.close()
    }

    test("VolatileRepository secondary constructor with name and initialEntities populates the repository") {
        val customer = Customer(1, "Alice")
        val repo = VolatileRepository<Int, Customer>("TestRepo", ConcurrentHashMap(mapOf(1 to customer)))

        repo.contains(1) shouldBe true
        repo.size() shouldBe 1

        repo.close()
        LirpContext.resetDefault()
    }

    test("VolatileRepository equals returns false for null and different type") {
        repository.equals(null) shouldBe false
        repository.equals("not a repository") shouldBe false
        repository.equals(repository) shouldBe true
    }

    test("VolatileRepository equals and hashCode are consistent for two repos with the same entities") {
        val ctx2 = LirpContext()
        val repo2 = CustomerVolatileRepo(ctx2)

        repository.create(42, "Bob")
        repo2.create(42, "Bob")

        repository.equals(repo2) shouldBe true
        repository.hashCode() shouldBe repo2.hashCode()

        ctx2.close()
    }

    test("VolatileRepository removeAll returns false when no entity in the collection is present") {
        val absent = Customer(99, "Ghost")

        val result = repository.removeAll(listOf(absent))

        result shouldBe false
    }

    test("RegistryBase secondary constructor with default context and publisher initializes correctly") {
        val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<Int, Customer>>("TestRegistry")
        val registry = object : RegistryBase<Int, Customer>(ConcurrentHashMap(), publisher) {}

        registry shouldNotBe null
        registry.isEmpty shouldBe true

        registry.close()
        LirpContext.resetDefault()
    }

    context("Mutable aggregate collection delegates") {

        test("entity retrieved by ID reflects mutable aggregate collection mutations") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)

            val t1 = trackRepo.create(1, "Track A")
            val t2 = trackRepo.create(2, "Track B")
            val playlist = playlistRepo.create(1L, "My Playlist")

            playlist.items.add(t1)
            playlist.items.add(t2)

            playlistRepo.findById(1L).shouldBePresent {
                it.itemIds shouldContainExactly listOf(1, 2)
                it.items.resolveAll() shouldContainExactly listOf(t1, t2)
            }
        }

        test("entity reflects remove and clear on mutable aggregate") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            trackRepo.create(3, "T3")
            val playlist = playlistRepo.create(1L, "Playlist", listOf(1, 2, 3))

            playlist.items.remove(t2)

            playlistRepo.findById(1L).shouldBePresent {
                it.itemIds shouldContainExactly listOf(1, 3)
            }

            playlist.items.clear()

            playlistRepo.findById(1L).shouldBePresent {
                it.itemIds shouldBe emptyList()
            }
        }

        test("addAll on mutable aggregate updates backing IDs for all elements") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist = playlistRepo.create(1L, "Bulk Add")

            playlist.items.addAll(listOf(t1, t2, t3))

            playlistRepo.findById(1L).shouldBePresent {
                it.itemIds shouldContainExactly listOf(1, 2, 3)
                it.items.resolveAll() shouldContainExactly listOf(t1, t2, t3)
            }
        }

        test("removeAll on mutable aggregate removes matching elements") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist = playlistRepo.create(1L, "Bulk Remove", listOf(1, 2, 3))

            playlist.items.removeAll(setOf(t1, t3))

            playlistRepo.findById(1L).shouldBePresent {
                it.itemIds shouldContainExactly listOf(2)
                it.items.resolveAll() shouldContainExactly listOf(t2)
            }
        }

        test("addAll on mutable aggregate emits exactly one MutationEvent") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Long, MutablePlaylist>>()
                )

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist = playlistRepo.create(1L, "Bulk Add")

            playlist.subscribe { events.add(it) }

            playlist.items.addAll(listOf(t1, t2, t3))
            testDispatcher.scheduler.advanceUntilIdle()

            events shouldHaveSize 1
        }

        test("removeAll on mutable aggregate emits exactly one MutationEvent") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Long, MutablePlaylist>>()
                )

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist = playlistRepo.create(1L, "Bulk Remove", listOf(1, 2, 3))

            playlist.subscribe { events.add(it) }

            playlist.items.removeAll(listOf(t1, t3))
            testDispatcher.scheduler.advanceUntilIdle()

            events shouldHaveSize 1
        }

        test("addAll with empty collection returns false and emits no event") {
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Long, MutablePlaylist>>()
                )

            val playlist = playlistRepo.create(1L, "Empty Add")

            playlist.subscribe { events.add(it) }

            val result = playlist.items.addAll(emptyList())
            testDispatcher.scheduler.advanceUntilIdle()

            result shouldBe false
            events shouldHaveSize 0
        }

        test("removeAll with no matching elements returns false and emits no event") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Long, MutablePlaylist>>()
                )

            val unrelated = trackRepo.create(99, "Unrelated")
            val playlist = playlistRepo.create(1L, "No Match Remove")

            playlist.subscribe { events.add(it) }

            val result = playlist.items.removeAll(listOf(unrelated))
            testDispatcher.scheduler.advanceUntilIdle()

            result shouldBe false
            events shouldHaveSize 0
        }

        test("addAll on mutable aggregate set emits exactly one MutationEvent") {
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val groupRepo = MutablePlaylistGroupVolatileRepo(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Long, MutablePlaylistGroup>>()
                )

            val p1 = playlistRepo.create(1L, "P1")
            val p2 = playlistRepo.create(2L, "P2")
            val p3 = playlistRepo.create(3L, "P3")
            val group = groupRepo.create(1L)

            group.subscribe { events.add(it) }

            group.playlists.addAll(listOf(p1, p2, p3))
            testDispatcher.scheduler.advanceUntilIdle()

            events shouldHaveSize 1
        }

        test("entity emits MutationEvent on add to mutable aggregate") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Long, MutablePlaylist>>()
                )

            val t1 = trackRepo.create(1, "Track")
            val playlist = playlistRepo.create(1L, "Test")

            playlist.subscribe { events.add(it) }

            playlist.items.add(t1)
            testDispatcher.scheduler.advanceUntilIdle()

            events shouldHaveSize 1
        }

        test("entity emits MutationEvent on remove from mutable aggregate") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Long, MutablePlaylist>>()
                )

            val t1 = trackRepo.create(1, "Track")
            val playlist = playlistRepo.create(1L, "Test", listOf(1))

            playlist.subscribe { events.add(it) }

            playlist.items.remove(t1)
            testDispatcher.scheduler.advanceUntilIdle()

            events shouldHaveSize 1
        }

        test("entity emits MutationEvent on clear of mutable aggregate") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Long, MutablePlaylist>>()
                )

            trackRepo.create(1, "T1")
            val playlist = playlistRepo.create(1L, "Test", listOf(1))

            playlist.subscribe { events.add(it) }

            playlist.items.clear()
            testDispatcher.scheduler.advanceUntilIdle()

            events shouldHaveSize 1
        }

        test("multiple entities with independent mutable aggregates") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)

            val t1 = trackRepo.create(1, "Track 1")
            val t2 = trackRepo.create(2, "Track 2")
            val t3 = trackRepo.create(3, "Track 3")
            val pl1 = playlistRepo.create(1L, "Playlist A")
            val pl2 = playlistRepo.create(2L, "Playlist B")

            pl1.items.addAll(listOf(t1, t2))
            pl2.items.addAll(listOf(t2, t3))

            playlistRepo.findById(1L).shouldBePresent { it.itemIds shouldContainExactly listOf(1, 2) }
            playlistRepo.findById(2L).shouldBePresent { it.itemIds shouldContainExactly listOf(2, 3) }
        }

        test("set-based mutable aggregate maintains uniqueness across repository operations") {
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val groupRepo = MutablePlaylistGroupVolatileRepo(ctx)

            val p1 = playlistRepo.create(1L, "P1")
            val p2 = playlistRepo.create(2L, "P2")
            val group = groupRepo.create(1L)

            group.playlists.add(p1)
            group.playlists.add(p2)
            group.playlists.add(p1) // duplicate

            groupRepo.findById(1L).shouldBePresent {
                it.playlistIds shouldContainExactlyInAnyOrder setOf(1L, 2L)
            }
        }

        test("retainAll on mutable aggregate emits exactly one MutationEvent") {
            val trackRepo = TestTrackVolatileRepo(ctx)
            val playlistRepo = MutablePlaylistVolatileRepo(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Long, MutablePlaylist>>()
                )

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist = playlistRepo.create(1L, "Retain", listOf(1, 2, 3))

            playlist.subscribe { events.add(it) }

            playlist.items.retainAll(listOf(t2))
            testDispatcher.scheduler.advanceUntilIdle()

            events shouldHaveSize 1
        }
    }
})
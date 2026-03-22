package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.Person
import net.transgressoft.lirp.PersonVolatileRepo
import net.transgressoft.lirp.Personly
import net.transgressoft.lirp.arbitraryPerson
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.CrudEvent.Type.DELETE
import net.transgressoft.lirp.event.CrudEvent.Type.READ
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.LirpEventSubscriberBase
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@ExperimentalCoroutinesApi
internal class VolatileRepositoryTest : StringSpec({

    class SomeClassSubscribedToEvents() : LirpEventSubscriberBase<Personly, CrudEvent.Type, CrudEvent<Int, Personly>>("Some Name") {
        val createEventEntities = AtomicInteger(0)
        val deletedEventEntities = AtomicInteger(0)
        val receivedEvents = mutableMapOf<EventType, CrudEvent<Int, Personly>>()

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
    lateinit var repository: PersonVolatileRepo
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
            PersonVolatileRepo(ctx).apply {
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

    "Repository reflects addition and deletion of entities" {
        checkAll(arbitraryPerson()) { person ->
            repository.isEmpty shouldBe true
            repository.create(person) shouldNotBe null
            repository.isEmpty shouldBe false
            repository.findById(person.id) shouldBe Optional.of(person)
            repository.findByUniqueId(person.uniqueId) shouldBePresent { it shouldBe person }
            repository.search { it.money == person.money }.shouldContainOnly(person)
            repository.contains(person.id) shouldBe true
            repository.contains { it == person } shouldBe true

            repository.size() shouldBe 1

            repository.remove(person) shouldBe true
            repository.isEmpty shouldBe true
        }
    }

    "Registry iterates over all entities via Iterable" {
        val people = Arb.set(arbitraryPerson(), 3..3).next()
        people.forEach(repository::create)

        val iterated = mutableSetOf<Person>()
        for (entity in repository) {
            iterated.add(entity as Person)
        }

        iterated shouldContainOnly people
    }

    "Repository publishes CRUD events received by a subscriber" {
        val person = arbitraryPerson().next()
        val person2 = arbitraryPerson().next()
        repository.create(person)
        repository.create(person2)

        testDispatcher.scheduler.advanceUntilIdle()

        // Two separate create() calls produce two separate CREATE events;
        // receivedEvents[CREATE] holds only the last one, so check count via createEventEntities
        subscriber.receivedEvents[CREATE]?.isCreate() shouldBe true
        subscriber.createEventEntities.get() shouldBe 2
        subscriber.deletedEventEntities.get() shouldBe 0

        repository.removeAll(setOf(person, person2)) shouldBe true

        testDispatcher.scheduler.advanceUntilIdle()

        assertSoftly(subscriber.receivedEvents[DELETE]) {
            this?.isDelete() shouldBe true
            this?.entities?.values shouldContainOnly setOf(person, person2)
        }
        subscriber.createEventEntities.get() shouldBe 2
        subscriber.deletedEventEntities.get() shouldBe 2

        repository.create(person)
        repository.findById(person.id) shouldBePresent { it shouldBe person }

        testDispatcher.scheduler.advanceUntilIdle()

        assertSoftly(subscriber.receivedEvents[READ]) {
            this?.isRead() shouldBe true
            this?.entities?.values shouldContainOnly setOf(person)
        }
        subscriber.createEventEntities.get() shouldBe 3
        subscriber.deletedEventEntities.get() shouldBe 2

        repository.clear()

        testDispatcher.scheduler.advanceUntilIdle()

        assertSoftly(subscriber.receivedEvents[DELETE]) {
            this?.isDelete() shouldBe true
            this?.entities?.values.shouldContainOnly(person)
        }
        subscriber.createEventEntities.get() shouldBe 3
        subscriber.deletedEventEntities.get() shouldBe 3
    }

    "Repository disableEvents method prevents events from being published" {
        val person = arbitraryPerson().next()

        repository.create(person)

        testDispatcher.scheduler.advanceUntilIdle()

        subscriber.receivedEvents[CREATE] shouldNotBe null
        subscriber.createEventEntities.get() shouldBe 1

        subscriber.receivedEvents.clear()
        subscriber.createEventEntities.set(0)

        repository.disableEvents(CREATE)

        val person2 = arbitraryPerson().next()
        repository.create(person2)

        testDispatcher.scheduler.advanceUntilIdle()

        subscriber.receivedEvents[CREATE] shouldBe null
        subscriber.createEventEntities.get() shouldBe 0

        repository.activateEvents(CREATE)
        val person3 = arbitraryPerson().next()
        repository.create(person3)

        testDispatcher.scheduler.advanceUntilIdle()

        subscriber.receivedEvents[CREATE] shouldNotBe null
        subscriber.createEventEntities.get() shouldBe 1
    }

    "LirpEventSubscriber error and complete actions are triggered correctly" {
        val errorFired = AtomicInteger(0)
        val completeFired = AtomicInteger(0)
        val errorMsg = mutableListOf<String>()

        val testSubscriber =
            object : LirpEventSubscriberBase<Personly, CrudEvent.Type, CrudEvent<Int, Personly>>("ErrorCompleteSubscriber") {
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

    "Anonymous subscription test" {
        val createEventsReceived = AtomicInteger(0)
        val updateEventsReceived = AtomicInteger(0)
        val receivedPersonIds = mutableSetOf<Int>()

        val createSubscription =
            repository.subscribe(CREATE) { event ->
                createEventsReceived.incrementAndGet()
                event.entities.keys.forEach { receivedPersonIds.add(it) }
            }

        val updateSubscription =
            repository.subscribe(UPDATE) { event ->
                updateEventsReceived.incrementAndGet()
            }

        val person = arbitraryPerson().next()
        repository.create(person) shouldNotBe null

        testDispatcher.scheduler.advanceUntilIdle()

        createEventsReceived.get() shouldBe 1
        receivedPersonIds shouldContainOnly setOf(person.id)
        updateEventsReceived.get() shouldBe 0

        createSubscription.cancel()

        val person2 = arbitraryPerson().next()
        repository.create(person2)

        testDispatcher.scheduler.advanceUntilIdle()

        createEventsReceived.get() shouldBe 1
        receivedPersonIds shouldContainOnly setOf(person.id)

        updateSubscription.cancel()
    }

    "RegistryBase equals handles null and different types" {
        repository.equals(null) shouldBe false
        repository.equals("not a repository") shouldBe false
        repository.equals(repository) shouldBe true
    }

    "RegistryBase hashCode is consistent with equals" {
        val ctx2 = LirpContext()
        val repository2 = PersonVolatileRepo(ctx2)
        val person = arbitraryPerson(1).next()

        repository.create(person)
        repository2.create(person)

        repository.hashCode() shouldBe repository2.hashCode()
        ctx2.close()
    }
})
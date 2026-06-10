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
import net.transgressoft.lirp.testing.arbitraryAudioItem
import net.transgressoft.lirp.testing.reactiveScope
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
import io.kotest.property.arbitrary.set
import io.kotest.property.checkAll
import java.util.Collections
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class VolatileRepositoryTest : FunSpec({

    class SomeClassSubscribedToEvents() : LirpEventSubscriberBase<AudioItem, CrudEvent.Type, CrudEvent<Int, AudioItem>>("Some Name") {
        val createEventEntities = AtomicInteger(0)
        val deletedEventEntities = AtomicInteger(0)
        val receivedEvents = mutableMapOf<EventType, CrudEvent<Int, AudioItem>>()

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
    lateinit var repository: AudioItemVolatileRepository
    lateinit var subscriber: SomeClassSubscribedToEvents

    val reactive = reactiveScope()

    beforeTest {
        ctx = LirpContext()
        repository =
            AudioItemVolatileRepository(ctx).apply {
                activateEvents(READ)
            }
        subscriber = SomeClassSubscribedToEvents()
        repository.subscribe(subscriber)
    }

    afterTest {
        ctx.close()
    }

    test("Repository reflects addition and deletion of entities") {
        checkAll(arbitraryAudioItem()) { audioItem ->
            repository.isEmpty shouldBe true
            repository.create(audioItem.id, audioItem.title) shouldNotBe null
            repository.isEmpty shouldBe false
            repository.findById(audioItem.id) shouldBe Optional.of(audioItem)
            repository.findByUniqueId(audioItem.uniqueId) shouldBePresent { it shouldBe audioItem }
            repository.search { it.title == audioItem.title }.shouldContainOnly(audioItem)
            repository.contains(audioItem.id) shouldBe true
            repository.contains { it == audioItem } shouldBe true

            repository.size() shouldBe 1

            repository.remove(audioItem) shouldBe true
            repository.isEmpty shouldBe true
        }
    }

    test("Registry iterates over all entities via Iterable") {
        val audioItems = Arb.set(arbitraryAudioItem(), 3..3).next()
        audioItems.forEach { repository.create(it.id, it.title) }

        val iterated = mutableSetOf<AudioItem>()
        repository.forEach(iterated::add)

        iterated shouldContainOnly audioItems
    }

    test("Repository publishes CRUD events received by a subscriber") {
        val audioItem = arbitraryAudioItem().next()
        val audioItem2 = arbitraryAudioItem().next()
        repository.create(audioItem.id, audioItem.title)
        repository.create(audioItem2.id, audioItem2.title)

        reactive.advance()

        // Two separate create() calls produce two separate CREATE events;
        // receivedEvents[CREATE] holds only the last one, so check count via createEventEntities
        subscriber.receivedEvents[CREATE]?.isCreate() shouldBe true
        subscriber.createEventEntities.get() shouldBe 2
        subscriber.deletedEventEntities.get() shouldBe 0

        repository.removeAll(setOf(audioItem, audioItem2)) shouldBe true

        reactive.advance()

        assertSoftly(subscriber.receivedEvents[DELETE]) {
            this?.isDelete() shouldBe true
            this?.entities?.values shouldContainOnly setOf(audioItem, audioItem2)
        }
        subscriber.createEventEntities.get() shouldBe 2
        subscriber.deletedEventEntities.get() shouldBe 2

        repository.create(audioItem.id, audioItem.title)
        repository.findById(audioItem.id) shouldBePresent { it shouldBe audioItem }

        reactive.advance()

        assertSoftly(subscriber.receivedEvents[READ]) {
            this?.isRead() shouldBe true
            this?.entities?.values shouldContainOnly setOf(audioItem)
        }
        subscriber.createEventEntities.get() shouldBe 3
        subscriber.deletedEventEntities.get() shouldBe 2

        repository.clear()

        reactive.advance()

        assertSoftly(subscriber.receivedEvents[DELETE]) {
            this?.isDelete() shouldBe true
            this?.entities?.values.shouldContainOnly(audioItem)
        }
        subscriber.createEventEntities.get() shouldBe 3
        subscriber.deletedEventEntities.get() shouldBe 3
    }

    test("Repository disableEvents method prevents events from being published") {
        val audioItem = arbitraryAudioItem().next()

        repository.create(audioItem.id, audioItem.title)

        reactive.advance()

        subscriber.receivedEvents[CREATE] shouldNotBe null
        subscriber.createEventEntities.get() shouldBe 1

        subscriber.receivedEvents.clear()
        subscriber.createEventEntities.set(0)

        repository.disableEvents(CREATE)

        val audioItem2 = arbitraryAudioItem().next()
        repository.create(audioItem2.id, audioItem2.title)

        reactive.advance()

        subscriber.receivedEvents[CREATE] shouldBe null
        subscriber.createEventEntities.get() shouldBe 0

        repository.activateEvents(CREATE)
        val audioItem3 = arbitraryAudioItem().next()
        repository.create(audioItem3.id, audioItem3.title)

        reactive.advance()

        subscriber.receivedEvents[CREATE] shouldNotBe null
        subscriber.createEventEntities.get() shouldBe 1
    }

    test("LirpEventSubscriber error and complete actions are triggered correctly") {
        val errorFired = AtomicInteger(0)
        val completeFired = AtomicInteger(0)
        val errorMsg = mutableListOf<String>()

        val testSubscriber =
            object : LirpEventSubscriberBase<AudioItem, CrudEvent.Type, CrudEvent<Int, AudioItem>>("ErrorCompleteSubscriber") {
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
        val receivedAudioItemIds = mutableSetOf<Int>()

        val createSubscription =
            repository.subscribe(CREATE) { event ->
                createEventsReceived.incrementAndGet()
                event.entities.keys.forEach { receivedAudioItemIds.add(it) }
            }

        val updateSubscription =
            repository.subscribe(UPDATE) { event ->
                updateEventsReceived.incrementAndGet()
            }

        val audioItem = arbitraryAudioItem().next()
        repository.create(audioItem.id, audioItem.title) shouldNotBe null

        reactive.advance()

        createEventsReceived.get() shouldBe 1
        receivedAudioItemIds shouldContainOnly setOf(audioItem.id)
        updateEventsReceived.get() shouldBe 0

        createSubscription.cancel()

        val audioItem2 = arbitraryAudioItem().next()
        repository.create(audioItem2.id, audioItem2.title)

        reactive.advance()

        createEventsReceived.get() shouldBe 1
        receivedAudioItemIds shouldContainOnly setOf(audioItem.id)

        updateSubscription.cancel()
    }

    test("VolatileRepository equals and hashCode are symmetric across types, instances, and content") {
        val ctx2 = LirpContext()
        val repository2 = AudioItemVolatileRepository(ctx2)
        val audioItem = arbitraryAudioItem(1).next()

        repository.create(audioItem.id, audioItem.title)
        repository2.create(audioItem.id, audioItem.title)

        // Reflexive and symmetric equals
        repository.equals(repository) shouldBe true
        repository.equals(null) shouldBe false
        repository.equals("not a repository") shouldBe false

        // Two repos with the same entities are equal and share the same hashCode
        repository.equals(repository2) shouldBe true
        repository.hashCode() shouldBe repository2.hashCode()

        ctx2.close()
    }

    test("VolatileRepository secondary constructor with name and initialEntities populates the repository") {
        val audioItem = MutableAudioItem(1, "Track Alpha")
        val repo = VolatileRepository<Int, AudioItem>("TestRepo", ConcurrentHashMap(mapOf(1 to audioItem as AudioItem)))

        repo.contains(1) shouldBe true
        repo.size() shouldBe 1

        repo.close()
        LirpContext.resetDefault()
    }

    test("VolatileRepository removeAll returns false when no entity in the collection is present") {
        val absent = MutableAudioItem(99, "Ghost Track")

        val result = repository.removeAll(listOf(absent))

        result shouldBe false
    }

    test("RegistryBase secondary constructor with default context and publisher initializes correctly") {
        val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<Int, AudioItem>>("TestRegistry")
        val registry = object : RegistryBase<Int, AudioItem>(ConcurrentHashMap(), publisher) {}

        registry shouldNotBe null
        registry.isEmpty shouldBe true

        registry.close()
        LirpContext.resetDefault()
    }

    context("Mutable aggregate collection delegates") {

        test("entity retrieved by ID reflects mutable aggregate collection mutations") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)

            val t1 = trackRepo.create(1, "Track A")
            val t2 = trackRepo.create(2, "Track B")
            val playlist = DefaultAudioPlaylist(1, "My Playlist").also(playlistRepo::add)

            playlist.audioItems.add(t1)
            playlist.audioItems.add(t2)

            playlistRepo.findById(1).shouldBePresent {
                (it as DefaultAudioPlaylist).audioItems.referenceIds shouldContainExactly listOf(1, 2)
                it.audioItems.resolveAll() shouldContainExactly listOf(t1, t2)
            }
        }

        test("entity reflects remove and clear on mutable aggregate") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            trackRepo.create(3, "T3")
            val playlist =
                DefaultAudioPlaylist(1, "Playlist", listOf(1, 2, 3))
                    .also(playlistRepo::add)

            playlist.audioItems.remove(t2)

            playlistRepo.findById(1).shouldBePresent {
                (it as DefaultAudioPlaylist).audioItems.referenceIds shouldContainExactly listOf(1, 3)
            }

            playlist.audioItems.clear()

            playlistRepo.findById(1).shouldBePresent {
                (it as DefaultAudioPlaylist).audioItems.referenceIds shouldBe emptyList()
            }
        }

        test("addAll on mutable aggregate updates backing IDs for all elements") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist = DefaultAudioPlaylist(1, "Bulk Add").also(playlistRepo::add)

            playlist.audioItems.addAll(listOf(t1, t2, t3))

            playlistRepo.findById(1).shouldBePresent {
                (it as DefaultAudioPlaylist).audioItems.referenceIds shouldContainExactly listOf(1, 2, 3)
                it.audioItems.resolveAll() shouldContainExactly listOf(t1, t2, t3)
            }
        }

        test("removeAll on mutable aggregate removes matching elements") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist =
                DefaultAudioPlaylist(1, "Bulk Remove", listOf(1, 2, 3))
                    .also(playlistRepo::add)

            playlist.audioItems.removeAll(setOf(t1, t3))

            playlistRepo.findById(1).shouldBePresent {
                (it as DefaultAudioPlaylist).audioItems.referenceIds shouldContainExactly listOf(2)
                it.audioItems.resolveAll() shouldContainExactly listOf(t2)
            }
        }

        test("addAll on mutable aggregate emits exactly one MutationEvent") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Int, MutableAudioPlaylist>>()
                )

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist = DefaultAudioPlaylist(1, "Bulk Add").also(playlistRepo::add)

            playlist.subscribe { events.add(it) }

            playlist.audioItems.addAll(listOf(t1, t2, t3))
            reactive.advance()

            events shouldHaveSize 1
        }

        test("removeAll on mutable aggregate emits exactly one MutationEvent") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Int, MutableAudioPlaylist>>()
                )

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist =
                DefaultAudioPlaylist(1, "Bulk Remove", listOf(1, 2, 3))
                    .also(playlistRepo::add)

            playlist.subscribe { events.add(it) }

            playlist.audioItems.removeAll(listOf(t1, t3))
            reactive.advance()

            events shouldHaveSize 1
        }

        test("addAll with empty collection returns false and emits no event") {
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Int, MutableAudioPlaylist>>()
                )

            val playlist = DefaultAudioPlaylist(1, "Empty Add").also(playlistRepo::add)

            playlist.subscribe { events.add(it) }

            val result = playlist.audioItems.addAll(emptyList())
            reactive.advance()

            result shouldBe false
            events shouldHaveSize 0
        }

        test("removeAll with no matching elements returns false and emits no event") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Int, MutableAudioPlaylist>>()
                )

            val unrelated = trackRepo.create(99, "Unrelated")
            val playlist = DefaultAudioPlaylist(1, "No Match Remove").also(playlistRepo::add)

            playlist.subscribe { events.add(it) }

            val result = playlist.audioItems.removeAll(listOf(unrelated))
            reactive.advance()

            result shouldBe false
            events shouldHaveSize 0
        }

        test("addAll on mutable aggregate set emits exactly one MutationEvent") {
            val sharedRepo = AudioPlaylistVolatileRepository(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Int, MutableAudioPlaylist>>()
                )

            val p1 = DefaultAudioPlaylist(1, "P1").also(sharedRepo::add)
            val p2 = DefaultAudioPlaylist(2, "P2").also(sharedRepo::add)
            val p3 = DefaultAudioPlaylist(3, "P3").also(sharedRepo::add)
            val group = DefaultAudioPlaylist(100, "Group").also(sharedRepo::add)

            group.subscribe { events.add(it) }

            group.playlists.addAll(listOf(p1, p2, p3))
            reactive.advance()

            events shouldHaveSize 1
        }

        test("entity emits MutationEvent on add to mutable aggregate") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Int, MutableAudioPlaylist>>()
                )

            val t1 = trackRepo.create(1, "Track")
            val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

            playlist.subscribe { events.add(it) }

            playlist.audioItems.add(t1)
            reactive.advance()

            events shouldHaveSize 1
        }

        test("entity emits MutationEvent on remove from mutable aggregate") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Int, MutableAudioPlaylist>>()
                )

            val t1 = trackRepo.create(1, "Track")
            val playlist =
                DefaultAudioPlaylist(1, "Test", listOf(1))
                    .also(playlistRepo::add)

            playlist.subscribe { events.add(it) }

            playlist.audioItems.remove(t1)
            reactive.advance()

            events shouldHaveSize 1
        }

        test("entity emits MutationEvent on clear of mutable aggregate") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Int, MutableAudioPlaylist>>()
                )

            trackRepo.create(1, "T1")
            val playlist =
                DefaultAudioPlaylist(1, "Test", listOf(1))
                    .also(playlistRepo::add)

            playlist.subscribe { events.add(it) }

            playlist.audioItems.clear()
            reactive.advance()

            events shouldHaveSize 1
        }

        test("multiple entities with independent mutable aggregates") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)

            val t1 = trackRepo.create(1, "Track 1")
            val t2 = trackRepo.create(2, "Track 2")
            val t3 = trackRepo.create(3, "Track 3")
            val pl1 = DefaultAudioPlaylist(1, "Playlist A").also(playlistRepo::add)
            val pl2 = DefaultAudioPlaylist(2, "Playlist B").also(playlistRepo::add)

            pl1.audioItems.addAll(listOf(t1, t2))
            pl2.audioItems.addAll(listOf(t2, t3))

            playlistRepo.findById(1).shouldBePresent { (it as DefaultAudioPlaylist).audioItems.referenceIds shouldContainExactly listOf(1, 2) }
            playlistRepo.findById(2).shouldBePresent { (it as DefaultAudioPlaylist).audioItems.referenceIds shouldContainExactly listOf(2, 3) }
        }

        test("set-based mutable aggregate maintains uniqueness across repository operations") {
            val sharedRepo = AudioPlaylistVolatileRepository(ctx)

            val p1 = DefaultAudioPlaylist(1, "P1").also(sharedRepo::add)
            val p2 = DefaultAudioPlaylist(2, "P2").also(sharedRepo::add)
            val group = DefaultAudioPlaylist(100, "Group").also(sharedRepo::add)

            group.playlists.add(p1)
            group.playlists.add(p2)
            group.playlists.add(p1) // duplicate

            sharedRepo.findById(100).shouldBePresent {
                (it as DefaultAudioPlaylist).playlists.referenceIds shouldContainExactlyInAnyOrder setOf(1, 2)
            }
        }

        test("retainAll on mutable aggregate emits exactly one MutationEvent") {
            val trackRepo = repository
            val playlistRepo = AudioPlaylistVolatileRepository(ctx)
            val events =
                Collections.synchronizedList(
                    mutableListOf<MutationEvent<Int, MutableAudioPlaylist>>()
                )

            val t1 = trackRepo.create(1, "T1")
            val t2 = trackRepo.create(2, "T2")
            val t3 = trackRepo.create(3, "T3")
            val playlist =
                DefaultAudioPlaylist(1, "Retain", listOf(1, 2, 3))
                    .also(playlistRepo::add)

            playlist.subscribe { events.add(it) }

            playlist.audioItems.retainAll(listOf(t2))
            reactive.advance()

            events shouldHaveSize 1
        }
    }
})
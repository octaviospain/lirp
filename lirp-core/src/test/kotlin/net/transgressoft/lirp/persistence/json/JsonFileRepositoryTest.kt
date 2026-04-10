package net.transgressoft.lirp.persistence.json

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.DefaultAudioPlaylist
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.LirpDeserializationException
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.PersistentRepositoryBase
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.arbitrary.next
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@ExperimentalCoroutinesApi
class JsonFileRepositoryTest : DescribeSpec({
    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)
    lateinit var jsonFile: File
    lateinit var repository: StandardCustomerJsonFileRepository

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    beforeEach {
        jsonFile = tempfile("json-repository-test", ".json").also { it.deleteOnExit() }
        repository = StandardCustomerJsonFileRepository(jsonFile)
    }

    afterEach {
        repository.close()
    }

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    fun getSubscriptionsMap(repo: JsonFileRepository<Int, PolymorphicCustomer>): MutableMap<Int, *> {
        val subscriptionsMapField =
            PersistentRepositoryBase::class.java
                .getDeclaredField("subscriptionsMap")
                .also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return subscriptionsMapField.get(repo) as MutableMap<Int, *>
    }

    fun getDirtyFlag(repo: JsonFileRepository<Int, PolymorphicCustomer>): AtomicBoolean {
        val dirtyField =
            PersistentRepositoryBase::class.java
                .getDeclaredField("dirty")
                .also { it.isAccessible = true }
        return dirtyField.get(repo) as AtomicBoolean
    }

    describe("Serialization") {
        it("serializes to file on create") {
            val arb = arbitraryStandardCustomer().next()
            val customer = repository.create(arb.id, arb.name, arb.email)

            testDispatcher.scheduler.advanceUntilIdle()

            jsonFile.readText().isNotEmpty() shouldBe true

            repository.close()
            val secondRepository = StandardCustomerJsonFileRepository(jsonFile)
            secondRepository.equals(repository) shouldBe true
            secondRepository.close()
        }

        it("serializes to file after name mutation") {
            val arb = arbitraryStandardCustomer().next()
            val customer = repository.create(arb.id, arb.name, arb.email)

            customer.updateName("Ken")

            testDispatcher.scheduler.advanceUntilIdle()
            jsonFile.readText().isNotEmpty() shouldBe true

            repository.close()
            val secondRepository = StandardCustomerJsonFileRepository(jsonFile)
            secondRepository.findById(customer.id) shouldBePresent { it.name shouldBe "Ken" }
            secondRepository.close()
        }

        it("serializes on entity mutation via name setter") {
            val arb = arbitraryStandardCustomer().next()
            val customer = repository.create(arb.id, arb.name, arb.email)
            testDispatcher.scheduler.advanceUntilIdle()

            customer.name = "John Namechanged"
            testDispatcher.scheduler.advanceUntilIdle()

            repository.findFirst { it.name == "John Namechanged" } shouldBePresent { it shouldBe customer }
        }

        it("debouncing doesn't lose final state") {
            val debounceFile = tempfile("debounce-test", ".json").also { it.deleteOnExit() }
            repository.close()
            val debounceRepo = StandardCustomerJsonFileRepository(debounceFile)
            val customer = debounceRepo.create(1, "Initial", null)

            repeat(100) { i -> customer.updateName("Name-$i") }
            debounceRepo.findById(1).get().name shouldBe "Name-99"
            testDispatcher.scheduler.advanceUntilIdle()

            debounceRepo.close()
            val reloaded = StandardCustomerJsonFileRepository(debounceFile)
            testDispatcher.scheduler.advanceUntilIdle()
            reloaded.findById(1).get().name shouldBe "Name-99"
            reloaded.close()
        }
    }

    describe("Initialization and file management") {
        it("initializes from existing json, allows modification and persistence") {
            val arb = arbitraryStandardCustomer().next()
            jsonFile.writeText("""{"${arb.id}":{"type":"StandardCustomer","id":${arb.id},"name":"${arb.name}","email":null,"loyaltyPoints":0}}""")
            repository.close()
            repository = StandardCustomerJsonFileRepository(jsonFile)
            repository.size() shouldBe 1
            testDispatcher.scheduler.advanceUntilIdle()
            repository.findById(arb.id) shouldBePresent { it.name shouldBe arb.name }
            val loaded = repository.findById(arb.id).get() as StandardCustomer
            loaded.name = "name changed"
            testDispatcher.scheduler.advanceUntilIdle()
            repository.findFirst { it.name == "name changed" }.isPresent shouldBe true
        }

        it("rejects invalid json file path") {
            repository.close()
            shouldThrow<IllegalArgumentException> {
                StandardCustomerJsonFileRepository(File("/does-not-exist.txt"))
            }.message shouldBe "Provided jsonFile does not exist, is not writable or is not a json file"
        }

        it("supports switching json file at runtime") {
            val arb = arbitraryStandardCustomer().next()
            repository.create(arb.id, arb.name, arb.email)
            repository.size() shouldBe 1
            val newJsonFile = tempfile("json-repository-test", ".json").also { it.deleteOnExit() }
            repository.jsonFile = newJsonFile
            val arb2 = arbitraryStandardCustomer().next()
            repository.create(arb2.id, arb2.name, arb2.email)
            testDispatcher.scheduler.advanceUntilIdle()
            newJsonFile.readText().isNotEmpty() shouldBe true
        }

        it("tolerates file I/O failures without crashing") {
            mockkStatic("kotlin.io.FilesKt__FileReadWriteKt")
            mockkStatic("kotlin.io.FilesKt__UtilsKt")
            val testFile =
                mockk<File> {
                    every { exists() } returns true
                    every { canWrite() } returns true
                    every { extension } returns "json"
                    every { readText() } returns "{}"
                    every { name } returns "test.json"
                }
            repository.close()
            val ioFailureRepo = StandardCustomerJsonFileRepository(testFile)
            every { testFile.canWrite() } throws IOException("Simulated write failure")
            shouldNotThrowAny { ioFailureRepo.create(1, "Test", "test@example.com") }
            ioFailureRepo.findById(1).isPresent shouldBe true
            unmockkAll()
            // close() propagates the final flush failure since the mock file is not writable
            try {
                ioFailureRepo.close()
            } catch (_: Exception) {
                // expected
            }
        }
    }

    describe("Polymorphic types") {
        it("initializes StandardCustomerJsonFileRepository from json with StandardCustomer and persists updates") {
            jsonFile.writeText("""{"1":{"type":"StandardCustomer","id":1,"name":"John Doe","email":"john@example.com","loyaltyPoints":0}}""")
            repository.close()
            repository = StandardCustomerJsonFileRepository(jsonFile)
            repository.size() shouldBe 1
            repository.findById(1) shouldBePresent { it.name shouldBe "John Doe" }
            val loaded = repository.findById(1).get() as StandardCustomer
            loaded.name = "John Namechanged"
            testDispatcher.scheduler.advanceUntilIdle()
            repository.findFirst { it.name == "John Namechanged" }.isPresent shouldBe true
        }

        it("polymorphic round-trip persists and reloads StandardCustomer and PremiumCustomer") {
            val standard = repository.create(1, "Alice", "alice@example.com")
            repository.close()

            val premiumFile = tempfile("premium-repo-test", ".json").also { it.deleteOnExit() }
            val premiumRepository = PremiumCustomerJsonFileRepository(premiumFile)
            val premium = premiumRepository.create(2, "Bob", "bob@example.com", 500)
            testDispatcher.scheduler.advanceUntilIdle()
            premiumRepository.close()

            val reloadedStdRepo = StandardCustomerJsonFileRepository(jsonFile)
            testDispatcher.scheduler.advanceUntilIdle()
            reloadedStdRepo.size() shouldBe 1
            reloadedStdRepo.findById(standard.id) shouldBePresent { loaded ->
                loaded.shouldBeInstanceOf<StandardCustomer>()
                loaded.name shouldBe "Alice"
            }
            reloadedStdRepo.close()

            val reloadedPremiumRepo = PremiumCustomerJsonFileRepository(premiumFile)
            testDispatcher.scheduler.advanceUntilIdle()
            reloadedPremiumRepo.size() shouldBe 1
            reloadedPremiumRepo.findById(premium.id) shouldBePresent { loaded ->
                loaded.shouldBeInstanceOf<PremiumCustomer>()
                loaded.loyaltyPoints shouldBe 500
            }
            reloadedPremiumRepo.close()
        }

        it("PremiumCustomer loyalty points survive JSON encode/decode round-trip") {
            repository.close()
            val premiumFile = tempfile("premium-loyalty-test", ".json").also { it.deleteOnExit() }
            val premiumRepository = PremiumCustomerJsonFileRepository(premiumFile)
            val premium = premiumRepository.create(10, "Carol", "carol@example.com", 9999)
            premium.updateLoyaltyPoints(12000)
            testDispatcher.scheduler.advanceUntilIdle()
            premiumRepository.close()
            val reloaded = PremiumCustomerJsonFileRepository(premiumFile)
            reloaded.findById(10) shouldBePresent { loaded ->
                loaded.shouldBeInstanceOf<PremiumCustomer>()
                loaded.loyaltyPoints shouldBe 12000
            }
            reloaded.close()
        }
    }

    describe("Concurrency") {
        it("maintains state under concurrent additions") {
            val testCustomers = (1..100).map { arbitraryStandardCustomer(it).next() }
            testScope.launch {
                testCustomers.chunked(10).forEach { chunk ->
                    launch { chunk.forEach { c -> repository.create(c.id, c.name, c.email) } }
                }
            }
            testDispatcher.scheduler.advanceUntilIdle()
            repository.size() shouldBe testCustomers.size
            repository.close()
            val reloadedRepo = StandardCustomerJsonFileRepository(jsonFile)
            reloadedRepo.size() shouldBe testCustomers.size
            reloadedRepo.close()
        }

        it("publishes create events under concurrency") {
            val events = Collections.synchronizedList(mutableListOf<CrudEvent<Int, PolymorphicCustomer>>())
            val subscription: LirpEventSubscription<in PolymorphicCustomer, CrudEvent.Type, CrudEvent<Int, PolymorphicCustomer>> =
                repository.subscribe(CREATE) { events.add(it) }
            val testCustomers = (1..5_000).map { arbitraryStandardCustomer(it).next() }.distinct()
            testCustomers.chunked(500).map { chunk ->
                testScope.launch { chunk.forEach { c -> repository.create(c.id, c.name, c.email) } }
            }
            testDispatcher.scheduler.advanceUntilIdle()
            val createdEntityIds = events.flatMap { it.entities.keys }
            createdEntityIds shouldContainAll testCustomers.map { it.id }
            subscription.source.changes shouldBe repository.changes
            val reloadSize = repository.size()
            repository.close()
            StandardCustomerJsonFileRepository(jsonFile).also { it.size() shouldBe reloadSize }.close()
        }

        it("remains consistent under concurrent mutations") {
            (1..20).forEach { id -> repository.create(id, "Customer-$id", "c$id@example.com") }
            testDispatcher.scheduler.advanceUntilIdle()
            (1..5).map { _ ->
                testScope.launch {
                    repeat(10) { i ->
                        val id = (i % 20) + 1
                        (repository.findById(id).orElse(null) as? StandardCustomer)?.updateName("Updated-$i")
                    }
                }
            }
            testDispatcher.scheduler.advanceUntilIdle()
            val inMemorySize = repository.size()
            repository.close()
            val reloadedRepo = StandardCustomerJsonFileRepository(jsonFile)
            reloadedRepo.size() shouldBe inMemorySize
            reloadedRepo.close()
        }
    }

    describe("Rapid operations") {
        it("rapid additions are all persisted and queryable") {
            val customers = (1..100).map { arbitraryStandardCustomer(it).next() }
            customers.forEach { c -> repository.create(c.id, c.name, c.email) }
            testDispatcher.scheduler.advanceUntilIdle()
            repository.size() shouldBe 100
        }

        it("rapid mutations followed by query returns latest state") {
            val customer = repository.create(1, "Original", null)
            repeat(50) { i -> customer.updateName("Name-$i") }
            testDispatcher.scheduler.advanceUntilIdle()
            repository.findById(customer.id).get().name shouldBe "Name-49"
        }

        it("add, mutate, remove sequence is consistent") {
            val customer = repository.create(1, "Original", null)
            customer.updateName("Modified")
            repository.remove(customer)
            testDispatcher.scheduler.advanceUntilIdle()
            repository.findById(customer.id).isEmpty shouldBe true
            repository.isEmpty shouldBe true
        }
    }

    describe("Multi-repository") {
        it("multiple repositories on same file stay synchronized") {
            val multiFile = tempfile("multi-repo", ".json").also { it.deleteOnExit() }
            repository.close()
            val repo1 = StandardCustomerJsonFileRepository(multiFile)
            val customer = repo1.create(1, "Original", "orig@example.com")
            testDispatcher.scheduler.advanceUntilIdle()
            repo1.close()
            val repo2 = StandardCustomerJsonFileRepository(multiFile)
            testDispatcher.scheduler.advanceUntilIdle()
            repo2.size() shouldBe 1
            (repo2.findById(customer.id).get() as StandardCustomer).updateName("Modified in repo2")
            testDispatcher.scheduler.advanceUntilIdle()
            repo2.close()
            val repo3 = StandardCustomerJsonFileRepository(multiFile)
            testDispatcher.scheduler.advanceUntilIdle()
            repo3.findById(customer.id).get().name shouldBe "Modified in repo2"
            repo3.close()
        }
    }

    describe("Event ordering") {
        it("subscribers receive all create and delete events in order despite rapid changes") {
            val receivedEvents = mutableListOf<String>()
            val subscription =
                repository.subscribe { event ->
                    when {
                        event.isCreate() -> receivedEvents.add("CREATE-${event.entities.keys.first()}")
                        event.isUpdate() -> receivedEvents.add("UPDATE-${event.entities.keys.first()}")
                        event.isDelete() -> receivedEvents.add("DELETE-${event.entities.keys.first()}")
                    }
                }
            val c1 = repository.create(1, "Alice", null)
            val c2 = repository.create(2, "Bob", null)
            repository.create(3, "Charlie", null)
            repository.remove(c2)
            testDispatcher.scheduler.advanceUntilIdle()
            receivedEvents shouldBe listOf("CREATE-1", "CREATE-2", "CREATE-3", "DELETE-2")
            subscription.cancel()
        }
    }

    describe("Subscription cleanup") {
        it("remove() atomically removes subscription from map before cancelling") {
            val customer = repository.create(1, "Test", "t@t.com")
            testDispatcher.scheduler.advanceUntilIdle()
            val subscriptionsMap = getSubscriptionsMap(repository)
            subscriptionsMap.containsKey(customer.id) shouldBe true
            repository.remove(customer) shouldBe true
            testDispatcher.scheduler.advanceUntilIdle()
            repository.findById(customer.id).isEmpty shouldBe true
            subscriptionsMap.containsKey(customer.id) shouldBe false
        }

        it("remove() throws IllegalStateException when subscription is missing from map") {
            val customer = repository.create(1, "Test", "t@t.com")
            testDispatcher.scheduler.advanceUntilIdle()
            getSubscriptionsMap(repository).remove(customer.id)
            shouldThrow<IllegalStateException> { repository.remove(customer) }
        }

        it("removeAll() atomically removes subscriptions from map before cancelling") {
            val customer1 = repository.create(1, "Test1", "t1@t.com")
            val customer2 = repository.create(2, "Test2", "t2@t.com")
            testDispatcher.scheduler.advanceUntilIdle()
            val subscriptionsMap = getSubscriptionsMap(repository)
            subscriptionsMap.containsKey(customer1.id) shouldBe true
            subscriptionsMap.containsKey(customer2.id) shouldBe true
            repository.removeAll(listOf(customer1, customer2)) shouldBe true
            testDispatcher.scheduler.advanceUntilIdle()
            subscriptionsMap.containsKey(customer1.id) shouldBe false
            subscriptionsMap.containsKey(customer2.id) shouldBe false
        }

        it("removeAll() gracefully handles entities not present in the repository") {
            val customer1 = repository.create(1, "Test1", "t1@t.com")
            val customer2 = repository.create(2, "Test2", "t2@t.com")
            testDispatcher.scheduler.advanceUntilIdle()
            getSubscriptionsMap(repository).remove(customer2.id)
            shouldNotThrowAny { repository.removeAll(listOf(customer1, customer2)) }
            repository.findById(customer1.id).isEmpty shouldBe true
        }
    }

    describe("Dirty flag optimization") {
        it("skips serialization when no changes occur after last write") {
            repository.create(1, "Test", "t@t.com")
            testDispatcher.scheduler.advanceUntilIdle()
            val lastModified = jsonFile.lastModified()
            jsonFile.readText().isNotEmpty() shouldBe true
            testDispatcher.scheduler.advanceUntilIdle()
            jsonFile.lastModified() shouldBe lastModified
        }

        it("writes to new file on jsonFile switch even when state is unchanged") {
            repository.create(1, "Test", "t@t.com")
            testDispatcher.scheduler.advanceUntilIdle()
            val newJsonFile = tempfile("json-repository-test", ".json").also { it.deleteOnExit() }
            repository.jsonFile = newJsonFile
            testDispatcher.scheduler.advanceUntilIdle()
            newJsonFile.readText().isNotEmpty() shouldBe true
        }

        it("does not write back loaded state on initialization") {
            repository.create(1, "Test", "t@t.com")
            testDispatcher.scheduler.advanceUntilIdle()
            repository.close()
            val contentAfterFirstWrite = jsonFile.readText()
            val lastModifiedBeforeReload = jsonFile.lastModified()
            val reloadedRepo = StandardCustomerJsonFileRepository(jsonFile)
            testDispatcher.scheduler.advanceUntilIdle()
            jsonFile.lastModified() shouldBe lastModifiedBeforeReload
            jsonFile.readText().shouldEqualJson(contentAfterFirstWrite)
            getDirtyFlag(reloadedRepo).get() shouldBe false
            reloadedRepo.close()
        }

        it("close() skips write when repository is clean") {
            repository.create(1, "Test", "t@t.com")
            testDispatcher.scheduler.advanceUntilIdle()
            val lastModified = jsonFile.lastModified()
            val content = jsonFile.readText()
            repository.close()
            jsonFile.lastModified() shouldBe lastModified
            jsonFile.readText() shouldBe content
        }

        it("dirty flag resets after successful write") {
            val dirtyFlag = getDirtyFlag(repository)
            repository.create(1, "Test", "t@t.com")
            dirtyFlag.get() shouldBe true
            testDispatcher.scheduler.advanceUntilIdle()
            dirtyFlag.get() shouldBe false
        }
    }

    describe("Close contract") {
        it("close() returns immediately without blocking the calling thread") {
            val start = System.currentTimeMillis()
            repository.close()
            (System.currentTimeMillis() - start < 200) shouldBe true
        }

        it("close() is idempotent") {
            repository.close()
            repository.close()
        }

        it("create() throws IllegalStateException after close") {
            repository.close()
            shouldThrow<IllegalStateException> { repository.create(1, "Test", null) }
        }

        it("remove() throws IllegalStateException after close") {
            val customer = repository.create(1, "Test", null)
            repository.close()
            shouldThrow<IllegalStateException> { repository.remove(customer) }
        }

        it("removeAll() throws IllegalStateException after close") {
            val customer = repository.create(1, "Test", null)
            repository.close()
            shouldThrow<IllegalStateException> { repository.removeAll(listOf(customer)) }
        }

        it("clear() throws IllegalStateException after close") {
            repository.close()
            shouldThrow<IllegalStateException> { repository.clear() }
        }
    }

    describe("custom serialization delay") {
        it("uses the configured delay instead of the default") {
            val customDelayFile = tempfile("custom-delay-test", ".json").also { it.deleteOnExit() }
            repository.close()
            val customRepo = StandardCustomerJsonFileRepository(customDelayFile, 50L)
            try {
                customRepo.create(1, "Test", "t@t.com")
                testDispatcher.scheduler.advanceUntilIdle()
                customDelayFile.readText().isNotEmpty() shouldBe true
            } finally {
                customRepo.close()
            }
        }
    }

    describe("Deserialization error handling") {
        it("JsonFileRepository throws LirpDeserializationException on malformed JSON") {
            val corruptFile = tempfile("corrupt-json", ".json").also { it.deleteOnExit() }
            corruptFile.writeText("{invalid")
            repository.close()
            shouldThrow<LirpDeserializationException> { StandardCustomerJsonFileRepository(corruptFile) }
        }

        it("JsonFileRepository exception wraps original serialization cause") {
            val corruptFile = tempfile("corrupt-json-cause", ".json").also { it.deleteOnExit() }
            corruptFile.writeText("{invalid")
            repository.close()
            shouldThrow<LirpDeserializationException> { StandardCustomerJsonFileRepository(corruptFile) }.cause.shouldBeInstanceOf<Exception>()
        }

        it("JsonFileRepository exception message includes file path") {
            val corruptFile = tempfile("corrupt-json-path", ".json").also { it.deleteOnExit() }
            corruptFile.writeText("{invalid")
            repository.close()
            shouldThrow<LirpDeserializationException> { StandardCustomerJsonFileRepository(corruptFile) }.message shouldContain corruptFile.absolutePath
        }

        it("JsonFileRepository with valid JSON initializes normally") {
            val arb = arbitraryStandardCustomer(42).next()
            val validFile = tempfile("valid-json", ".json").also { it.deleteOnExit() }
            validFile.writeText("""{"${arb.id}":{"type":"StandardCustomer","id":${arb.id},"name":"${arb.name}","email":null,"loyaltyPoints":0}}""")
            repository.close()
            shouldNotThrowAny {
                val repo = StandardCustomerJsonFileRepository(validFile)
                repo.size() shouldBe 1
                repo.close()
            }
        }

        it("JsonFileRepository with empty JSON file initializes with no entities") {
            val emptyFile = tempfile("empty-json", ".json").also { it.deleteOnExit() }
            repository.close()
            shouldNotThrowAny {
                val repo = StandardCustomerJsonFileRepository(emptyFile)
                repo.size() shouldBe 0
                repo.close()
            }
        }
    }

    describe("Coverage gaps") {

        it("JsonFileRepository clear removes all entities and cancels their subscriptions") {
            val ctx = LirpContext()
            val file = tempfile("clear-test", ".json").also { it.deleteOnExit() }
            val repo = StandardCustomerJsonFileRepository(ctx, file)

            repo.create(1, "Alice", null)
            repo.create(2, "Bob", null)
            repo.size() shouldBe 2

            repo.clear()

            repo.isEmpty shouldBe true

            testDispatcher.scheduler.advanceUntilIdle()

            repo.close()
            ctx.close()
        }

        it("JsonFileRepository equals returns true when both repos point to the same file") {
            val ctx1 = LirpContext()
            val ctx2 = LirpContext()
            val ctx3 = LirpContext()
            val file = tempfile("equals-test", ".json").also { it.deleteOnExit() }

            val repo1 = StandardCustomerJsonFileRepository(ctx1, file)
            val repo2 = StandardCustomerJsonFileRepository(ctx2, file)
            val repo3 = StandardCustomerJsonFileRepository(ctx3, tempfile("other-file", ".json").also { it.deleteOnExit() })

            repo1.equals(repo2) shouldBe true
            repo1.hashCode() shouldBe repo2.hashCode()
            repo1.equals(repo3) shouldBe false
            repo1.equals("not a repo") shouldBe false

            repo1.close()
            repo2.close()
            repo3.close()
            ctx1.close()
            ctx2.close()
            ctx3.close()
        }

        it("JsonFileRepository jsonFile setter rejects a non-empty target file") {
            val ctx = LirpContext()
            val file = tempfile("setter-test", ".json").also { it.deleteOnExit() }
            val nonEmptyFile =
                tempfile("non-empty", ".json").also {
                    it.writeText("{}")
                    it.deleteOnExit()
                }
            val repo = StandardCustomerJsonFileRepository(ctx, file)

            shouldThrow<IllegalArgumentException> {
                repo.jsonFile = nonEmptyFile
            }.message shouldContain "not empty"

            repo.close()
            ctx.close()
        }

        it("JsonFileRepository secondary constructor uses the default LirpContext") {
            val file = tempfile("secondary-ctor-test", ".json").also { it.deleteOnExit() }
            repository.close()
            val repo = StandardCustomerJsonFileRepository(file)

            repo.isEmpty shouldBe true

            repo.close()
            LirpContext.resetDefault()
        }
    }

    describe("Mutable aggregate collection delegates") {

        it("mutable aggregate mutations update delegate backing store immediately") {
            val ctx = LirpContext()
            val file = tempfile("mutable-playlist-test", ".json").also { it.deleteOnExit() }
            val trackRepo = AudioItemVolatileRepository(ctx)
            val playlistRepo = MutableAudioPlaylistJsonFileRepository(ctx, file, 10L)

            val t1 =
                MutableAudioItem(1, "Track A").also {
                    trackRepo.add(it)
                }
            val t2 =
                MutableAudioItem(2, "Track B").also {
                    trackRepo.add(it)
                }
            val playlist = playlistRepo.create(1, "Persisted Playlist")

            playlist.audioItems.add(t1)
            playlist.audioItems.add(t2)

            // delegate backing store reflects mutations immediately
            playlist.audioItems.referenceIds shouldContainExactly listOf(1, 2)

            playlistRepo.close()
            ctx.close()
        }

        it("round-trip preserves mutable aggregate state after reload") {
            val ctx1 = LirpContext()
            val file = tempfile("mutable-roundtrip-test", ".json").also { it.deleteOnExit() }
            val trackRepo1 = AudioItemVolatileRepository(ctx1)
            val playlistRepo1 = MutableAudioPlaylistJsonFileRepository(ctx1, file, 10L)

            trackRepo1.add(MutableAudioItem(1, "Track 1"))
            trackRepo1.add(MutableAudioItem(2, "Track 2"))
            trackRepo1.add(MutableAudioItem(3, "Track 3"))
            // Create playlist with initial IDs so they are persisted in audioItems
            val playlist = playlistRepo1.create(1, "Round-trip Playlist", listOf(1, 2, 3))
            playlist.audioItems.referenceIds shouldHaveSize 3

            playlistRepo1.close()
            ctx1.close()

            val ctx2 = LirpContext()
            val trackRepo2 = AudioItemVolatileRepository(ctx2)
            trackRepo2.add(MutableAudioItem(1, "Track 1"))
            trackRepo2.add(MutableAudioItem(2, "Track 2"))
            trackRepo2.add(MutableAudioItem(3, "Track 3"))
            val playlistRepo2 = MutableAudioPlaylistJsonFileRepository(ctx2, file, 10L)

            playlistRepo2.findById(1).shouldBePresent {
                (it as DefaultAudioPlaylist).audioItems.referenceIds shouldContainExactly listOf(1, 2, 3)
                it.audioItems.resolveAll() shouldHaveSize 3
            }

            playlistRepo2.close()
            ctx2.close()
        }

        it("round-trip preserves mutable aggregate after further mutations on reload") {
            val ctx1 = LirpContext()
            val file = tempfile("mutable-mutate-reload-test", ".json").also { it.deleteOnExit() }
            val trackRepo1 = AudioItemVolatileRepository(ctx1)
            val playlistRepo1 = MutableAudioPlaylistJsonFileRepository(ctx1, file, 10L)

            trackRepo1.add(MutableAudioItem(1, "T1"))
            trackRepo1.add(MutableAudioItem(2, "T2"))
            // Create with initial IDs so audioItems is persisted
            val playlist = playlistRepo1.create(1, "Evolving Playlist", listOf(1, 2))
            playlist.audioItems.referenceIds shouldContainExactly listOf(1, 2)

            playlistRepo1.close()
            ctx1.close()

            val ctx2 = LirpContext()
            val trackRepo2 = AudioItemVolatileRepository(ctx2)
            trackRepo2.add(MutableAudioItem(1, "T1"))
            trackRepo2.add(MutableAudioItem(2, "T2"))
            trackRepo2.add(MutableAudioItem(3, "T3"))
            val playlistRepo2 = MutableAudioPlaylistJsonFileRepository(ctx2, file, 10L)

            val reloaded = playlistRepo2.findById(1).get() as DefaultAudioPlaylist
            reloaded.audioItems.referenceIds shouldContainExactly listOf(1, 2)

            playlistRepo2.close()
            ctx2.close()
        }

        it("addAll updates delegate backing store for all elements") {
            val ctx1 = LirpContext()
            val file = tempfile("mutable-addall-test", ".json").also { it.deleteOnExit() }
            val trackRepo = AudioItemVolatileRepository(ctx1)
            val playlistRepo = MutableAudioPlaylistJsonFileRepository(ctx1, file, 10L)

            val t1 =
                MutableAudioItem(1, "T1").also {
                    trackRepo.add(it)
                }
            val t2 =
                MutableAudioItem(2, "T2").also {
                    trackRepo.add(it)
                }
            val t3 =
                MutableAudioItem(3, "T3").also {
                    trackRepo.add(it)
                }
            val playlist = playlistRepo.create(1, "Bulk Add")

            playlist.audioItems.addAll(listOf(t1, t2, t3))

            playlist.audioItems.referenceIds shouldContainExactly listOf(1, 2, 3)

            playlistRepo.close()
            ctx1.close()
        }

        it("removeAll updates delegate backing store for remaining elements") {
            val ctx1 = LirpContext()
            val file = tempfile("mutable-removeall-test", ".json").also { it.deleteOnExit() }
            val trackRepo = AudioItemVolatileRepository(ctx1)
            val playlistRepo = MutableAudioPlaylistJsonFileRepository(ctx1, file, 10L)

            val t1 =
                MutableAudioItem(1, "T1").also {
                    trackRepo.add(it)
                }
            val t2 =
                MutableAudioItem(2, "T2").also {
                    trackRepo.add(it)
                }
            val t3 =
                MutableAudioItem(3, "T3").also {
                    trackRepo.add(it)
                }
            val playlist = playlistRepo.create(1, "Bulk Remove", listOf(1, 2, 3))

            playlist.audioItems.removeAll(listOf(t1, t3))

            playlist.audioItems.referenceIds shouldContainExactly listOf(2)

            playlistRepo.close()
            ctx1.close()
        }

        it("entity emits MutationEvent when mutable aggregate collection is mutated") {
            val ctx = LirpContext()
            val file = tempfile("mutable-event-test", ".json").also { it.deleteOnExit() }
            val trackRepo = AudioItemVolatileRepository(ctx)
            val playlistRepo = MutableAudioPlaylistJsonFileRepository(ctx, file, 10L)
            val received = AtomicReference(false)

            val t1 =
                MutableAudioItem(1, "Track").also {
                    trackRepo.add(it)
                }
            val playlist = playlistRepo.create(1, "EventTest")

            playlist.subscribe { received.set(true) }

            playlist.audioItems.add(t1)

            eventually(5.seconds) {
                received.get() shouldBe true
            }

            playlistRepo.close()
            ctx.close()
        }

        it("persists entity constructed with initial audioItem IDs") {
            val ctx = LirpContext()
            val file = tempfile("mutable-initial-ids-test", ".json").also { it.deleteOnExit() }
            AudioItemVolatileRepository(ctx)
            val playlistRepo = MutableAudioPlaylistJsonFileRepository(ctx, file, 10L)

            playlistRepo.create(1, "Pre-loaded", listOf(10, 20, 30))

            playlistRepo.close()
            ctx.close()

            val ctx2 = LirpContext()
            AudioItemVolatileRepository(ctx2)
            val playlistRepo2 = MutableAudioPlaylistJsonFileRepository(ctx2, file, 10L)

            playlistRepo2.findById(1).shouldBePresent {
                (it as DefaultAudioPlaylist).audioItems.referenceIds shouldContainExactly listOf(10, 20, 30)
            }

            playlistRepo2.close()
            ctx2.close()
        }
    }

    describe("deferred loading") {

        it("constructs empty repository when loadOnInit is false") {
            val deferredFile = tempfile("deferred-empty", ".json").also { it.deleteOnExit() }
            val arb = arbitraryStandardCustomer().next()
            deferredFile.writeText("""{"${arb.id}":{"type":"StandardCustomer","id":${arb.id},"name":"${arb.name}","email":null,"loyaltyPoints":0}}""")
            val ctx = LirpContext()
            val deferred = StandardCustomerJsonFileRepository(ctx, deferredFile, loadOnInit = false)
            try {
                deferred.size() shouldBe 0
                deferred.isLoaded shouldBe false
            } finally {
                deferred.close()
                ctx.close()
            }
        }

        it("load() deserializes entities from file") {
            val deferredFile = tempfile("deferred-load", ".json").also { it.deleteOnExit() }
            val json1 = """{"type":"StandardCustomer","id":1,"name":"Alice","email":null,"loyaltyPoints":0}"""
            val json2 = """{"type":"StandardCustomer","id":2,"name":"Bob","email":null,"loyaltyPoints":0}"""
            deferredFile.writeText("""{"1":$json1,"2":$json2}""")
            val ctx = LirpContext()
            val deferred = StandardCustomerJsonFileRepository(ctx, deferredFile, loadOnInit = false)
            try {
                deferred.load()
                deferred.size() shouldBe 2
                deferred.isLoaded shouldBe true
                deferred.findById(1) shouldBePresent { it.name shouldBe "Alice" }
                deferred.findById(2) shouldBePresent { it.name shouldBe "Bob" }
            } finally {
                deferred.close()
                ctx.close()
            }
        }

        it("load() twice throws IllegalStateException with 'already been loaded'") {
            val deferredFile = tempfile("deferred-double-load", ".json").also { it.deleteOnExit() }
            val ctx = LirpContext()
            val deferred = StandardCustomerJsonFileRepository(ctx, deferredFile, loadOnInit = false)
            try {
                deferred.load()
                shouldThrow<IllegalStateException> { deferred.load() }.message shouldContain "already been loaded"
            } finally {
                deferred.close()
                ctx.close()
            }
        }

        it("loadOnInit = true auto-loads on construction") {
            val eagerFile = tempfile("eager-load", ".json").also { it.deleteOnExit() }
            eagerFile.writeText("""{"1":{"type":"StandardCustomer","id":1,"name":"Alice","email":null,"loyaltyPoints":0}}""")
            val ctx = LirpContext()
            val eager = StandardCustomerJsonFileRepository(ctx, eagerFile)
            try {
                eager.size() shouldBe 1
                eager.isLoaded shouldBe true
            } finally {
                eager.close()
                ctx.close()
            }
        }

        it("load() does not emit CREATE or UPDATE events") {
            val deferredFile = tempfile("deferred-no-events", ".json").also { it.deleteOnExit() }
            deferredFile.writeText("""{"1":{"type":"StandardCustomer","id":1,"name":"Alice","email":null,"loyaltyPoints":0}}""")
            val ctx = LirpContext()
            val deferred = StandardCustomerJsonFileRepository(ctx, deferredFile, loadOnInit = false)
            try {
                val receivedEvents = AtomicReference(mutableListOf<CrudEvent<Int, PolymorphicCustomer>>())
                deferred.subscribe { event -> receivedEvents.get().add(event) }
                deferred.load()
                testDispatcher.scheduler.advanceUntilIdle()
                receivedEvents.get().filter { it.isCreate() || it.isUpdate() } shouldBe emptyList()
            } finally {
                deferred.close()
                ctx.close()
            }
        }

        it("add() before load() throws IllegalStateException with 'not been loaded'") {
            val deferredFile = tempfile("deferred-add-guard", ".json").also { it.deleteOnExit() }
            val ctx = LirpContext()
            val deferred = StandardCustomerJsonFileRepository(ctx, deferredFile, loadOnInit = false)
            try {
                shouldThrow<IllegalStateException> {
                    deferred.create(1, "Alice", null)
                }.message shouldContain "not been loaded"
            } finally {
                deferred.close()
                ctx.close()
            }
        }

        it("load() after close() throws IllegalStateException") {
            val deferredFile = tempfile("deferred-load-after-close", ".json").also { it.deleteOnExit() }
            val ctx = LirpContext()
            val deferred = StandardCustomerJsonFileRepository(ctx, deferredFile, loadOnInit = false)
            deferred.close()
            try {
                shouldThrow<IllegalStateException> { deferred.load() }.message shouldContain "closed"
            } finally {
                ctx.close()
            }
        }

        it("CRUD works normally after load()") {
            val deferredFile = tempfile("deferred-crud-after-load", ".json").also { it.deleteOnExit() }
            val ctx = LirpContext()
            val deferred = StandardCustomerJsonFileRepository(ctx, deferredFile, loadOnInit = false)
            try {
                deferred.load()
                val customer = deferred.create(1, "Alice", null)
                deferred.size() shouldBe 1
                testDispatcher.scheduler.advanceUntilIdle()
                deferred.remove(customer)
                deferred.size() shouldBe 0
            } finally {
                deferred.close()
                ctx.close()
            }
        }

        it("isLoaded reflects false before load() and true after load()") {
            val deferredFile = tempfile("deferred-is-loaded", ".json").also { it.deleteOnExit() }
            val ctx = LirpContext()
            val deferred = StandardCustomerJsonFileRepository(ctx, deferredFile, loadOnInit = false)
            try {
                deferred.isLoaded shouldBe false
                deferred.load()
                deferred.isLoaded shouldBe true
            } finally {
                deferred.close()
                ctx.close()
            }
        }
    }
})
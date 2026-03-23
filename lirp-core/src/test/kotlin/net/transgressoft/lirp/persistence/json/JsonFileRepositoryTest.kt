package net.transgressoft.lirp.persistence.json

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.LirpDeserializationException
import io.kotest.assertions.json.shouldEqualJson
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.collections.shouldContainAll
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
            JsonFileRepository::class.java
                .getDeclaredField("subscriptionsMap")
                .also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return subscriptionsMapField.get(repo) as MutableMap<Int, *>
    }

    fun getDirtyFlag(repo: JsonFileRepository<Int, PolymorphicCustomer>): AtomicBoolean {
        val dirtyField =
            JsonFileRepository::class.java
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
            ioFailureRepo.close()
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

        it("removeAll() throws IllegalStateException when subscription is missing from map") {
            val customer = repository.create(1, "Test", "t@t.com")
            testDispatcher.scheduler.advanceUntilIdle()
            getSubscriptionsMap(repository).remove(customer.id)
            shouldThrow<IllegalStateException> { repository.removeAll(listOf(customer)) }
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
})
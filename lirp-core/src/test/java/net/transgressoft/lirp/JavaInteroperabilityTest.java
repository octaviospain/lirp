package net.transgressoft.lirp;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.ExperimentalCoroutinesApi;
import kotlinx.coroutines.SupervisorKt;
import kotlinx.coroutines.test.TestCoroutineDispatchersKt;
import kotlinx.coroutines.test.TestCoroutineScheduler;
import net.transgressoft.lirp.event.AggregateMutationEvent;
import net.transgressoft.lirp.event.CrudEvent;
import net.transgressoft.lirp.event.MutationEvent;
import net.transgressoft.lirp.event.ReactiveScope;
import net.transgressoft.lirp.persistence.BubbleUpOrder;
import net.transgressoft.lirp.persistence.BubbleUpOrderVolatileRepo;
import net.transgressoft.lirp.persistence.Customer;
import net.transgressoft.lirp.persistence.CustomerVolatileRepo;
import net.transgressoft.lirp.persistence.Order;
import net.transgressoft.lirp.persistence.OrderVolatileRepo;
import net.transgressoft.lirp.persistence.ReactiveEntityReference;
import net.transgressoft.lirp.persistence.VolatileRepository;
import net.transgressoft.lirp.persistence.json.FlexibleJsonFileRepository;
import net.transgressoft.lirp.persistence.json.primitives.ReactiveString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the LIRP library can be used effectively from Java code,
 * covering reactive primitives, entities, repositories, Flow.Subscriber,
 * exception handling, AutoCloseable lifecycle, and registry queries.
 */
class JavaInteroperabilityTest {

    static TestCoroutineScheduler scheduler;

    @BeforeAll
    @ExperimentalCoroutinesApi
    static void setupTestDispatchers() {
        // Set up a TestCoroutineScheduler that allows us to control the virtual time in tests
        scheduler = new TestCoroutineScheduler();
        // Create an UnconfinedTestDispatcher which processes coroutines eagerly and can be controlled by the scheduler
        CoroutineDispatcher testDispatcher = TestCoroutineDispatchersKt.UnconfinedTestDispatcher(scheduler, null);
        // Create a test scope with the controlled dispatcher for deterministic testing.
        // SupervisorJob is required so that a failing subscriber coroutine (e.g., in exception isolation tests)
        // does not cancel the shared parent scope and break subsequent tests — mirroring production ReactiveScope.
        CoroutineScope testScope = CoroutineScopeKt.CoroutineScope(testDispatcher.plus(SupervisorKt.SupervisorJob(null)));
        // Override the default reactive scopes to use our test scope for predictable test execution
        ReactiveScope.INSTANCE.setFlowScope(testScope);
        ReactiveScope.INSTANCE.setIoScope(testScope);
    }

    @AfterAll
    static void resetDispatchers() {
        ReactiveScope.INSTANCE.resetDefaultFlowScope();
        ReactiveScope.INSTANCE.resetDefaultIoScope();
    }

    @Nested
    @DisplayName("Reactive Primitives")
    class ReactivePrimitivesTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("ReactiveString subscribe receives old and new values on change")
        void reactiveStringSubscribeReceivesOldAndNewValuesOnChange() {
            try (var appName = new ReactiveString("app.name", "MyApp")) {
                assertEquals("MyApp", appName.getValue());

                String[] oldValueHolder = new String[1];
                String[] newValueHolder = new String[1];

                var subscription = appName.subscribe(event -> {
                    oldValueHolder[0] = event.getOldEntity().getValue();
                    newValueHolder[0] = event.getNewEntity().getValue();
                });

                appName.setValue("NewAppName");
                scheduler.advanceUntilIdle();

                assertEquals("MyApp", oldValueHolder[0]);
                assertEquals("NewAppName", newValueHolder[0]);
                assertEquals("NewAppName", appName.getValue());

                subscription.cancel();
            }
        }

        @Test
        @DisplayName("FlexibleJsonFileRepository persists and reloads reactive primitives")
        void flexibleJsonFileRepositoryPersistsAndReloadsReactivePrimitives() throws Exception {
            var configFile = new File(tempDir.toFile(), "config.json");
            assertTrue(configFile.createNewFile());

            var configRepository = new FlexibleJsonFileRepository(configFile);

            var serverName = configRepository.getReactiveString("server.name", "MainServer");
            var maxConnections = configRepository.getReactiveInt("max.connections", 100);
            var debugMode = configRepository.getReactiveBoolean("debug.mode", false);

            assertEquals("MainServer", serverName.getValue());
            assertEquals(100, maxConnections.getValue());
            assertFalse(debugMode.getValue());

            maxConnections.setValue(150);
            debugMode.setValue(true);
            serverName.setValue("BackupServer");
            scheduler.advanceUntilIdle();

            assertEquals("BackupServer", serverName.getValue());
            assertEquals(150, maxConnections.getValue());
            assertTrue(debugMode.getValue());

            configRepository.close();

            var reloadedRepo = new FlexibleJsonFileRepository(configFile);
            scheduler.advanceUntilIdle();

            assertEquals("BackupServer", reloadedRepo.findById("server.name").get().getValue());
            assertEquals(150, reloadedRepo.findById("max.connections").get().getValue());
            assertEquals(Boolean.TRUE, reloadedRepo.findById("debug.mode").get().getValue());

            reloadedRepo.close();
            configFile.deleteOnExit();
        }
    }

    @Nested
    @DisplayName("Reactive Entity")
    class ReactiveEntityTests {

        @Test
        @DisplayName("Entity subscribe delivers old and new name on mutation")
        void entitySubscribeDeliversOldAndNewNameOnMutation() {
            var person = new Person(1, "Alice", 0L, true);

            assertEquals(1, person.getId());
            assertEquals("Alice", person.getName());
            assertEquals(0L, person.getMoney());

            var oldName = new String[1];
            var newName = new String[1];

            var subscription = person.subscribe(event -> {
                oldName[0] = event.getOldEntity().getName();
                newName[0] = event.getNewEntity().getName();
            });

            person.setName("John");
            scheduler.advanceUntilIdle();

            assertEquals("Alice", oldName[0]);
            assertEquals("John", newName[0]);

            subscription.cancel();
        }
    }

    @Nested
    @DisplayName("Flow.Subscriber")
    class FlowSubscriberTests {

        @Test
        @DisplayName("Flow.Subscriber receives entity mutation events via onNext")
        void flowSubscriberReceivesEntityMutationEventsViaOnNext() {
            var person = new Person(1, "Alice", 0L, true);
            List<MutationEvent<Integer, Personly>> receivedEvents = new ArrayList<>();
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

            Flow.Subscriber<MutationEvent<Integer, Personly>> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                }

                @Override
                public void onNext(MutationEvent<Integer, Personly> item) {
                    receivedEvents.add(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    // Not expected in this test scenario
                }

                @Override
                public void onComplete() {
                    // Not expected in this test scenario
                }
            };

            person.subscribe(subscriber);
            person.setName("Bob");
            scheduler.advanceUntilIdle();

            assertNotNull(subscriptionRef.get(), "onSubscribe must be called");
            assertEquals(1, receivedEvents.size());
            assertEquals("Alice", receivedEvents.get(0).getOldEntity().getName());
            assertEquals("Bob", receivedEvents.get(0).getNewEntity().getName());
        }

        @Test
        @DisplayName("Flow.Subscriber receives repository CRUD events via onNext")
        void flowSubscriberReceivesRepositoryCrudEventsViaOnNext() {
            var repository = new VolatileRepository<Integer, Person>("TestRepo");
            List<CrudEvent<Integer, ? extends Person>> receivedEvents = new ArrayList<>();
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

            Flow.Subscriber<CrudEvent<Integer, ? extends Person>> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                }

                @Override
                public void onNext(CrudEvent<Integer, ? extends Person> item) {
                    receivedEvents.add(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    // Not expected in this test scenario
                }

                @Override
                public void onComplete() {
                    // Not expected in this test scenario
                }
            };

            repository.subscribe(subscriber);
            repository.add(new Person(1, "Alice", 100L, true));
            scheduler.advanceUntilIdle();

            assertNotNull(subscriptionRef.get(), "onSubscribe must be called");
            assertEquals(1, receivedEvents.size());
            assertTrue(receivedEvents.get(0).isCreate());
            assertEquals("Alice", receivedEvents.get(0).getEntities().get(1).getName());
        }

        @Test
        @DisplayName("Calling request() on subscription throws IllegalStateException")
        void callingRequestOnSubscriptionThrowsIllegalStateException() {
            var person = new Person(1, "Alice", 0L, true);
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

            Flow.Subscriber<MutationEvent<Integer, Personly>> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                }

                @Override
                public void onNext(MutationEvent<Integer, Personly> item) {
                    // Not expected in this test scenario
                }

                @Override
                public void onError(Throwable throwable) {
                    // Not expected in this test scenario
                }

                @Override
                public void onComplete() {
                    // Not expected in this test scenario
                }
            };

            person.subscribe(subscriber);
            assertNotNull(subscriptionRef.get());
            Flow.Subscription subscription = subscriptionRef.get();
            assertThrows(IllegalStateException.class, () -> subscription.request(1));
        }
    }

    @Nested
    @DisplayName("Exception Handling")
    class ExceptionHandlingTests {

        @Test
        @DisplayName("Subscribing to closed entity throws IllegalStateException")
        void subscribingToClosedEntityThrowsIllegalStateException() {
            var person = new Person(1, "Alice", 0L, true);
            person.close();

            assertTrue(person.isClosed());
            assertThrows(IllegalStateException.class, () -> person.subscribe(event -> {}));
        }

        @Test
        @DisplayName("Subscribing to closed repository throws IllegalStateException")
        void subscribingToClosedRepositoryThrowsIllegalStateException() {
            var repository = new VolatileRepository<Integer, Person>("ClosedRepo");
            repository.close();

            assertTrue(repository.isClosed());
            assertThrows(IllegalStateException.class, () -> repository.subscribe(event -> {}));
        }
    }

    @Nested
    @DisplayName("AutoCloseable Lifecycle")
    class AutoCloseableLifecycleTests {

        @Test
        @DisplayName("Entity closes properly via try-with-resources")
        void entityClosesProperlyViaTryWithResources() {
            Person[] personRef = new Person[1];

            try (var person = new Person(1, "Alice", 0L, true)) {
                personRef[0] = person;
                var subscription = person.subscribe(event -> {});
                person.setName("Bob");
                scheduler.advanceUntilIdle();
                subscription.cancel();
            }

            assertTrue(personRef[0].isClosed());
            assertThrows(IllegalStateException.class, () -> personRef[0].subscribe(event -> {}));
        }

        @Test
        @DisplayName("Repository closes properly via try-with-resources")
        void repositoryClosesProperlyViaTryWithResources() {
            VolatileRepository<Integer, Person>[] repoRef = new VolatileRepository[1];

            try (var repository = new VolatileRepository<Integer, Person>("AutoCloseRepo")) {
                repoRef[0] = repository;
                repository.add(new Person(1, "Alice", 0L, true));
                scheduler.advanceUntilIdle();
            }

            assertTrue(repoRef[0].isClosed());
            assertThrows(IllegalStateException.class, () -> repoRef[0].subscribe(event -> {}));
        }
    }

    @Nested
    @DisplayName("Registry Queries")
    class RegistryQueryTests {

        @Test
        @DisplayName("search with Predicate returns only matching entities")
        void searchWithPredicateReturnsOnlyMatchingEntities() {
            var repository = new VolatileRepository<Integer, Person>("SearchRepo");
            repository.add(new Person(1, "Alice", 100L, true));
            repository.add(new Person(2, "Bob", 200L, false));
            repository.add(new Person(3, "Charlie", 300L, true));

            var result = repository.search(person -> person.getName().startsWith("A"));

            assertEquals(1, result.size());
            assertEquals("Alice", result.iterator().next().getName());
        }

        @Test
        @DisplayName("search with size limit returns at most the requested number")
        void searchWithSizeLimitReturnsAtMostTheRequestedNumber() {
            var repository = new VolatileRepository<Integer, Person>("SizeLimitRepo");
            repository.add(new Person(1, "Alice", 100L, true));
            repository.add(new Person(2, "Bob", 200L, false));
            repository.add(new Person(3, "Charlie", 300L, true));

            var result = repository.search(2, person -> true);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("findFirst with Predicate returns a matching entity")
        void findFirstWithPredicateReturnsMatchingEntity() {
            var repository = new VolatileRepository<Integer, Person>("FindFirstRepo");
            repository.add(new Person(1, "Alice", 50L, true));
            repository.add(new Person(2, "Bob", 200L, false));
            repository.add(new Person(3, "Charlie", 300L, true));

            var result = repository.findFirst(person -> person.getMoney() > 100L);

            assertTrue(result.isPresent());
            assertTrue(result.get().getMoney() > 100L);
        }

        @Test
        @DisplayName("contains with Predicate detects existing and non-existing entities")
        void containsWithPredicateDetectsExistingAndNonExistingEntities() {
            var repository = new VolatileRepository<Integer, Person>("ContainsRepo");
            repository.add(new Person(1, "Alice", 100L, true));
            repository.add(new Person(2, "Bob", 200L, false));

            assertTrue(repository.contains(person -> person.getName().equals("Alice")));
            assertFalse(repository.contains(person -> person.getName().equals("NonExistent")));
        }

        @Test
        @DisplayName("iterator returns all entities in the repository")
        void iteratorReturnsAllEntitiesInRepository() {
            var repository = new VolatileRepository<Integer, Person>("IteratorRepo");
            var alice = new Person(1, "Alice", 100L, true);
            var bob = new Person(2, "Bob", 200L, false);
            var charlie = new Person(3, "Charlie", 300L, true);
            repository.add(alice);
            repository.add(bob);
            repository.add(charlie);

            var iterated = new ArrayList<Person>();
            for (var person : repository) {
                iterated.add(person);
            }

            assertEquals(3, iterated.size());
            assertTrue(iterated.containsAll(List.of(alice, bob, charlie)));
        }

        @Test
        @DisplayName("findByUniqueId returns the entity with the matching unique ID")
        void findByUniqueIdReturnsEntityWithMatchingUniqueId() {
            var repository = new VolatileRepository<Integer, Person>("UniqueIdRepo");
            var alice = new Person(1, "Alice", 100L, true);
            repository.add(alice);

            var result = repository.findByUniqueId(alice.getUniqueId());

            assertTrue(result.isPresent());
            assertEquals(alice, result.get());
        }
    }

    @Nested
    @DisplayName("Subscriber Exception Isolation")
    class SubscriberExceptionIsolationTests {

        @Test
        @DisplayName("Java Consumer subscriber exception does not prevent other subscribers from receiving events")
        void javaConsumerSubscriberExceptionDoesNotPreventOtherSubscribersFromReceivingEvents() {
            var person = new Person(1, "Alice", 0L, true);

            // Throwing Consumer — unconditional exception on every event
            person.subscribe(event -> { throw new RuntimeException("intentional Java exception"); });

            var healthyCounter = new AtomicInteger(0);
            person.subscribe(event -> healthyCounter.incrementAndGet());

            person.setName("Bob");
            person.setName("Charlie");
            scheduler.advanceUntilIdle();

            assertEquals(2, healthyCounter.get());
        }
    }

    @Nested
    @DisplayName("Aggregate Reference")
    class AggregateReferenceTests {

        CustomerVolatileRepo customerRepo;
        OrderVolatileRepo orderRepo;

        @BeforeEach
        void setupRepos() {
            customerRepo = new CustomerVolatileRepo();
            orderRepo = new OrderVolatileRepo();
        }

        @AfterEach
        void cleanupRepos() {
            customerRepo.close();
            orderRepo.close();
        }

        @Test
        @DisplayName("Java can access aggregate ref via getter and call resolve()")
        void javaCanAccessAggregateRefViaGetterAndCallResolve() {
            var customer = new Customer(1, "Alice");
            var order = new Order(10L, 1);

            customerRepo.add(customer);
            orderRepo.add(order);
            scheduler.advanceUntilIdle();

            ReactiveEntityReference<Customer, Integer> ref = order.getCustomer();
            assertNotNull(ref);
            assertTrue(ref.resolve().isPresent());
            assertEquals("Alice", ref.resolve().get().getName());
        }

        @Test
        @DisplayName("Java resolve returns empty Optional when referenced entity not in repo")
        void javaResolveReturnsEmptyOptionalWhenReferencedEntityNotInRepo() {
            var order = new Order(10L, 99);
            orderRepo.add(order);
            scheduler.advanceUntilIdle();

            ReactiveEntityReference<Customer, Integer> ref = order.getCustomer();
            assertNotNull(ref);
            assertFalse(ref.resolve().isPresent());
        }

        @Test
        @DisplayName("Java subscriber receives AggregateMutationEvent as MutationEvent subtype")
        void javaSubscriberReceivesAggregateMutationEventAsMutationEventSubtype() throws InterruptedException {
            var customer = new Customer(1, "Bob");
            var order = new BubbleUpOrder(10L, 1);
            var bubbleUpOrderRepo = new BubbleUpOrderVolatileRepo();

            customerRepo.add(customer);
            bubbleUpOrderRepo.add(order);
            scheduler.advanceUntilIdle();

            var latch = new CountDownLatch(1);
            var receivedAggregateEvent = new AtomicReference<MutationEvent<?, ?>>(null);

            order.subscribe(event -> {
                if (event instanceof AggregateMutationEvent) {
                    receivedAggregateEvent.set(event);
                    latch.countDown();
                }
            });

            customer.updateName("Bob Updated");
            scheduler.advanceUntilIdle();

            assertTrue(latch.await(2, SECONDS));
            assertNotNull(receivedAggregateEvent.get());
            assertInstanceOf(AggregateMutationEvent.class, receivedAggregateEvent.get());
            bubbleUpOrderRepo.close();
        }
    }

    @Nested
    @DisplayName("Repository CRUD")
    class RepositoryCrudTests {

        @Test
        @DisplayName("add publishes CREATE event with the added entity")
        void addPublishesCreateEventWithAddedEntity() {
            var repository = new VolatileRepository<Integer, Person>("AddRepo");
            var eventEntities = new ArrayList<Person>();

            var subscription = repository.subscribe(
                event -> eventEntities.addAll(event.getEntities().values()));

            repository.add(new Person(1, "Alice", 0L, true));
            scheduler.advanceUntilIdle();

            assertEquals(1, eventEntities.size());
            assertEquals("Alice", eventEntities.get(0).getName());

            subscription.cancel();
        }

        @Test
        @DisplayName("addOrReplace with updated entity and findById reflects change")
        void addOrReplaceWithUpdatedEntityAndFindByIdReflectsChange() {
            var repository = new VolatileRepository<Integer, Person>("AddOrReplaceNameRepo");
            repository.add(new Person(1, "Alice", 0L, true));

            repository.addOrReplace(new Person(1, "John", 0L, true));
            scheduler.advanceUntilIdle();

            assertEquals("John", repository.findById(1).get().getName());
        }

        @Test
        @DisplayName("addOrReplace replaces existing entity and publishes UPDATE event")
        void addOrReplaceReplacesExistingEntityAndPublishesUpdateEvent() {
            var repository = new VolatileRepository<Integer, Person>("AddOrReplaceRepo");
            repository.activateEvents(CrudEvent.Type.UPDATE);
            repository.add(new Person(1, "Alice", 100L, true));

            List<CrudEvent<Integer, ? extends Person>> receivedEvents = new ArrayList<>();
            var subscription = repository.subscribe(event -> receivedEvents.add(event));

            repository.addOrReplace(new Person(1, "Bob", 200L, false));
            scheduler.advanceUntilIdle();

            assertEquals("Bob", repository.findById(1).get().getName());
            assertEquals(1, receivedEvents.size());
            assertTrue(receivedEvents.get(0).isUpdate());

            subscription.cancel();
        }

        @Test
        @DisplayName("remove deletes entity and publishes DELETE event")
        void removeDeletesEntityAndPublishesDeleteEvent() {
            var repository = new VolatileRepository<Integer, Person>("RemoveRepo");
            var alice = new Person(1, "Alice", 100L, true);
            repository.add(alice);

            List<CrudEvent<Integer, ? extends Person>> receivedEvents = new ArrayList<>();
            var subscription = repository.subscribe(event -> receivedEvents.add(event));

            repository.remove(alice);
            scheduler.advanceUntilIdle();

            assertEquals(0, repository.size());
            assertEquals(1, receivedEvents.size());
            assertTrue(receivedEvents.get(0).isDelete());

            subscription.cancel();
        }

        @Test
        @DisplayName("removeAll deletes multiple entities at once")
        void removeAllDeletesMultipleEntitiesAtOnce() {
            var repository = new VolatileRepository<Integer, Person>("RemoveAllRepo");
            var alice = new Person(1, "Alice", 100L, true);
            var bob = new Person(2, "Bob", 200L, false);
            var charlie = new Person(3, "Charlie", 300L, true);
            repository.add(alice);
            repository.add(bob);
            repository.add(charlie);

            repository.removeAll(List.of(alice, bob));
            scheduler.advanceUntilIdle();

            assertEquals(1, repository.size());
            assertTrue(repository.findById(3).isPresent());
        }

        @Test
        @DisplayName("clear removes all entities and publishes DELETE event")
        void clearRemovesAllEntitiesAndPublishesDeleteEvent() {
            var repository = new VolatileRepository<Integer, Person>("ClearRepo");
            repository.add(new Person(1, "Alice", 100L, true));
            repository.add(new Person(2, "Bob", 200L, false));
            repository.add(new Person(3, "Charlie", 300L, true));

            List<CrudEvent<Integer, ? extends Person>> receivedEvents = new ArrayList<>();
            var subscription = repository.subscribe(event -> receivedEvents.add(event));

            repository.clear();
            scheduler.advanceUntilIdle();

            assertTrue(repository.isEmpty());
            assertEquals(1, receivedEvents.size());
            assertTrue(receivedEvents.get(0).isDelete());

            subscription.cancel();
        }
    }
}

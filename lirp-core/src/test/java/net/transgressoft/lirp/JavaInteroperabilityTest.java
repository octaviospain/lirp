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
import net.transgressoft.lirp.persistence.BubbleUpOrderVolatileRepo;
import net.transgressoft.lirp.persistence.Customer;
import net.transgressoft.lirp.persistence.CustomerVolatileRepo;
import net.transgressoft.lirp.persistence.LirpContext;
import net.transgressoft.lirp.persistence.MutablePlaylist;
import net.transgressoft.lirp.persistence.MutablePlaylistVolatileRepo;
import net.transgressoft.lirp.persistence.OrderVolatileRepo;
import net.transgressoft.lirp.persistence.ReactiveEntityReference;
import net.transgressoft.lirp.persistence.TestTrack;
import net.transgressoft.lirp.persistence.TestTrackVolatileRepo;
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
import java.util.Collections;
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
            var customer = new Customer(1, "Alice");

            assertEquals(1, customer.getId());
            assertEquals("Alice", customer.getName());

            var oldName = new String[1];
            var newName = new String[1];

            var subscription = customer.subscribe(event -> {
                oldName[0] = event.getOldEntity().getName();
                newName[0] = event.getNewEntity().getName();
            });

            customer.updateName("John");
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
            var customer = new Customer(1, "Alice");
            List<MutationEvent<Integer, Customer>> receivedEvents = new ArrayList<>();
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

            Flow.Subscriber<MutationEvent<Integer, Customer>> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                }

                @Override
                public void onNext(MutationEvent<Integer, Customer> item) {
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

            customer.subscribe(subscriber);
            customer.updateName("Bob");
            scheduler.advanceUntilIdle();

            assertNotNull(subscriptionRef.get(), "onSubscribe must be called");
            assertEquals(1, receivedEvents.size());
            assertEquals("Alice", receivedEvents.get(0).getOldEntity().getName());
            assertEquals("Bob", receivedEvents.get(0).getNewEntity().getName());
        }

        @Test
        @DisplayName("Flow.Subscriber receives repository CRUD events via onNext")
        void flowSubscriberReceivesRepositoryCrudEventsViaOnNext() {
            var repository = new CustomerVolatileRepo();
            List<CrudEvent<Integer, ? extends Customer>> receivedEvents = new ArrayList<>();
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

            Flow.Subscriber<CrudEvent<Integer, ? extends Customer>> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                }

                @Override
                public void onNext(CrudEvent<Integer, ? extends Customer> item) {
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
            repository.create(1, "Alice");
            scheduler.advanceUntilIdle();

            assertNotNull(subscriptionRef.get(), "onSubscribe must be called");
            assertEquals(1, receivedEvents.size());
            assertTrue(receivedEvents.get(0).isCreate());
            assertEquals("Alice", receivedEvents.get(0).getEntities().get(1).getName());

            repository.close();
        }

        @Test
        @DisplayName("Calling request() on subscription throws IllegalStateException")
        void callingRequestOnSubscriptionThrowsIllegalStateException() {
            var customer = new Customer(1, "Alice");
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

            Flow.Subscriber<MutationEvent<Integer, Customer>> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                }

                @Override
                public void onNext(MutationEvent<Integer, Customer> item) {
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

            customer.subscribe(subscriber);
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
            var customer = new Customer(1, "Alice");
            customer.close();

            assertTrue(customer.isClosed());
            assertThrows(IllegalStateException.class, () -> customer.subscribe(event -> {}));
        }

        @Test
        @DisplayName("Subscribing to closed repository throws IllegalStateException")
        void subscribingToClosedRepositoryThrowsIllegalStateException() {
            var repository = new CustomerVolatileRepo();
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
            Customer[] customerRef = new Customer[1];

            try (var customer = new Customer(1, "Alice")) {
                customerRef[0] = customer;
                var subscription = customer.subscribe(event -> {});
                customer.updateName("Bob");
                scheduler.advanceUntilIdle();
                subscription.cancel();
            }

            assertTrue(customerRef[0].isClosed());
            assertThrows(IllegalStateException.class, () -> customerRef[0].subscribe(event -> {}));
        }

        @Test
        @DisplayName("Repository closes properly via try-with-resources")
        void repositoryClosesProperlyViaTryWithResources() {
            CustomerVolatileRepo[] repoRef = new CustomerVolatileRepo[1];

            try (var repository = new CustomerVolatileRepo()) {
                repoRef[0] = repository;
                repository.create(1, "Alice");
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
            var repository = new CustomerVolatileRepo();
            repository.create(1, "Alice");
            repository.create(2, "Bob");
            repository.create(3, "Charlie");

            var result = repository.search(customer -> customer.getName().startsWith("A"));

            assertEquals(1, result.size());
            assertEquals("Alice", result.iterator().next().getName());
            repository.close();
        }

        @Test
        @DisplayName("search with size limit returns at most the requested number")
        void searchWithSizeLimitReturnsAtMostTheRequestedNumber() {
            var repository = new CustomerVolatileRepo();
            repository.create(1, "Alice");
            repository.create(2, "Bob");
            repository.create(3, "Charlie");

            var result = repository.search(2, customer -> true);

            assertEquals(2, result.size());
            repository.close();
        }

        @Test
        @DisplayName("findFirst with Predicate returns a matching entity")
        void findFirstWithPredicateReturnsMatchingEntity() {
            var repository = new CustomerVolatileRepo();
            repository.create(1, "Alice");
            repository.create(2, "Bob");
            repository.create(3, "Charlie");

            var result = repository.findFirst(customer -> customer.getName().startsWith("B"));

            assertTrue(result.isPresent());
            assertEquals("Bob", result.get().getName());
            repository.close();
        }

        @Test
        @DisplayName("contains with Predicate detects existing and non-existing entities")
        void containsWithPredicateDetectsExistingAndNonExistingEntities() {
            var repository = new CustomerVolatileRepo();
            repository.create(1, "Alice");
            repository.create(2, "Bob");

            assertTrue(repository.contains(customer -> customer.getName().equals("Alice")));
            assertFalse(repository.contains(customer -> customer.getName().equals("NonExistent")));
            repository.close();
        }

        @Test
        @DisplayName("iterator returns all entities in the repository")
        void iteratorReturnsAllEntitiesInRepository() {
            var repository = new CustomerVolatileRepo();
            var alice = repository.create(1, "Alice");
            var bob = repository.create(2, "Bob");
            var charlie = repository.create(3, "Charlie");

            var iterated = new ArrayList<Customer>();
            for (var customer : repository) {
                iterated.add(customer);
            }

            assertEquals(3, iterated.size());
            assertTrue(iterated.containsAll(List.of(alice, bob, charlie)));
            repository.close();
        }

        @Test
        @DisplayName("findByUniqueId returns the entity with the matching unique ID")
        void findByUniqueIdReturnsEntityWithMatchingUniqueId() {
            var repository = new CustomerVolatileRepo();
            var alice = repository.create(1, "Alice");

            var result = repository.findByUniqueId(alice.getUniqueId());

            assertTrue(result.isPresent());
            assertEquals(alice, result.get());
            repository.close();
        }
    }

    @Nested
    @DisplayName("Subscriber Exception Isolation")
    class SubscriberExceptionIsolationTests {

        @Test
        @DisplayName("Java Consumer subscriber exception does not prevent other subscribers from receiving events")
        void javaConsumerSubscriberExceptionDoesNotPreventOtherSubscribersFromReceivingEvents() {
            var customer = new Customer(1, "Alice");

            // Throwing Consumer — unconditional exception on every event
            customer.subscribe(event -> { throw new RuntimeException("intentional Java exception"); });

            var healthyCounter = new AtomicInteger(0);
            customer.subscribe(event -> healthyCounter.incrementAndGet());

            customer.updateName("Bob");
            customer.updateName("Charlie");
            scheduler.advanceUntilIdle();

            assertEquals(2, healthyCounter.get());
        }
    }

    @Nested
    @DisplayName("Aggregate Reference")
    class AggregateReferenceTests {

        LirpContext ctx;
        CustomerVolatileRepo customerRepo;
        OrderVolatileRepo orderRepo;

        @BeforeEach
        void setupRepos() {
            ctx = new LirpContext();
            customerRepo = new CustomerVolatileRepo(ctx);
            orderRepo = new OrderVolatileRepo(ctx);
        }

        @AfterEach
        void cleanupRepos() {
            ctx.close();
        }

        @Test
        @DisplayName("Java can access aggregate ref via getter and call resolve()")
        void javaCanAccessAggregateRefViaGetterAndCallResolve() {
            customerRepo.create(1, "Alice");
            orderRepo.create(10L, 1);
            scheduler.advanceUntilIdle();

            var order = orderRepo.findById(10L).get();
            ReactiveEntityReference<Integer, Customer> ref = order.getCustomer();
            assertNotNull(ref);
            assertTrue(ref.resolve().isPresent());
            assertEquals("Alice", ref.resolve().get().getName());
        }

        @Test
        @DisplayName("Java resolve returns empty Optional when referenced entity not in repo")
        void javaResolveReturnsEmptyOptionalWhenReferencedEntityNotInRepo() {
            orderRepo.create(10L, 99);
            scheduler.advanceUntilIdle();

            var order = orderRepo.findById(10L).get();
            ReactiveEntityReference<Integer, Customer> ref = order.getCustomer();
            assertNotNull(ref);
            assertFalse(ref.resolve().isPresent());
        }

        @Test
        @DisplayName("Java subscriber receives AggregateMutationEvent as MutationEvent subtype")
        void javaSubscriberReceivesAggregateMutationEventAsMutationEventSubtype() throws InterruptedException {
            customerRepo.create(1, "Bob");
            var bubbleUpOrderRepo = new BubbleUpOrderVolatileRepo(ctx);
            bubbleUpOrderRepo.create(10L, 1);
            scheduler.advanceUntilIdle();

            var order = bubbleUpOrderRepo.findById(10L).get();

            var latch = new CountDownLatch(1);
            var receivedAggregateEvent = new AtomicReference<MutationEvent<?, ?>>(null);

            order.subscribe(event -> {
                if (event instanceof AggregateMutationEvent) {
                    receivedAggregateEvent.set(event);
                    latch.countDown();
                }
            });

            customerRepo.findById(1).get().updateName("Bob Updated");
            scheduler.advanceUntilIdle();

            assertTrue(latch.await(2, SECONDS));
            assertNotNull(receivedAggregateEvent.get());
            assertInstanceOf(AggregateMutationEvent.class, receivedAggregateEvent.get());
        }
    }

    @Nested
    @DisplayName("Mutable Aggregate Collection")
    class MutableAggregateCollectionTests {

        LirpContext ctx;
        TestTrackVolatileRepo trackRepo;
        MutablePlaylistVolatileRepo playlistRepo;

        @BeforeEach
        void setUp() {
            ctx = new LirpContext();
            trackRepo = new TestTrackVolatileRepo(ctx);
            playlistRepo = new MutablePlaylistVolatileRepo(ctx);
        }

        @AfterEach
        void tearDown() {
            ctx.close();
        }

        @Test
        @DisplayName("Java code adds entity to mutable aggregate list via getItems().add()")
        void javaAddsEntityToMutableAggregateListViaGetItemsAdd() {
            TestTrack track = trackRepo.create(1, "Track 1");
            MutablePlaylist playlist = playlistRepo.create(1L, "Java Test", Collections.emptyList());

            boolean added = playlist.getItems().add(track);

            assertTrue(added);
            assertTrue(playlist.getItems().getReferenceIds().contains(1));
            assertEquals(1, playlist.getItems().getReferenceIds().size());
        }

        @Test
        @DisplayName("Java code removes entity from mutable aggregate list via getItems().remove()")
        void javaRemovesEntityFromMutableAggregateListViaGetItemsRemove() {
            TestTrack track = trackRepo.create(1, "Track 1");
            MutablePlaylist playlist = playlistRepo.create(1L, "Java Test", List.of(1));

            boolean removed = playlist.getItems().remove(track);

            assertTrue(removed);
            assertTrue(playlist.getItems().getReferenceIds().isEmpty());
        }
    }

    @Nested
    @DisplayName("Repository CRUD")
    class RepositoryCrudTests {

        @Test
        @DisplayName("create publishes CREATE event with the added entity")
        void createPublishesCreateEventWithAddedEntity() {
            var repository = new CustomerVolatileRepo();
            var eventEntities = new ArrayList<Customer>();

            var subscription = repository.subscribe(
                event -> eventEntities.addAll(event.getEntities().values()));

            repository.create(1, "Alice");
            scheduler.advanceUntilIdle();

            assertEquals(1, eventEntities.size());
            assertEquals("Alice", eventEntities.get(0).getName());

            subscription.cancel();
            repository.close();
        }

        @Test
        @DisplayName("remove deletes entity and publishes DELETE event")
        void removeDeletesEntityAndPublishesDeleteEvent() {
            var repository = new CustomerVolatileRepo();
            var alice = repository.create(1, "Alice");
            repository.create(1, "Alice");

            List<CrudEvent<Integer, ? extends Customer>> receivedEvents = new ArrayList<>();
            var subscription = repository.subscribe(event -> receivedEvents.add(event));

            repository.remove(alice);
            scheduler.advanceUntilIdle();

            assertEquals(0, repository.size());
            assertEquals(1, receivedEvents.size());
            assertTrue(receivedEvents.get(0).isDelete());

            subscription.cancel();
            repository.close();
        }

        @Test
        @DisplayName("removeAll deletes multiple entities at once")
        void removeAllDeletesMultipleEntitiesAtOnce() {
            var repository = new CustomerVolatileRepo();
            var alice = repository.create(1, "Alice");
            var bob = repository.create(2, "Bob");
            var charlie = repository.create(3, "Charlie");

            repository.removeAll(List.of(alice, bob));
            scheduler.advanceUntilIdle();

            assertEquals(1, repository.size());
            assertTrue(repository.findById(3).isPresent());
            assertEquals(charlie, repository.findById(3).get());
            repository.close();
        }

        @Test
        @DisplayName("clear removes all entities and publishes DELETE event")
        void clearRemovesAllEntitiesAndPublishesDeleteEvent() {
            var repository = new CustomerVolatileRepo();
            repository.create(1, "Alice");
            repository.create(2, "Bob");
            repository.create(3, "Charlie");

            List<CrudEvent<Integer, ? extends Customer>> receivedEvents = new ArrayList<>();
            var subscription = repository.subscribe(event -> receivedEvents.add(event));

            repository.clear();
            scheduler.advanceUntilIdle();

            assertTrue(repository.isEmpty());
            assertEquals(1, receivedEvents.size());
            assertTrue(receivedEvents.get(0).isDelete());

            subscription.cancel();
            repository.close();
        }
    }
}

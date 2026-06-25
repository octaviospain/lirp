package net.transgressoft.lirp;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.ExperimentalCoroutinesApi;
import kotlinx.coroutines.SupervisorKt;
import kotlinx.coroutines.test.TestCoroutineDispatchersKt;
import kotlinx.coroutines.test.TestCoroutineScheduler;
import net.transgressoft.lirp.entity.CollectionChangeEventExtensionsKt;
import net.transgressoft.lirp.event.AggregateMutationEvent;
import net.transgressoft.lirp.event.CollectionChangeEvent;
import net.transgressoft.lirp.event.CrudEvent;
import net.transgressoft.lirp.event.MutationEvent;
import net.transgressoft.lirp.event.PropertyChanged;
import net.transgressoft.lirp.event.ReactiveScope;
import net.transgressoft.lirp.persistence.AudioItem;
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository;
import net.transgressoft.lirp.persistence.AudioPlaylistVolatileRepository;
import net.transgressoft.lirp.persistence.BubbleUpAudioPlaylistRepo;
import net.transgressoft.lirp.persistence.DefaultAudioPlaylist;
import net.transgressoft.lirp.persistence.LirpContext;
import net.transgressoft.lirp.persistence.MutableAudioItem;
import net.transgressoft.lirp.persistence.MutableAudioPlaylist;
import net.transgressoft.lirp.persistence.ReactiveEntityReference;
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
import java.util.function.Consumer;

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

                var subscription = appName.subscribeAsync(event -> {
                    var pc = (PropertyChanged<?, ?, ?>) event;
                    oldValueHolder[0] = (String) pc.getOldValue();
                    newValueHolder[0] = (String) pc.getNewValue();
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
        @DisplayName("Entity subscribe delivers old and new title on mutation")
        void entitySubscribeDeliversOldAndNewTitleOnMutation() {
            var audioItem = new MutableAudioItem(1, "Track Alpha");

            assertEquals(1, audioItem.getId());
            assertEquals("Track Alpha", audioItem.getTitle());

            var oldTitle = new String[1];
            var newTitle = new String[1];

            var subscription = audioItem.subscribeAsync(event -> {
                var pc = (PropertyChanged<?, ?, ?>) event;
                oldTitle[0] = (String) pc.getOldValue();
                newTitle[0] = (String) pc.getNewValue();
            });

            audioItem.setTitle("Track Beta");
            scheduler.advanceUntilIdle();

            assertEquals("Track Alpha", oldTitle[0]);
            assertEquals("Track Beta", newTitle[0]);

            subscription.cancel();
        }
    }

    @Nested
    @DisplayName("Flow.Subscriber")
    class FlowSubscriberTests {

        @Test
        @DisplayName("Flow.Subscriber receives entity mutation events via onNext")
        void flowSubscriberReceivesEntityMutationEventsViaOnNext() {
            var audioItem = new MutableAudioItem(1, "Track Alpha");
            List<MutationEvent<Integer, AudioItem>> receivedEvents = new ArrayList<>();
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

            Flow.Subscriber<MutationEvent<Integer, AudioItem>> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                }

                @Override
                public void onNext(MutationEvent<Integer, AudioItem> item) {
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

            audioItem.subscribe(subscriber);
            audioItem.setTitle("Track Beta");
            scheduler.advanceUntilIdle();

            assertNotNull(subscriptionRef.get(), "onSubscribe must be called");
            assertEquals(1, receivedEvents.size());
            var pc0 = (PropertyChanged<?, ?, ?>) receivedEvents.get(0);
            assertEquals("Track Alpha", (String) pc0.getOldValue());
            assertEquals("Track Beta", (String) pc0.getNewValue());
        }

        @Test
        @DisplayName("Flow.Subscriber receives repository CRUD events via onNext")
        void flowSubscriberReceivesRepositoryCrudEventsViaOnNext() {
            var repository = new AudioItemVolatileRepository();
            List<CrudEvent<Integer, ? extends AudioItem>> receivedEvents = new ArrayList<>();
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

            Flow.Subscriber<CrudEvent<Integer, ? extends AudioItem>> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                }

                @Override
                public void onNext(CrudEvent<Integer, ? extends AudioItem> item) {
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
            repository.create(1, "Track Alpha", "");
            scheduler.advanceUntilIdle();

            assertNotNull(subscriptionRef.get(), "onSubscribe must be called");
            assertEquals(1, receivedEvents.size());
            assertTrue(receivedEvents.get(0).isCreate());
            assertEquals("Track Alpha", receivedEvents.get(0).getEntities().get(1).getTitle());

            repository.close();
        }

        @Test
        @DisplayName("Calling request() on subscription throws IllegalStateException")
        void callingRequestOnSubscriptionThrowsIllegalStateException() {
            var audioItem = new MutableAudioItem(1, "Track Alpha");
            AtomicReference<Flow.Subscription> subscriptionRef = new AtomicReference<>();

            Flow.Subscriber<MutationEvent<Integer, AudioItem>> subscriber = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriptionRef.set(subscription);
                }

                @Override
                public void onNext(MutationEvent<Integer, AudioItem> item) {
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

            audioItem.subscribe(subscriber);
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
            var audioItem = new MutableAudioItem(1, "Track Alpha");
            audioItem.close();

            assertTrue(audioItem.isClosed());
            assertThrows(IllegalStateException.class, () -> audioItem.subscribeAsync(event -> {}));
        }

        @Test
        @DisplayName("Subscribing to closed repository throws IllegalStateException")
        void subscribingToClosedRepositoryThrowsIllegalStateException() {
            var repository = new AudioItemVolatileRepository();
            repository.close();

            assertTrue(repository.isClosed());
            assertThrows(IllegalStateException.class, () -> repository.subscribeAsync(event -> {}));
        }
    }

    @Nested
    @DisplayName("AutoCloseable Lifecycle")
    class AutoCloseableLifecycleTests {

        @Test
        @DisplayName("Entity closes properly via try-with-resources")
        void entityClosesProperlyViaTryWithResources() {
            MutableAudioItem[] audioItemRef = new MutableAudioItem[1];

            try (var audioItem = new MutableAudioItem(1, "Track Alpha")) {
                audioItemRef[0] = audioItem;
                var subscription = audioItem.subscribeAsync(event -> {});
                audioItem.setTitle("Track Beta");
                scheduler.advanceUntilIdle();
                subscription.cancel();
            }

            assertTrue(audioItemRef[0].isClosed());
            assertThrows(IllegalStateException.class, () -> audioItemRef[0].subscribeAsync(event -> {}));
        }

        @Test
        @DisplayName("Repository closes properly via try-with-resources")
        void repositoryClosesProperlyViaTryWithResources() {
            AudioItemVolatileRepository[] repoRef = new AudioItemVolatileRepository[1];

            try (var repository = new AudioItemVolatileRepository()) {
                repoRef[0] = repository;
                repository.create(1, "Track Alpha", "");
                scheduler.advanceUntilIdle();
            }

            assertTrue(repoRef[0].isClosed());
            assertThrows(IllegalStateException.class, () -> repoRef[0].subscribeAsync(event -> {}));
        }
    }

    @Nested
    @DisplayName("Registry Queries")
    class RegistryQueryTests {

        @Test
        @DisplayName("search with Predicate returns only matching entities")
        void searchWithPredicateReturnsOnlyMatchingEntities() {
            var repository = new AudioItemVolatileRepository();
            repository.create(1, "Track Alpha", "");
            repository.create(2, "Track Beta", "");
            repository.create(3, "Track Charlie", "");

            var result = repository.search(item -> item.getTitle().startsWith("Track A"));

            assertEquals(1, result.size());
            assertEquals("Track Alpha", result.iterator().next().getTitle());
            repository.close();
        }

        @Test
        @DisplayName("search with size limit returns at most the requested number")
        void searchWithSizeLimitReturnsAtMostTheRequestedNumber() {
            var repository = new AudioItemVolatileRepository();
            repository.create(1, "Track Alpha", "");
            repository.create(2, "Track Beta", "");
            repository.create(3, "Track Charlie", "");

            var result = repository.search(2, item -> true);

            assertEquals(2, result.size());
            repository.close();
        }

        @Test
        @DisplayName("findFirst with Predicate returns a matching entity")
        void findFirstWithPredicateReturnsMatchingEntity() {
            var repository = new AudioItemVolatileRepository();
            repository.create(1, "Track Alpha", "");
            repository.create(2, "Track Beta", "");
            repository.create(3, "Track Charlie", "");

            var result = repository.findFirst(item -> item.getTitle().startsWith("Track B"));

            assertTrue(result.isPresent());
            assertEquals("Track Beta", result.get().getTitle());
            repository.close();
        }

        @Test
        @DisplayName("contains with Predicate detects existing and non-existing entities")
        void containsWithPredicateDetectsExistingAndNonExistingEntities() {
            var repository = new AudioItemVolatileRepository();
            repository.create(1, "Track Alpha", "");
            repository.create(2, "Track Beta", "");

            assertTrue(repository.contains(item -> item.getTitle().equals("Track Alpha")));
            assertFalse(repository.contains(item -> item.getTitle().equals("NonExistent")));
            repository.close();
        }

        @Test
        @DisplayName("iterator returns all entities in the repository")
        void iteratorReturnsAllEntitiesInRepository() {
            var repository = new AudioItemVolatileRepository();
            var alpha = repository.create(1, "Track Alpha", "");
            var beta = repository.create(2, "Track Beta", "");
            var charlie = repository.create(3, "Track Charlie", "");

            var iterated = new ArrayList<AudioItem>();
            for (var item : repository) {
                iterated.add(item);
            }

            assertEquals(3, iterated.size());
            assertTrue(iterated.containsAll(List.of(alpha, beta, charlie)));
            repository.close();
        }

        @Test
        @DisplayName("findByUniqueId returns the entity with the matching unique ID")
        void findByUniqueIdReturnsEntityWithMatchingUniqueId() {
            var repository = new AudioItemVolatileRepository();
            var alpha = repository.create(1, "Track Alpha", "");

            var result = repository.findByUniqueId(alpha.getUniqueId());

            assertTrue(result.isPresent());
            assertEquals(alpha, result.get());
            repository.close();
        }
    }

    @Nested
    @DisplayName("Subscriber Exception Isolation")
    class SubscriberExceptionIsolationTests {

        @Test
        @DisplayName("Java Consumer subscriber exception does not prevent other subscribers from receiving events")
        void javaConsumerSubscriberExceptionDoesNotPreventOtherSubscribersFromReceivingEvents() {
            var audioItem = new MutableAudioItem(1, "Track Alpha");

            // Throwing Consumer — unconditional exception on every event
            audioItem.subscribeAsync(event -> { throw new RuntimeException("intentional Java exception"); });

            var healthyCounter = new AtomicInteger(0);
            audioItem.subscribeAsync(event -> healthyCounter.incrementAndGet());

            audioItem.setTitle("Track Beta");
            audioItem.setTitle("Track Charlie");
            scheduler.advanceUntilIdle();

            assertEquals(2, healthyCounter.get());
        }
    }

    @Nested
    @DisplayName("Aggregate Reference")
    class AggregateReferenceTests {

        LirpContext ctx;
        AudioItemVolatileRepository audioItemRepo;
        AudioPlaylistVolatileRepository playlistRepo;

        @BeforeEach
        void setupRepos() {
            ctx = new LirpContext();
            audioItemRepo = new AudioItemVolatileRepository(ctx);
            playlistRepo = new AudioPlaylistVolatileRepository(ctx);
        }

        @AfterEach
        void cleanupRepos() {
            ctx.close();
        }

        @Test
        @DisplayName("Java can access aggregate ref via getter and call resolve()")
        void javaCanAccessAggregateRefViaGetterAndCallResolve() {
            var bubbleUpRepo = new BubbleUpAudioPlaylistRepo(ctx);
            audioItemRepo.create(1, "Track Alpha", "");
            bubbleUpRepo.create(10, 1);
            scheduler.advanceUntilIdle();

            var bubbleUp = bubbleUpRepo.findById(10).get();
            ReactiveEntityReference<Integer, AudioItem> ref = bubbleUp.getAudioItem();
            assertNotNull(ref);
            assertTrue(ref.resolve().isPresent());
            assertEquals("Track Alpha", ref.resolve().get().getTitle());
        }

        @Test
        @DisplayName("Java resolve returns empty Optional when referenced entity not in repo")
        void javaResolveReturnsEmptyOptionalWhenReferencedEntityNotInRepo() {
            var bubbleUpRepo = new BubbleUpAudioPlaylistRepo(ctx);
            bubbleUpRepo.create(10, 99);
            scheduler.advanceUntilIdle();

            var bubbleUp = bubbleUpRepo.findById(10).get();
            ReactiveEntityReference<Integer, AudioItem> ref = bubbleUp.getAudioItem();
            assertNotNull(ref);
            assertFalse(ref.resolve().isPresent());
        }

        @Test
        @DisplayName("Java subscriber receives AggregateMutationEvent as MutationEvent subtype")
        void javaSubscriberReceivesAggregateMutationEventAsMutationEventSubtype() throws InterruptedException {
            var bubbleUpRepo = new BubbleUpAudioPlaylistRepo(ctx);
            audioItemRepo.create(1, "Track Alpha", "");
            bubbleUpRepo.create(10, 1);
            scheduler.advanceUntilIdle();

            var bubbleUp = bubbleUpRepo.findById(10).get();

            var latch = new CountDownLatch(1);
            var receivedAggregateEvent = new AtomicReference<MutationEvent<?, ?>>(null);

            bubbleUp.subscribeAsync(event -> {
                if (event instanceof AggregateMutationEvent) {
                    receivedAggregateEvent.set(event);
                    latch.countDown();
                }
            });

            ((MutableAudioItem) audioItemRepo.findById(1).get()).setTitle("Track Alpha Updated");
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
        AudioItemVolatileRepository trackRepo;
        AudioPlaylistVolatileRepository playlistRepo;

        @BeforeEach
        void setUp() {
            ctx = new LirpContext();
            trackRepo = new AudioItemVolatileRepository(ctx);
            playlistRepo = new AudioPlaylistVolatileRepository(ctx);
        }

        @AfterEach
        void tearDown() {
            ctx.close();
        }

        @Test
        @DisplayName("Java code adds entity to mutable aggregate list via getAudioItems().add()")
        void javaAddsEntityToMutableAggregateListViaGetAudioItemsAdd() {
            MutableAudioItem track = new MutableAudioItem(1, "Track 1");
            trackRepo.add(track);
            DefaultAudioPlaylist playlist = new DefaultAudioPlaylist(1, "Test Playlist", Collections.emptyList(), Collections.emptySet());
            playlistRepo.add(playlist);

            boolean added = playlist.getAudioItems().add(track);

            assertTrue(added);
            assertTrue(playlist.getAudioItems().getReferenceIds().contains(1));
            assertEquals(1, playlist.getAudioItems().getReferenceIds().size());
        }

        @Test
        @DisplayName("Java code removes entity from mutable aggregate list via getAudioItems().remove()")
        void javaRemovesEntityFromMutableAggregateListViaGetAudioItemsRemove() {
            MutableAudioItem track = new MutableAudioItem(1, "Track 1");
            trackRepo.add(track);
            DefaultAudioPlaylist playlist = new DefaultAudioPlaylist(1, "Test Playlist", List.of(1), Collections.emptySet());
            playlistRepo.add(playlist);

            boolean removed = playlist.getAudioItems().remove(track);

            assertTrue(removed);
            assertTrue(playlist.getAudioItems().getReferenceIds().isEmpty());
        }
    }

    @Nested
    @DisplayName("Subscription Extensions")
    class SubscriptionExtensionTests {

        LirpContext ctx;
        AudioItemVolatileRepository trackRepo;
        AudioPlaylistVolatileRepository playlistRepo;

        @BeforeEach
        void setUp() {
            ctx = new LirpContext();
            trackRepo = new AudioItemVolatileRepository(ctx);
            playlistRepo = new AudioPlaylistVolatileRepository(ctx);
        }

        @AfterEach
        void tearDown() {
            ctx.close();
        }

        @Test
        @DisplayName("subscribeToCollectionChanges Java Consumer overload receives CollectionChangeEvent")
        void subscribeToCollectionChanges_Java_Consumer_receives_events() throws InterruptedException {
            MutableAudioItem track = new MutableAudioItem(1, "Track 1");
            trackRepo.add(track);
            DefaultAudioPlaylist playlist = new DefaultAudioPlaylist(1, "Test Playlist", Collections.emptyList(), Collections.emptySet());
            playlistRepo.add(playlist);

            var latch = new CountDownLatch(1);
            AtomicReference<CollectionChangeEvent<?>> receivedEvent = new AtomicReference<>(null);

            CollectionChangeEventExtensionsKt.subscribeToCollectionChanges(playlist, AudioItem.class, null,
                (Consumer<CollectionChangeEvent<AudioItem>>) event -> {
                    receivedEvent.set(event);
                    latch.countDown();
                });

            playlist.getAudioItems().add(track);
            scheduler.advanceUntilIdle();

            assertTrue(latch.await(2, SECONDS));
            assertNotNull(receivedEvent.get());
            assertEquals(CollectionChangeEvent.Type.ADD, receivedEvent.get().getType());
        }

        @Test
        @DisplayName("subscribeToMutations Java Consumer overload receives MutationEvent for property change")
        void subscribeToMutations_Java_Consumer_receives_events() throws InterruptedException {
            var audioItem = new MutableAudioItem(1, "Track Alpha");

            var latch = new CountDownLatch(1);
            AtomicReference<MutationEvent<?, ?>> receivedEvent = new AtomicReference<>(null);

            CollectionChangeEventExtensionsKt.subscribeToMutations(audioItem,
                (Consumer<MutationEvent<Integer, AudioItem>>) event -> {
                    receivedEvent.set(event);
                    latch.countDown();
                });

            audioItem.setTitle("Track Beta");
            scheduler.advanceUntilIdle();

            assertTrue(latch.await(2, SECONDS));
            assertNotNull(receivedEvent.get());
            assertInstanceOf(PropertyChanged.class, receivedEvent.get());
        }
    }

    @Nested
    @DisplayName("Repository CRUD")
    class RepositoryCrudTests {

        @Test
        @DisplayName("create publishes CREATE event with the added entity")
        void createPublishesCreateEventWithAddedEntity() {
            var repository = new AudioItemVolatileRepository();
            var eventEntities = new ArrayList<AudioItem>();

            var subscription = repository.subscribeAsync(
                event -> eventEntities.addAll(event.getEntities().values()));

            repository.create(1, "Track Alpha", "");
            scheduler.advanceUntilIdle();

            assertEquals(1, eventEntities.size());
            assertEquals("Track Alpha", eventEntities.get(0).getTitle());

            subscription.cancel();
            repository.close();
        }

        @Test
        @DisplayName("remove deletes entity and publishes DELETE event")
        void removeDeletesEntityAndPublishesDeleteEvent() {
            var repository = new AudioItemVolatileRepository();
            var alpha = repository.create(1, "Track Alpha", "");
            repository.create(1, "Track Alpha", "");

            List<CrudEvent<Integer, ? extends AudioItem>> receivedEvents = new ArrayList<>();
            var subscription = repository.subscribeAsync(event -> receivedEvents.add(event));

            repository.remove(alpha);
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
            var repository = new AudioItemVolatileRepository();
            var alpha = repository.create(1, "Track Alpha", "");
            var beta = repository.create(2, "Track Beta", "");
            var charlie = repository.create(3, "Track Charlie", "");

            repository.removeAll(List.of(alpha, beta));
            scheduler.advanceUntilIdle();

            assertEquals(1, repository.size());
            assertTrue(repository.findById(3).isPresent());
            assertEquals(charlie, repository.findById(3).get());
            repository.close();
        }

        @Test
        @DisplayName("clear removes all entities and publishes DELETE event")
        void clearRemovesAllEntitiesAndPublishesDeleteEvent() {
            var repository = new AudioItemVolatileRepository();
            repository.create(1, "Track Alpha", "");
            repository.create(2, "Track Beta", "");
            repository.create(3, "Track Charlie", "");

            List<CrudEvent<Integer, ? extends AudioItem>> receivedEvents = new ArrayList<>();
            var subscription = repository.subscribeAsync(event -> receivedEvents.add(event));

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

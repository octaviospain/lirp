package net.transgressoft.lirp.persistence.fx;

import javafx.collections.MapChangeListener;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.SupervisorKt;
import net.transgressoft.lirp.event.ReactiveScope;
import net.transgressoft.lirp.persistence.AudioItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability tests verifying that {@link FxProperties#fxProjectionMap} static factory
 * method is accessible from Java and that the resulting projection correctly groups entities
 * and fires {@link MapChangeListener} notifications for both list and set sources.
 */
@DisplayName("FxProjectionMap Java interop")
class FxProjectionMapJavaInteropTest {

    @BeforeAll
    static void initToolkit() {
        FxToolkitInit.INSTANCE.ensureInitialized();
        ReactiveScope.INSTANCE.setFlowScope(
            CoroutineScopeKt.CoroutineScope(
                Dispatchers.getUnconfined().plus(SupervisorKt.SupervisorJob(null))
            )
        );
    }

    @AfterAll
    static void resetScope() {
        ReactiveScope.INSTANCE.resetDefaultFlowScope();
    }

    @Test
    @DisplayName("FxProperties.fxProjectionMap creates projection from list source in Java")
    void projectionMapFromListSource() {
        FxAggregateList<Integer, AudioItem> source = FxProperties.fxAggregateList(List.of(), false);

        FxProjectionMap<Integer, String, AudioItem> audioItemsByAlbum =
            FxProperties.fxProjectionMap(() -> source, AudioItem::getAlbumName, false);

        assertTrue(audioItemsByAlbum.isEmpty(), "Map is empty before any items are added");

        FxAudioItem item1 = new FxAudioItem(1, "Track 1", "Album A");
        FxAudioItem item2 = new FxAudioItem(2, "Track 2", "Album A");
        FxAudioItem item3 = new FxAudioItem(3, "Track 3", "Album B");

        source.add(item1);
        source.add(item2);
        source.add(item3);

        assertEquals(2, audioItemsByAlbum.size(), "Map has two keys after adding items to two albums");
        assertTrue(audioItemsByAlbum.containsKey("Album A"), "Map contains key Album A");
        assertTrue(audioItemsByAlbum.containsKey("Album B"), "Map contains key Album B");
        assertEquals(2, audioItemsByAlbum.get("Album A").size(), "Album A has two items");
        assertEquals(1, audioItemsByAlbum.get("Album B").size(), "Album B has one item");
    }

    @Test
    @DisplayName("FxProperties.fxProjectionMap fires MapChangeListener from Java")
    @SuppressWarnings("unchecked")
    void projectionMapFiresMapChangeListener() {
        FxAggregateList<Integer, AudioItem> source = FxProperties.fxAggregateList(List.of(), false);

        FxProjectionMap<Integer, String, AudioItem> projection =
            FxProperties.fxProjectionMap(() -> source, AudioItem::getAlbumName, false);

        AtomicBoolean listenerFired = new AtomicBoolean(false);
        AtomicReference<String> addedKey = new AtomicReference<>();

        projection.addListener((MapChangeListener<? super String, ? super List<? extends AudioItem>>) change -> {
            if (change.wasAdded()) {
                listenerFired.set(true);
                addedKey.set(change.getKey());
            }
        });

        FxAudioItem item = new FxAudioItem(10, "Song X", "New Album");
        source.add(item);

        assertTrue(listenerFired.get(), "MapChangeListener was fired after adding to source");
        assertEquals("New Album", addedKey.get(), "MapChangeListener received the correct key");
    }

    @Test
    @DisplayName("FxProperties.fxProjectionMap creates projection from set source in Java")
    void projectionMapFromSetSource() {
        FxAggregateSet<Integer, AudioItem> source = FxProperties.fxAggregateSet(Set.of(), false);

        FxProjectionMap<Integer, String, AudioItem> projection =
            FxProperties.fxProjectionMap(() -> source, AudioItem::getAlbumName, false);

        assertTrue(projection.isEmpty(), "Map is empty before any items are added");

        FxAudioItem item1 = new FxAudioItem(1, "Track 1", "Album A");
        FxAudioItem item2 = new FxAudioItem(2, "Track 2", "Album B");

        source.add(item1);
        source.add(item2);

        assertEquals(2, projection.size(), "Map has two keys after adding items to two albums");
        assertTrue(projection.containsKey("Album A"), "Map contains key Album A");
        assertTrue(projection.containsKey("Album B"), "Map contains key Album B");
    }
}

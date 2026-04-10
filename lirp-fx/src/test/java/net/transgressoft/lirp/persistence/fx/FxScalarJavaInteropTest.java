package net.transgressoft.lirp.persistence.fx;

import net.transgressoft.lirp.persistence.AudioItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability tests verifying that {@link FxProperties} @JvmStatic factory methods
 * are accessible from Java and return correctly initialized lirp-fx property instances.
 */
@DisplayName("FxScalarJavaInteropTest")
class FxScalarJavaInteropTest {

    @BeforeAll
    static void initToolkit() {
        FxToolkitInit.INSTANCE.ensureInitialized();
    }

    @Nested
    @DisplayName("Scalar factory access")
    class ScalarFactoryTests {

        @Test
        @DisplayName("FxProperties.fxString() creates LirpStringProperty")
        void stringFactory() {
            LirpStringProperty prop = FxProperties.fxString("hello", true);
            assertEquals("hello", prop.get());
        }

        @Test
        @DisplayName("FxProperties.fxInteger() creates LirpIntegerProperty")
        void integerFactory() {
            LirpIntegerProperty prop = FxProperties.fxInteger(42, true);
            assertEquals(42, prop.get());
        }

        @Test
        @DisplayName("FxProperties.fxDouble() creates LirpDoubleProperty")
        void doubleFactory() {
            LirpDoubleProperty prop = FxProperties.fxDouble(3.14, true);
            assertEquals(3.14, prop.get(), 0.001);
        }

        @Test
        @DisplayName("FxProperties.fxFloat() creates LirpFloatProperty")
        void floatFactory() {
            LirpFloatProperty prop = FxProperties.fxFloat(1.5f, true);
            assertEquals(1.5f, prop.get(), 0.001f);
        }

        @Test
        @DisplayName("FxProperties.fxLong() creates LirpLongProperty")
        void longFactory() {
            LirpLongProperty prop = FxProperties.fxLong(100L, true);
            assertEquals(100L, prop.get());
        }

        @Test
        @DisplayName("FxProperties.fxBoolean() creates LirpBooleanProperty")
        void booleanFactory() {
            LirpBooleanProperty prop = FxProperties.fxBoolean(true, true);
            assertTrue(prop.get());
        }

        @Test
        @DisplayName("FxProperties.fxObject() supports nullable types")
        void objectFactory() {
            LirpObjectProperty<String> prop = FxProperties.fxObject(null, true);
            assertNull(prop.get());
            prop.set("value");
            assertEquals("value", prop.get());
        }
    }

    @Nested
    @DisplayName("Collection factory access")
    class CollectionFactoryTests {

        @Test
        @DisplayName("FxProperties.fxAggregateList() creates FxAggregateList")
        void aggregateListFactory() {
            var list = FxProperties.<Integer, AudioItem>fxAggregateList(List.of(), true);
            assertNotNull(list);
            assertTrue(list instanceof javafx.collections.ObservableList);
        }

        @Test
        @DisplayName("FxProperties.fxAggregateSet() creates FxAggregateSet")
        void aggregateSetFactory() {
            var set = FxProperties.<Integer, AudioItem>fxAggregateSet(Set.of(), true);
            assertNotNull(set);
            assertTrue(set instanceof javafx.collections.ObservableSet);
        }
    }
}

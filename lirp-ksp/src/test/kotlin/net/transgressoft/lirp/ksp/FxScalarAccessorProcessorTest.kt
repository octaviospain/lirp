/******************************************************************************
 *     Copyright (C) 2025  Octavio Calleya Garcia                             *
 *                                                                            *
 *     This program is free software: you can redistribute it and/or modify   *
 *     it under the terms of the GNU General Public License as published by   *
 *     the Free Software Foundation, either version 3 of the License, or      *
 *     (at your option) any later version.                                    *
 *                                                                            *
 *     This program is distributed in the hope that it will be useful,        *
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of         *
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
 *     GNU General Public License for more details.                           *
 *                                                                            *
 *     You should have received a copy of the GNU General Public License      *
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>. *
 ******************************************************************************/

package net.transgressoft.lirp.ksp

import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for [FxScalarAccessorProcessor], verifying that the processor generates
 * correct `_LirpFxScalarAccessor` implementations for entities with FxScalar delegate properties.
 *
 * Each test compiles a source entity in-process using kctfork and asserts on the generated file content.
 * Positive-case stubs are defined in `javafx.beans.property` so the JavaFX-FQN guard in the processor
 * passes for the six primitive types and `ObjectProperty`. A separate negative stub in a non-JavaFX
 * package verifies that custom `*Property`-suffixed types fall through to the `else` default.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("FxScalarAccessorProcessor")
internal class FxScalarAccessorProcessorTest : StringSpec({

    // Stubs for JavaFX property types that implement FxScalarPropertyDelegate.
    // Each stub class name ends with the expected suffix (e.g. "StringProperty", "IntegerProperty")
    // and lives in javafx.beans.property so the processor's JavaFX-FQN guard passes and the correct
    // primitive serializer is selected.
    val fxPropertyStubs =
        SourceFile.kotlin(
            "FxPropertyStubs.kt",
            """
            package javafx.beans.property

            import net.transgressoft.lirp.persistence.FxScalarPropertyDelegate
            import kotlin.reflect.KProperty

            class StubStringProperty(private var value: String? = null) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
                fun get(): String? = value
                fun set(v: String?) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubStringProperty = this
            }

            class StubIntegerProperty(private var value: Int = 0) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
                fun get(): Int = value
                fun set(v: Int) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubIntegerProperty = this
            }

            class StubDoubleProperty(private var value: Double = 0.0) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
                fun get(): Double = value
                fun set(v: Double) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubDoubleProperty = this
            }

            class StubFloatProperty(private var value: Float = 0.0f) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
                fun get(): Float = value
                fun set(v: Float) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubFloatProperty = this
            }

            class StubLongProperty(private var value: Long = 0L) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
                fun get(): Long = value
                fun set(v: Long) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubLongProperty = this
            }

            class StubBooleanProperty(private var value: Boolean = false) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
                fun get(): Boolean = value
                fun set(v: Boolean) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubBooleanProperty = this
            }

            class StubObjectProperty<T>(private var value: T? = null) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
                fun get(): T? = value
                fun set(v: T?) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubObjectProperty<T> = this
            }
            """
        )

    "generates _LirpFxScalarAccessor with correct entry for entity with StringProperty delegate" {
        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "ProductEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import javafx.beans.property.StubStringProperty

                    data class ProductEntity(override val id: Int) : ReactiveEntityBase<Int, ProductEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        val title by StubStringProperty()
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("ProductEntity_LirpFxScalarAccessor.kt")
        content.shouldContainEach(
            "class ProductEntity_LirpFxScalarAccessor : LirpFxScalarAccessor<ProductEntity>",
            "override val entries: List<FxScalarEntry<ProductEntity>>",
            "name = \"title\"",
            "getter = { it.title.get() }",
            "setter = { entity, value -> entity.title.set(value as String?) }",
            "serializer<String?>()"
        )
    }

    "generates entries with correct serializer types for all six scalar property types" {
        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "AllScalarsEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import javafx.beans.property.StubStringProperty
                    import javafx.beans.property.StubIntegerProperty
                    import javafx.beans.property.StubDoubleProperty
                    import javafx.beans.property.StubFloatProperty
                    import javafx.beans.property.StubLongProperty
                    import javafx.beans.property.StubBooleanProperty

                    data class AllScalarsEntity(override val id: Int) : ReactiveEntityBase<Int, AllScalarsEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        val name by StubStringProperty()
                        val count by StubIntegerProperty()
                        val ratio by StubDoubleProperty()
                        val weight by StubFloatProperty()
                        val size by StubLongProperty()
                        val active by StubBooleanProperty()
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("AllScalarsEntity_LirpFxScalarAccessor.kt")
        content.shouldContainEach(
            "serializer<String?>()",
            "serializer<Int>()",
            "serializer<Double>()",
            "serializer<Float>()",
            "serializer<Long>()",
            "serializer<Boolean>()",
            "name = \"name\"",
            "name = \"count\"",
            "name = \"ratio\"",
            "name = \"weight\"",
            "name = \"size\"",
            "name = \"active\""
        )
    }

    "generates entry with typed serializer for entity with ObjectProperty type argument" {
        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "TaggedEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import javafx.beans.property.StubObjectProperty
                    import kotlinx.serialization.Serializable

                    @Serializable
                    data class Tag(val value: String)

                    data class TaggedEntity(override val id: Int) : ReactiveEntityBase<Int, TaggedEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        val tag by StubObjectProperty<Tag>()
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("TaggedEntity_LirpFxScalarAccessor.kt")
        content.shouldContainEach(
            "name = \"tag\"",
            "serializer<test.Tag?>()",
            "getter = { it.tag.get() }",
            "setter = { entity, value -> entity.tag.set(value as test.Tag?) }"
        )
    }

    "does not generate accessor file for entity with no FxScalar delegate properties" {
        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                SourceFile.kotlin(
                    "PlainEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class PlainEntity(override val id: Int, val name: String) : ReactiveEntityBase<Int, PlainEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldSucceed()
        val generatedFiles = result.generatedNames()
        generatedFiles.contains("PlainEntity_LirpFxScalarAccessor.kt") shouldBe false
    }

    "FxScalarAccessorProcessor emits internal class declaration for top-level internal entity" {
        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "InternalFxEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import javafx.beans.property.StubStringProperty

                    internal data class InternalFxEntity(override val id: Int) : ReactiveEntityBase<Int, InternalFxEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        val label by StubStringProperty()
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("InternalFxEntity_LirpFxScalarAccessor.kt")
        content shouldContain "internal class InternalFxEntity_LirpFxScalarAccessor"
    }

    "FxScalarAccessorProcessor emits internal class declaration for entity nested in internal outer" {
        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "InternalOuterFx.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import javafx.beans.property.StubStringProperty

                    internal class InternalOuterFx {
                        data class InnerFx(override val id: Int) : ReactiveEntityBase<Int, InnerFx>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            val title by StubStringProperty()
                        }
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("InternalOuterFx\$InnerFx_LirpFxScalarAccessor.kt")
        content shouldContain "internal class"
    }

    "FxScalarAccessorProcessor silently skips private-nested entity without generating a file" {
        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "PrivateOuterFx.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import javafx.beans.property.StubStringProperty

                    private class PrivateOuterFx {
                        data class HiddenFx(override val id: Int) : ReactiveEntityBase<Int, HiddenFx>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            val name by StubStringProperty()
                        }
                    }
                    """
                )
            )

        result.shouldSucceed()
        // Structural processors silently skip private/protected entities
        val generatedNames = result.generatedNames()
        generatedNames.contains("PrivateOuterFx\$HiddenFx_LirpFxScalarAccessor.kt") shouldBe false
    }

    "generates accessor with correct JVM binary name for nested entity class" {
        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "OuterContainer.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import javafx.beans.property.StubStringProperty

                    class OuterContainer {
                        data class InnerEntity(override val id: Int) : ReactiveEntityBase<Int, InnerEntity>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            val label by StubStringProperty()
                        }
                    }
                    """
                )
            )

        result.shouldSucceed()
        val generatedFiles = result.generatedNames()
        generatedFiles.contains("OuterContainer\$InnerEntity_LirpFxScalarAccessor.kt") shouldBe true
        val content = result.generatedFileContent("OuterContainer\$InnerEntity_LirpFxScalarAccessor.kt")
        content.shouldContainEach(
            "class `OuterContainer\$InnerEntity_LirpFxScalarAccessor` : LirpFxScalarAccessor<OuterContainer.InnerEntity>",
            "name = \"label\""
        )
    }

    // #346: a custom type whose name ends with "StringProperty" but lives outside javafx.beans.property
    // must fall through to the else default, not be mis-classified as a String property.
    "FxScalarAccessorProcessor generates else-default serializer for custom *StringProperty type not in javafx.beans.property" {
        val customStringPropertyStub =
            SourceFile.kotlin(
                "CustomStringPropertyStub.kt",
                """
                package net.transgressoft.lirp.persistence.fx

                import net.transgressoft.lirp.persistence.FxScalarPropertyDelegate
                import kotlin.reflect.KProperty

                // Custom type whose name ends with "StringProperty" but is not in javafx.beans.property.
                // The generated accessor falls through to the else default (String? serializer/cast).
                // The set() signature accepts Any? so the generated "value as String?" cast compiles,
                // but the serializer emitted is the wrong String? fallback — not a domain-correct one.
                class LocalizedStringProperty(private var value: Any? = null) : FxScalarPropertyDelegate {
                    override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
                    fun get(): Any? = value
                    fun set(v: Any?) { value = v }
                    operator fun getValue(thisRef: Any?, property: KProperty<*>): LocalizedStringProperty = this
                }
                """
            )

        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                customStringPropertyStub,
                SourceFile.kotlin(
                    "LocalizedEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.fx.LocalizedStringProperty

                    data class LocalizedEntity(override val id: Int) : ReactiveEntityBase<Int, LocalizedEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        val label by LocalizedStringProperty()
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("LocalizedEntity_LirpFxScalarAccessor.kt")
        // Must use the else-default String? serializer, not serializer<Int>() or any non-String specialist
        content.shouldContainEach(
            "serializer<String?>()",
            "value as String?"
        )
    }

    // #347: a nullable-payload ObjectProperty must generate a single-? type, not Foo??.
    "FxScalarAccessorProcessor generates single-? serializer for nullable-payload ObjectProperty" {
        val result =
            KspTestSupport.compile(
                FxScalarAccessorProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "NullableTaggedEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import javafx.beans.property.StubObjectProperty
                    import kotlinx.serialization.Serializable

                    @Serializable
                    data class Tag(val value: String)

                    data class NullableTaggedEntity(override val id: Int) : ReactiveEntityBase<Int, NullableTaggedEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        val tag by StubObjectProperty<Tag?>()
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("NullableTaggedEntity_LirpFxScalarAccessor.kt")
        content.shouldContainEach(
            "serializer<test.Tag?>()",
            "value as test.Tag?"
        )
        // Verify no double-? is emitted
        (content.contains("test.Tag??")) shouldBe false
    }
})
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

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import com.tschuchort.compiletesting.symbolProcessorProviders
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
 * FxScalar property stubs are defined inline in the `net.transgressoft.lirp.persistence` package
 * to satisfy the processor's FQN-based `FxScalarPropertyDelegate` detection without importing JavaFX.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("FxScalarAccessorProcessor")
internal class FxScalarAccessorProcessorTest : StringSpec({

    // Stubs for JavaFX property types that implement FxScalarPropertyDelegate.
    // Each stub class name ends with the expected suffix (e.g. "StringProperty", "IntegerProperty")
    // so the processor's FQN-suffix-based serializer mapping resolves correctly.
    // Stubs live in net.transgressoft.lirp.persistence so the processor's FQN detection works.
    val fxPropertyStubs =
        SourceFile.kotlin(
            "FxPropertyStubs.kt",
            """
            package net.transgressoft.lirp.persistence

            import kotlin.reflect.KProperty

            class StubStringProperty(private var value: String? = null) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (() -> Unit) -> Unit) {}
                fun get(): String? = value
                fun set(v: String?) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubStringProperty = this
            }

            class StubIntegerProperty(private var value: Int = 0) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (() -> Unit) -> Unit) {}
                fun get(): Int = value
                fun set(v: Int) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubIntegerProperty = this
            }

            class StubDoubleProperty(private var value: Double = 0.0) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (() -> Unit) -> Unit) {}
                fun get(): Double = value
                fun set(v: Double) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubDoubleProperty = this
            }

            class StubFloatProperty(private var value: Float = 0.0f) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (() -> Unit) -> Unit) {}
                fun get(): Float = value
                fun set(v: Float) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubFloatProperty = this
            }

            class StubLongProperty(private var value: Long = 0L) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (() -> Unit) -> Unit) {}
                fun get(): Long = value
                fun set(v: Long) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubLongProperty = this
            }

            class StubBooleanProperty(private var value: Boolean = false) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (() -> Unit) -> Unit) {}
                fun get(): Boolean = value
                fun set(v: Boolean) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubBooleanProperty = this
            }

            class StubObjectProperty<T>(private var value: T? = null) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (() -> Unit) -> Unit) {}
                fun get(): T? = value
                fun set(v: T?) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubObjectProperty<T> = this
            }
            """
        )

    fun compileWithProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.configureKsp { withCompilation = true }
        compilation.symbolProcessorProviders += FxScalarAccessorProcessorProvider()
        return compilation.compile()
    }

    fun JvmCompilationResult.generatedFileContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error("Generated file '$name' not found among: ${sourcesGeneratedBySymbolProcessor.map { it.name }.toList()}")
        return file.readText()
    }

    "generates _LirpFxScalarAccessor with correct entry for entity with StringProperty delegate" {
        val result =
            compileWithProcessor(
                fxPropertyStubs,
                SourceFile.kotlin(
                    "ProductEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.StubStringProperty

                    data class ProductEntity(override val id: Int) : ReactiveEntityBase<Int, ProductEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        val title by StubStringProperty()
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ProductEntity_LirpFxScalarAccessor.kt")
        content shouldContain "class ProductEntity_LirpFxScalarAccessor : LirpFxScalarAccessor<ProductEntity>"
        content shouldContain "override val entries: List<FxScalarEntry<ProductEntity>>"
        content shouldContain "name = \"title\""
        content shouldContain "getter = { it.title.get() }"
        content shouldContain "setter = { entity, value -> entity.title.set(value as String?) }"
        content shouldContain "serializer<String?>()"
    }

    "generates entries with correct serializer types for all six scalar property types" {
        val result =
            compileWithProcessor(
                fxPropertyStubs,
                SourceFile.kotlin(
                    "AllScalarsEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.StubStringProperty
                    import net.transgressoft.lirp.persistence.StubIntegerProperty
                    import net.transgressoft.lirp.persistence.StubDoubleProperty
                    import net.transgressoft.lirp.persistence.StubFloatProperty
                    import net.transgressoft.lirp.persistence.StubLongProperty
                    import net.transgressoft.lirp.persistence.StubBooleanProperty

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

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("AllScalarsEntity_LirpFxScalarAccessor.kt")
        content shouldContain "serializer<String?>()"
        content shouldContain "serializer<Int>()"
        content shouldContain "serializer<Double>()"
        content shouldContain "serializer<Float>()"
        content shouldContain "serializer<Long>()"
        content shouldContain "serializer<Boolean>()"
        content shouldContain "name = \"name\""
        content shouldContain "name = \"count\""
        content shouldContain "name = \"ratio\""
        content shouldContain "name = \"weight\""
        content shouldContain "name = \"size\""
        content shouldContain "name = \"active\""
    }

    "generates entry with typed serializer for entity with ObjectProperty type argument" {
        val result =
            compileWithProcessor(
                fxPropertyStubs,
                SourceFile.kotlin(
                    "TaggedEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.StubObjectProperty
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

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("TaggedEntity_LirpFxScalarAccessor.kt")
        content shouldContain "name = \"tag\""
        content shouldContain "serializer<test.Tag?>()"
        content shouldContain "getter = { it.tag.get() }"
        content shouldContain "setter = { entity, value -> entity.tag.set(value as test.Tag?) }"
    }

    "does not generate accessor file for entity with no FxScalar delegate properties" {
        val result =
            compileWithProcessor(
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

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generatedFiles = result.sourcesGeneratedBySymbolProcessor.map { it.name }
        generatedFiles.contains("PlainEntity_LirpFxScalarAccessor.kt") shouldBe false
    }

    "generates accessor with correct JVM binary name for nested entity class" {
        val result =
            compileWithProcessor(
                fxPropertyStubs,
                SourceFile.kotlin(
                    "OuterContainer.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.StubStringProperty

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

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generatedFiles = result.sourcesGeneratedBySymbolProcessor.map { it.name }
        generatedFiles.contains("OuterContainer\$InnerEntity_LirpFxScalarAccessor.kt") shouldBe true
        val content = result.generatedFileContent("OuterContainer\$InnerEntity_LirpFxScalarAccessor.kt")
        content shouldContain "class `OuterContainer\$InnerEntity_LirpFxScalarAccessor` : LirpFxScalarAccessor<OuterContainer.InnerEntity>"
        content shouldContain "name = \"label\""
    }
})
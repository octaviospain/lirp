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
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for [ReactivePropertyAccessorProcessor], verifying that the processor
 * generates correct `_LirpReactivePropertyAccessor` implementations for entities with
 * `reactiveProperty(...)`-delegated properties.
 *
 * Each test compiles a source entity in-process using kctfork against the real lirp-core classpath
 * (which provides `ReactiveEntityBase`, `reactiveProperty`, `ReactivePropertyDelegate`,
 * `LirpReactivePropertyAccessor`, `ReactivePropertyEntry`, and `writeReactivePropertyBackingField`).
 *
 * Detection API: KSP does not directly expose the delegate-expression type for a
 * `var x: T by reactiveProperty(...)` property — `KSPropertyDeclaration.type.resolve()` returns
 * the declared value type (`T`), not the delegate type. KSP's only public hook is
 * `KSPropertyDeclaration.isDelegated()` (verified against
 * com.google.devtools.ksp:symbol-processing-api:2.3.8 `KSPropertyDeclaration.kt`). The processor
 * therefore applies a composite heuristic — `isDelegated && isMutable && !isFxScalarType(value) &&
 * !isKotlinCollectionType(value)` — which admits any `var T by <delegate>` where `T` is a
 * scalar/nullable value type. This covers ordinary `reactiveProperty(...)`, `@Version`, and
 * `@Aggregate` single-ref Id properties (all reactive-backed per RESEARCH.md), and excludes
 * FxScalar-typed delegates and `aggregateList`/`aggregateSet` collection delegates.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("ReactivePropertyAccessorProcessor")
internal class ReactivePropertyAccessorProcessorTest : StringSpec({

    fun compileWithProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.configureKsp { withCompilation = true }
        compilation.symbolProcessorProviders += ReactivePropertyAccessorProcessorProvider()
        return compilation.compile()
    }

    fun JvmCompilationResult.generatedFileContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error("Generated file '$name' not found among: ${sourcesGeneratedBySymbolProcessor.map { it.name }.toList()}")
        return file.readText()
    }

    "generates accessor for entity with single reactiveProperty Int field" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "Foo.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class Foo(override val id: Int) : ReactiveEntityBase<Int, Foo>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var x: Int by reactiveProperty(0)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("Foo_LirpReactivePropertyAccessor.kt")
        content shouldContain "class Foo_LirpReactivePropertyAccessor"
        content shouldContain "ReactivePropertyEntry"
        content shouldContain "name = \"x\""
        content shouldContain "writeReactivePropertyBackingField"
    }

    "skips entities with no reactiveProperty fields" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "Plain.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class Plain(override val id: Int, val name: String) : ReactiveEntityBase<Int, Plain>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generatedNames = result.sourcesGeneratedBySymbolProcessor.map { it.name }
        generatedNames.contains("Plain_LirpReactivePropertyAccessor.kt") shouldBe false
    }

    "ReactivePropertyAccessorProcessor emits internal class declaration for top-level internal entity" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "InternalBar.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    internal data class InternalBar(override val id: Int) : ReactiveEntityBase<Int, InternalBar>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var label: String by reactiveProperty("")
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InternalBar_LirpReactivePropertyAccessor.kt")
        content shouldContain "internal class InternalBar_LirpReactivePropertyAccessor"
    }

    "ReactivePropertyAccessorProcessor emits internal class declaration for entity nested in internal outer" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "InternalOuterReactive.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    internal class InternalOuterReactive {
                        data class InnerReactive(override val id: Int) : ReactiveEntityBase<Int, InnerReactive>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            var name: String by reactiveProperty("")
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InternalOuterReactive\$InnerReactive_LirpReactivePropertyAccessor.kt")
        content shouldContain "internal class"
    }

    "ReactivePropertyAccessorProcessor silently skips private-nested entity without generating a file" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "PrivateOuterReactive.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    private class PrivateOuterReactive {
                        data class HiddenReactive(override val id: Int) : ReactiveEntityBase<Int, HiddenReactive>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            var x: Int by reactiveProperty(0)
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        // Structural processors silently skip private/protected entities
        val generatedNames = result.sourcesGeneratedBySymbolProcessor.map { it.name }
        generatedNames.contains("PrivateOuterReactive\$HiddenReactive_LirpReactivePropertyAccessor.kt") shouldBe false
    }

    "ReactivePropertyAccessorProcessor skips private reactive delegates" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "WithPrivateDelegate.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class WithPrivateDelegate(override val id: Int) : ReactiveEntityBase<Int, WithPrivateDelegate>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var publicName: String by reactiveProperty("")
                        private var secret: Int by reactiveProperty(0)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("WithPrivateDelegate_LirpReactivePropertyAccessor.kt")
        content shouldContain "name = \"publicName\""
        content shouldNotContain "name = \"secret\""
        content shouldNotContain "\"secret\""
    }

    "handles entity with multiple reactiveProperty fields" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "Bar.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class Bar(override val id: Int) : ReactiveEntityBase<Int, Bar>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var a: String by reactiveProperty("")
                        var b: Int? by reactiveProperty(null)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("Bar_LirpReactivePropertyAccessor.kt")
        content shouldContain "name = \"a\""
        content shouldContain "name = \"b\""
        // Two ReactivePropertyEntry constructor calls in the generated list.
        val entryCount = Regex.fromLiteral("ReactivePropertyEntry(").findAll(content).count()
        entryCount shouldBe 2
    }
})
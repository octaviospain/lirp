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
 * KSP compilation tests for [RawInitializerProcessor], verifying that the processor generates
 * `_LirpRawInitializer` files for entities with reactive-property and non-reactive `var`
 * scalar fields, while excluding collection-typed `@Aggregate` properties.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("RawInitializerProcessor")
internal class RawInitializerProcessorTest : StringSpec({

    fun compileWithProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.configureKsp { withCompilation = true }
        compilation.symbolProcessorProviders += RawInitializerProcessorProvider()
        return compilation.compile()
    }

    fun JvmCompilationResult.generatedFileContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error("Generated file '$name' not found among: " + sourcesGeneratedBySymbolProcessor.map { it.name }.toList())
        return file.readText()
    }

    "generates initializer for entity with reactiveProperty + non-reactive var scalar" {
        // `lastDateModified` is inherited from ReactiveEntityBase as a plain `var LocalDateTime` —
        // it is not a constructor parameter and not delegated, so the processor must emit a
        // raw-init entry for it alongside the reactive `x`.
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
        val content = result.generatedFileContent("Foo_LirpRawInitializer.kt")
        content shouldContain "class Foo_LirpRawInitializer"
        // At least two entries: x (reactive) and the inherited non-reactive var lastDateModified.
        val entryCount = Regex.fromLiteral("RawInitEntry(").findAll(content).count()
        (entryCount >= 2) shouldBe true
        content shouldContain "writeReactivePropertyBackingField"
        content shouldContain "name = \"x\""
        content shouldContain "name = \"lastDateModified\""
        // Plain var assignment path for non-reactive scalar
        content shouldContain "entity.lastDateModified = value as"
    }

    "generates initializer for entity with @Version field" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "Versioned.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Version

                    data class Versioned(override val id: Int) : ReactiveEntityBase<Int, Versioned>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Version var version: Long by reactiveProperty(0L)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("Versioned_LirpRawInitializer.kt")
        content shouldContain "name = \"version\""
        content shouldContain "writeReactivePropertyBackingField"
    }

    "generates initializer for entity with @Aggregate single-ref Id" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "WithOwner.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class WithOwner(override val id: Int) : ReactiveEntityBase<Int, WithOwner>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var ownerId: Int? by reactiveProperty(null)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("WithOwner_LirpRawInitializer.kt")
        content shouldContain "name = \"ownerId\""
        content shouldContain "writeReactivePropertyBackingField"
    }

    "skips collection-ref properties" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "Parent.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class Parent(override val id: Int) : ReactiveEntityBase<Int, Parent>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var name: String by reactiveProperty("")
                        var childIds: List<Int> = emptyList()
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("Parent_LirpRawInitializer.kt")
        content shouldContain "name = \"name\""
        // childIds is a plain `var List<Int>` (no @Aggregate annotation in this minimal test);
        // it is collection-typed so it must NOT receive a raw-init entry. The detection mirrors
        // the runtime behaviour for aggregateList/aggregateSet collection refs.
        content shouldNotContain "name = \"childIds\""
    }
})
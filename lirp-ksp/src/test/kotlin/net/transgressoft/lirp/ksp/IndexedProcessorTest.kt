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

/**
 * KSP compilation tests for [IndexedProcessor], verifying that the processor generates correct
 * `_LirpIndexAccessor` implementations including the `sorted` flag in [IndexEntry] and that the
 * compile-time `Comparable<*>` check rejects non-Comparable properties annotated with
 * `@Indexed(sorted = true)`.
 *
 * Tests use kctfork in-process compilation with the full lirp-api/lirp-core classpath. Each test
 * compiles a minimal entity source and asserts on generated file content or compilation error messages.
 */
@OptIn(ExperimentalCompilerApi::class)
internal class IndexedProcessorTest : StringSpec({

    fun compileWithIndexedProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.configureKsp { withCompilation = true }
        compilation.symbolProcessorProviders += IndexedProcessorProvider()
        compilation.symbolProcessorProviders += RawInitializerProcessorProvider()
        return compilation.compile()
    }

    fun JvmCompilationResult.generatedFileContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error("Generated file '$name' not found among: ${sourcesGeneratedBySymbolProcessor.map { it.name }.toList()}")
        return file.readText()
    }

    "IndexedProcessor generates accessor with default sorted=false for plain @Indexed property" {
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "LabelEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    data class LabelEntity(override val id: Int) : ReactiveEntityBase<Int, LabelEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed val label: String = ""
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("LabelEntity_LirpIndexAccessor.kt")
        content shouldContain """IndexEntry("label") { it.label }"""
    }

    "IndexedProcessor emits sorted=true IndexEntry for @Indexed(sorted = true) on Comparable property" {
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "AgeEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    data class AgeEntity(override val id: Int) : ReactiveEntityBase<Int, AgeEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed(sorted = true) val age: Int = 0
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("AgeEntity_LirpIndexAccessor.kt")
        content shouldContain """IndexEntry("age", "age", sorted = true) { it.age }"""
    }

    "IndexedProcessor emits sorted=true IndexEntry honoring custom index name" {
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "RankEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    data class RankEntity(override val id: Int) : ReactiveEntityBase<Int, RankEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed(name = "score", sorted = true) val rank: Int = 0
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("RankEntity_LirpIndexAccessor.kt")
        content shouldContain """IndexEntry("score", "rank", sorted = true) { it.rank }"""
    }

    "IndexedProcessor allows nullable Comparable property with sorted=true" {
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "NickEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    data class NickEntity(override val id: Int) : ReactiveEntityBase<Int, NickEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed(sorted = true) val nick: String? = null
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }

    "IndexedProcessor fails compilation when @Indexed(sorted = true) property is not Comparable" {
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "PayloadEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    data class PayloadEntity(override val id: Int) : ReactiveEntityBase<Int, PayloadEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed(sorted = true) val payload: ByteArray = ByteArray(0)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "payload"
        result.messages shouldContain "Comparable"
    }

    "IndexedProcessor emits internal class declaration for top-level internal entity" {
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "InternalLabelEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    internal data class InternalLabelEntity(override val id: Int) : ReactiveEntityBase<Int, InternalLabelEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed val label: String = ""
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InternalLabelEntity_LirpIndexAccessor.kt")
        content shouldContain "internal class InternalLabelEntity_LirpIndexAccessor"
    }

    "IndexedProcessor emits internal class declaration for second top-level internal entity with multiple @Indexed properties" {
        // Verifies that the internal modifier is propagated when an internal entity has multiple @Indexed properties
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "InternalMultiIndexed.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    internal data class InternalMultiIndexed(override val id: Int) : ReactiveEntityBase<Int, InternalMultiIndexed>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed val code: String = ""
                        @Indexed val rank: Int = 0
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InternalMultiIndexed_LirpIndexAccessor.kt")
        content shouldContain "internal class InternalMultiIndexed_LirpIndexAccessor"
        content shouldContain "code"
        content shouldContain "rank"
    }

    "IndexedProcessor fails compilation for private-nested entity with @Indexed property" {
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "PrivateOuterIndexed.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    private class PrivateOuterIndexed {
                        data class HiddenIndexed(override val id: Int) : ReactiveEntityBase<Int, HiddenIndexed>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            @Indexed val tag: String = ""
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "must be public or internal"
        result.messages shouldContain "Private and protected entities cannot have accessible generated code"
    }

    "IndexedProcessor does not require Comparable for default @Indexed (sorted=false)" {
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "DefaultPayloadEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    data class DefaultPayloadEntity(override val id: Int) : ReactiveEntityBase<Int, DefaultPayloadEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed val payload: ByteArray = ByteArray(0)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }
})
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

    val processors = listOf(IndexedProcessorProvider(), RawInitializerProcessorProvider())

    fun compileWithIndexedProcessor(vararg sources: SourceFile) =
        KspTestSupport.compile(providers = processors, sources = sources.toList())

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

        result.shouldSucceed()
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

        result.shouldSucceed()
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

        result.shouldSucceed()
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

        result.shouldSucceed()
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

        result.shouldFailWith("payload", "Comparable")
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

        result.shouldSucceed()
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

        result.shouldSucceed()
        val content = result.generatedFileContent("InternalMultiIndexed_LirpIndexAccessor.kt")
        content.shouldContainEach(
            "internal class InternalMultiIndexed_LirpIndexAccessor",
            "code",
            "rank"
        )
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

        result.shouldFailWith(
            "must be public or internal",
            "Private and protected entities cannot have accessible generated code"
        )
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

        result.shouldSucceed()
    }

    "IndexedProcessor generates compilable accessor with JVM binary name for nested public @Indexed entity" {
        // Regression test: nested entities were named from simpleName only, producing an
        // unresolvable class name (e.g. Track_LirpIndexAccessor instead of
        // Catalog${'$'}Track_LirpIndexAccessor). The generated file must compile and the
        // accessor class must be loadable by the runtime via its JVM binary name.
        val result =
            compileWithIndexedProcessor(
                SourceFile.kotlin(
                    "CatalogWithTrack.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    class Catalog {
                        data class Track(override val id: Int) : ReactiveEntityBase<Int, Track>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            @Indexed val title: String = ""
                        }
                    }
                    """
                )
            )

        result.shouldSucceed()
        val accessorFileName = "Catalog\$Track_LirpIndexAccessor.kt"
        val content = result.generatedFileContent(accessorFileName)
        content.shouldContainEach(
            // The class declaration must use the backtick-escaped JVM binary name
            "`Catalog\$Track_LirpIndexAccessor`",
            // The type argument must use the Kotlin-source nested name (dot-separated)
            "LirpIndexAccessor<Catalog.Track>",
            // The index entry must reference the property correctly
            """IndexEntry("title") { it.title }"""
        )
    }
})
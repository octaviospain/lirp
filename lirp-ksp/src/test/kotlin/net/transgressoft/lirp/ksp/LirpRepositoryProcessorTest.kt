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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for [LirpRepositoryProcessor], verifying that the processor generates
 * correct `_LirpRegistryInfo` implementations for all repository shapes, including top-level
 * and inner class repositories.
 *
 * Each test compiles a source repository in-process using kctfork and asserts on the generated
 * file content. Test entity classes use [net.transgressoft.lirp.entity.ReactiveEntityBase] as
 * base and test repositories extend [net.transgressoft.lirp.persistence.VolatileRepository].
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("LirpRepositoryProcessor")
internal class LirpRepositoryProcessorTest : FunSpec({

    fun compileWithProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.configureKsp { withCompilation = true }
        compilation.symbolProcessorProviders += LirpRepositoryProcessorProvider()
        return compilation.compile()
    }

    fun JvmCompilationResult.generatedFileContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error("Generated file '$name' not found among: ${sourcesGeneratedBySymbolProcessor.map { it.name }.toList()}")
        return file.readText()
    }

    test("generates _LirpRegistryInfo for top-level repository") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "TrackRepo.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.LirpRepository
                    import net.transgressoft.lirp.persistence.VolatileRepository

                    data class TrackEntity(override val id: Int) : ReactiveEntityBase<Int, TrackEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    @LirpRepository
                    class TrackRepo : VolatileRepository<Int, TrackEntity>()
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("TrackRepo_LirpRegistryInfo.kt")
        content shouldContain "`TrackRepo_LirpRegistryInfo`"
        content shouldContain "LirpRegistryInfo"
        content shouldContain "TrackEntity::class.java"
    }

    test("generates \$-separated info class name for 1-level inner repository") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "Outer.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.LirpRepository
                    import net.transgressoft.lirp.persistence.VolatileRepository

                    data class SomeEntity(override val id: Int) : ReactiveEntityBase<Int, SomeEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    class Outer {
                        @LirpRepository
                        class InnerRepo : VolatileRepository<Int, SomeEntity>()
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("Outer\$InnerRepo_LirpRegistryInfo.kt")
        content shouldContain "`Outer\$InnerRepo_LirpRegistryInfo`"
        content shouldContain "LirpRegistryInfo"
        content shouldContain "SomeEntity::class.java"
    }

    test("generates \$-separated info class name for 3-level nested repository") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "A.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.LirpRepository
                    import net.transgressoft.lirp.persistence.VolatileRepository

                    data class ItemEntity(override val id: Int) : ReactiveEntityBase<Int, ItemEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    class A {
                        class B {
                            @LirpRepository
                            class C : VolatileRepository<Int, ItemEntity>()
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("A\$B\$C_LirpRegistryInfo.kt")
        content shouldContain "`A\$B\$C_LirpRegistryInfo`"
        content shouldContain "LirpRegistryInfo"
        content shouldContain "ItemEntity::class.java"
    }

    test("inner repository info references inner entity with correct simple name") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "Domain.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.LirpRepository
                    import net.transgressoft.lirp.persistence.VolatileRepository

                    class Domain {
                        data class InnerEntity(override val id: Int) : ReactiveEntityBase<Int, InnerEntity>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                        }

                        @LirpRepository
                        class InnerRepo : VolatileRepository<Int, InnerEntity>()
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("Domain\$InnerRepo_LirpRegistryInfo.kt")
        content shouldContain "`Domain\$InnerRepo_LirpRegistryInfo`"
        content shouldContain "InnerEntity::class.java"
    }

    test("delegation-based @LirpRepository compiles successfully and generates no _LirpRegistryInfo") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "DelegatingRepo.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.LirpRepository
                    import net.transgressoft.lirp.persistence.Repository
                    import net.transgressoft.lirp.persistence.VolatileRepository

                    data class SomeEntity(override val id: Int) : ReactiveEntityBase<Int, SomeEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    @LirpRepository
                    class DelegatingRepo(private val delegate: VolatileRepository<Int, SomeEntity>) :
                        Repository<Int, SomeEntity> by delegate
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.sourcesGeneratedBySymbolProcessor
            .filter { it.name.contains("DelegatingRepo") }
            .toList() shouldBe emptyList()
    }

    test("delegation class with zero Repository-typed constructor params warns and generates no _LirpRegistryInfo") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ZeroParamDelegation.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.LirpRepository
                    import net.transgressoft.lirp.persistence.Repository
                    import net.transgressoft.lirp.persistence.VolatileRepository

                    data class SomeEntity(override val id: Int) : ReactiveEntityBase<Int, SomeEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    private val sharedDelegate = VolatileRepository<Int, SomeEntity>("shared")

                    @LirpRepository
                    class ZeroParamDelegation : Repository<Int, SomeEntity> by sharedDelegate
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.sourcesGeneratedBySymbolProcessor
            .filter { it.name.contains("ZeroParamDelegation") }
            .toList() shouldBe emptyList()
        result.messages shouldContain "must have exactly one Repository-typed constructor parameter"
    }

    test("delegation class with multiple Repository-typed constructor params warns and generates no _LirpRegistryInfo") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "MultiParamDelegation.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.LirpRepository
                    import net.transgressoft.lirp.persistence.Repository
                    import net.transgressoft.lirp.persistence.VolatileRepository

                    data class SomeEntity(override val id: Int) : ReactiveEntityBase<Int, SomeEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    @LirpRepository
                    class MultiParamDelegation(
                        private val delegate1: VolatileRepository<Int, SomeEntity>,
                        private val delegate2: VolatileRepository<Int, SomeEntity>
                    ) : Repository<Int, SomeEntity> by delegate1
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.sourcesGeneratedBySymbolProcessor
            .filter { it.name.contains("MultiParamDelegation") }
            .toList() shouldBe emptyList()
        result.messages shouldContain "must have exactly one Repository-typed constructor parameter"
    }

    test("class annotated with @LirpRepository that neither extends base nor delegates gets warn-and-skip") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NotARepo.kt",
                    """
                    package test
                    import net.transgressoft.lirp.persistence.LirpRepository

                    @LirpRepository
                    class NotARepo
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.sourcesGeneratedBySymbolProcessor
            .filter { it.name.contains("NotARepo") }
            .toList() shouldBe emptyList()
        result.messages shouldContain "Cannot determine entity class"
    }
})
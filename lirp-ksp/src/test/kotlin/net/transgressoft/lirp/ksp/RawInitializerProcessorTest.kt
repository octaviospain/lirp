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

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
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

    "generates initializer for entity with reactiveProperty + non-reactive var scalar" {
        // `lastDateModified` is inherited from ReactiveEntityBase as a plain `var LocalDateTime` —
        // it is not a constructor parameter and not delegated, so the processor must emit a
        // raw-init entry for it alongside the reactive `x`.
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
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
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
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
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
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

    "RawInitializerProcessor skips private var properties" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "WithPrivate.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class WithPrivate(override val id: Int) : ReactiveEntityBase<Int, WithPrivate>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var publicName: String by reactiveProperty("")
                        private var secret: Int = 0
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("WithPrivate_LirpRawInitializer.kt")
        content shouldContain "name = \"publicName\""
        content shouldNotContain "name = \"secret\""
        content shouldNotContain "entity.secret"
    }

    "RawInitializerProcessor emits entries for public var properties when private siblings are present" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "MixedVisibility.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class MixedVisibility(override val id: Int) : ReactiveEntityBase<Int, MixedVisibility>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var publicA: String by reactiveProperty("")
                        var publicB: Int by reactiveProperty(0)
                        private var hiddenA: Int = 0
                        private var hiddenB: String = ""
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MixedVisibility_LirpRawInitializer.kt")
        content shouldContain "name = \"publicA\""
        content shouldContain "name = \"publicB\""
        content shouldNotContain "name = \"hiddenA\""
        content shouldNotContain "name = \"hiddenB\""
    }

    "RawInitializerProcessor skips non-ctor var with private set" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "WithPrivateSetter.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class WithPrivateSetter(override val id: Int) : ReactiveEntityBase<Int, WithPrivateSetter>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var publicCounter: Int = 0
                        var lockedNote: String = ""
                            private set
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("WithPrivateSetter_LirpRawInitializer.kt")
        content shouldContain "name = \"publicCounter\""
        content shouldNotContain "name = \"lockedNote\""
        content shouldNotContain "entity.lockedNote"
    }

    "RawInitializerProcessor skips non-ctor var with internal set" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "WithInternalSetter.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class WithInternalSetter(override val id: Int) : ReactiveEntityBase<Int, WithInternalSetter>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var openCounter: Int = 0
                        var crateOnly: String = ""
                            internal set
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("WithInternalSetter_LirpRawInitializer.kt")
        content shouldContain "name = \"openCounter\""
        content shouldNotContain "name = \"crateOnly\""
    }

    "RawInitializerProcessor still excludes @PersistenceIgnore-annotated public var" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "WithIgnore.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceIgnore

                    data class WithIgnore(override val id: Int) : ReactiveEntityBase<Int, WithIgnore>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var kept: String by reactiveProperty("")
                        @PersistenceIgnore var ignored: Int by reactiveProperty(0)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("WithIgnore_LirpRawInitializer.kt")
        content shouldContain "name = \"kept\""
        content shouldNotContain "name = \"ignored\""
    }

    "RawInitializerProcessor still excludes @Transient-annotated public var" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "WithTransient.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class WithTransient(override val id: Int) : ReactiveEntityBase<Int, WithTransient>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var kept: String by reactiveProperty("")
                        @Transient var t: Int = 0
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("WithTransient_LirpRawInitializer.kt")
        content shouldContain "name = \"kept\""
        content shouldNotContain "name = \"t\""
    }

    "RawInitializerProcessor compiles entity with only private var fields" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "OnlyPrivate.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class OnlyPrivate(override val id: Int) : ReactiveEntityBase<Int, OnlyPrivate>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        private var cache: Int = 0
                        private var flag: Boolean = false
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("OnlyPrivate_LirpRawInitializer.kt")
        content shouldContain "class OnlyPrivate_LirpRawInitializer"
        content shouldNotContain "name = \"cache\""
        content shouldNotContain "name = \"flag\""
        content shouldNotContain "entity.cache"
        content shouldNotContain "entity.flag"
    }

    "RawInitializerProcessor emits internal class declaration for top-level internal entity" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "InternalFoo.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    internal data class InternalFoo(override val id: Int) : ReactiveEntityBase<Int, InternalFoo>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var x: Int by reactiveProperty(0)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InternalFoo_LirpRawInitializer.kt")
        content shouldContain "internal class InternalFoo_LirpRawInitializer"
    }

    "RawInitializerProcessor emits internal class declaration for internal entity nested in internal outer" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "InternalOuter.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    internal class InternalOuter {
                        internal data class InnerEntity(override val id: Int) : ReactiveEntityBase<Int, InnerEntity>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            var name: String by reactiveProperty("")
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InternalOuter\$InnerEntity_LirpRawInitializer.kt")
        content shouldContain "internal class"
    }

    "RawInitializerProcessor emits internal class declaration for public entity nested in internal outer" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "InternalOuterPublicInner.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    internal class InternalOuterPublicInner {
                        data class InnerPublic(override val id: Int) : ReactiveEntityBase<Int, InnerPublic>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            var label: String by reactiveProperty("")
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InternalOuterPublicInner\$InnerPublic_LirpRawInitializer.kt")
        content shouldContain "internal class"
    }

    "RawInitializerProcessor silently skips private-nested entity without generating a file" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
                SourceFile.kotlin(
                    "PrivateOuter.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    private class PrivateOuter {
                        data class HiddenEntity(override val id: Int) : ReactiveEntityBase<Int, HiddenEntity>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                            var value: String by reactiveProperty("")
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        // Structural processors (RawInitializerProcessor) silently skip private/protected entities
        val generatedNames = result.generatedNames()
        generatedNames.contains("PrivateOuter\$HiddenEntity_LirpRawInitializer.kt") shouldBe false
    }

    "skips collection-ref properties" {
        val result =
            KspTestSupport.compile(
                RawInitializerProcessorProvider(),
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
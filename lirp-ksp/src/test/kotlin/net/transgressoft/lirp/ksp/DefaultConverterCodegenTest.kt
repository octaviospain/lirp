/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

/**
 * KSP compilation tests for built-in default converter resolution: a column whose declared type
 * matches a built-in default ([net.transgressoft.lirp.persistence.DefaultColumnConverters]) is
 * auto-bound without a `@PersistenceProperty(converter = …)` annotation, and a consumer-supplied
 * converter for the same type overrides the default.
 */
@OptIn(ExperimentalCompilerApi::class)
class DefaultConverterCodegenTest : StringSpec({

    "TableDefProcessor auto-binds the built-in Path converter for an un-annotated Path column" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "PathDefaultEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import java.nio.file.Path

                    @PersistenceMapping
                    data class PathDefaultEntity(
                        override val id: Int,
                        val location: Path
                    ) : ReactiveEntityBase<Int, PathDefaultEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = PathDefaultEntity(id, location)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated = result.generatedFileContent("PathDefaultEntity_LirpTableDef.kt")
        generated shouldContain "net.transgressoft.lirp.persistence.PathColumnConverter.sqlType"
        generated shouldContain "net.transgressoft.lirp.persistence.PathColumnConverter.fromSql("
        generated shouldContain "net.transgressoft.lirp.persistence.PathColumnConverter.toSql(entity.location)"
        generated shouldContain "as kotlin.String"
    }

    "TableDefProcessor auto-binds the built-in Duration converter on a LongType-backed column" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "DurationDefaultEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import java.time.Duration

                    @PersistenceMapping
                    data class DurationDefaultEntity(
                        override val id: Int,
                        val length: Duration
                    ) : ReactiveEntityBase<Int, DurationDefaultEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DurationDefaultEntity(id, length)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated = result.generatedFileContent("DurationDefaultEntity_LirpTableDef.kt")
        generated shouldContain "net.transgressoft.lirp.persistence.DurationColumnConverter.sqlType"
        generated shouldContain "net.transgressoft.lirp.persistence.DurationColumnConverter.fromSql("
        generated shouldContain "net.transgressoft.lirp.persistence.DurationColumnConverter.toSql(entity.length)"
        generated shouldContain "as kotlin.Long"
    }

    "TableDefProcessor auto-binds the built-in URI converter on a nullable column preserving null" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "UriDefaultEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import java.net.URI

                    @PersistenceMapping
                    data class UriDefaultEntity(
                        override val id: Int,
                        val homepage: URI?
                    ) : ReactiveEntityBase<Int, UriDefaultEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = UriDefaultEntity(id, homepage)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated = result.generatedFileContent("UriDefaultEntity_LirpTableDef.kt")
        generated shouldContain "as? kotlin.String"
        generated shouldContain "?.let { net.transgressoft.lirp.persistence.UriColumnConverter.fromSql(it) }"
        generated shouldContain "entity.homepage?.let { net.transgressoft.lirp.persistence.UriColumnConverter.toSql(it) }"
    }

    "TableDefProcessor honors a length hint on a default-converter column refining TextType to VarcharType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "HintedPathEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty
                    import java.nio.file.Path

                    @PersistenceMapping
                    data class HintedPathEntity(
                        override val id: Int,
                        @PersistenceProperty(length = 1024) val location: Path
                    ) : ReactiveEntityBase<Int, HintedPathEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = HintedPathEntity(id, location)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated = result.generatedFileContent("HintedPathEntity_LirpTableDef.kt")
        generated shouldContain "ColumnType.VarcharType(1024)"
        generated shouldContain "net.transgressoft.lirp.persistence.PathColumnConverter.fromSql("
    }

    "TableDefProcessor lets a consumer-supplied converter override the built-in default for the same type" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "OverriddenPathEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty
                    import java.nio.file.Path
                    import java.nio.file.Paths

                    object PlainPathConverter : ColumnConverter<Path, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: Path): String = value.toString()
                        override fun fromSql(raw: String): Path = Paths.get(raw)
                    }

                    @PersistenceMapping
                    data class OverriddenPathEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = PlainPathConverter::class) val location: Path
                    ) : ReactiveEntityBase<Int, OverriddenPathEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = OverriddenPathEntity(id, location)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated = result.generatedFileContent("OverriddenPathEntity_LirpTableDef.kt")
        generated shouldContain "test.PlainPathConverter.fromSql("
        generated shouldContain "test.PlainPathConverter.toSql(entity.location)"
        generated shouldNotContain "PathColumnConverter"
    }

    "TableDefProcessor auto-binds a built-in default converter on an un-annotated leaf inside an @Embeddable" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "EmbeddedDefaultConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import java.nio.file.Path

                    @Embeddable
                    data class Media(val name: String, val location: Path)

                    @PersistenceMapping
                    data class EmbeddedDefaultConverterEntity(
                        override val id: Int,
                        @Embedded val media: Media
                    ) : ReactiveEntityBase<Int, EmbeddedDefaultConverterEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = EmbeddedDefaultConverterEntity(id, media)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated = result.generatedFileContent("EmbeddedDefaultConverterEntity_LirpTableDef.kt")
        generated shouldContain "name = \"media_location\""
        generated shouldContain "net.transgressoft.lirp.persistence.PathColumnConverter.toSql("
        generated shouldContain "net.transgressoft.lirp.persistence.PathColumnConverter.fromSql("
    }
})
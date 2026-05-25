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

/**
 * KSP compilation tests for the converter-resolution machinery in [TableDefProcessor],
 * exercising D-07 (object-only converters), D-08 (supported S allow-list), and the
 * sentinel `ColumnConverter::class` default that means "no converter declared".
 */
@OptIn(ExperimentalCompilerApi::class)
class ConverterCodegenTest : StringSpec({

    fun compileWithProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.configureKsp { withCompilation = true }
        compilation.symbolProcessorProviders += TableDefProcessorProvider()
        return compilation.compile()
    }

    "TableDefProcessor accepts sentinel converter default and emits no converter codegen" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "SentinelEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class SentinelEntity(
                        override val id: Int,
                        @PersistenceProperty val tag: String
                    ) : ReactiveEntityBase<Int, SentinelEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = SentinelEntity(id, tag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated =
            result.sourcesGeneratedBySymbolProcessor
                .firstOrNull { it.name == "SentinelEntity_LirpTableDef.kt" }
                ?.readText()
                ?: error("SentinelEntity_LirpTableDef.kt not generated")
        generated shouldNotContain ".fromSql("
        generated shouldNotContain ".toSql("
    }

    "TableDefProcessor rejects non-object converter with D-07 diagnostic naming the converter" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NonObjectConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    class NotAnObjectConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @PersistenceMapping
                    data class NonObjectConverterEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = NotAnObjectConverter::class) val tag: String
                    ) : ReactiveEntityBase<Int, NonObjectConverterEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NonObjectConverterEntity(id, tag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "test.NotAnObjectConverter"
        result.messages shouldContain "must be a Kotlin"
        result.messages shouldContain "object"
    }

    "TableDefProcessor rejects converter with unsupported S type via D-08 diagnostic" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "BadSEntity.kt",
                    """
                    package test
                    import java.util.Date
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object BadSConverter : ColumnConverter<String, Date> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): Date = Date()
                        override fun fromSql(raw: Date): String = raw.toString()
                    }

                    @PersistenceMapping
                    data class BadSEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = BadSConverter::class) val tag: String
                    ) : ReactiveEntityBase<Int, BadSEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BadSEntity(id, tag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "test.BadSConverter"
        result.messages shouldContain "java.util.Date"
        result.messages shouldContain "not supported"
    }

    "TableDefProcessor emits converter-routed fromRow and toParams for non-null scalar" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "TagEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object UpperCaseConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value.uppercase()
                        override fun fromSql(raw: String): String = raw.lowercase()
                    }

                    @PersistenceMapping
                    data class TagEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = UpperCaseConverter::class) val tag: String
                    ) : ReactiveEntityBase<Int, TagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = TagEntity(id, tag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated =
            result.sourcesGeneratedBySymbolProcessor
                .firstOrNull { it.name == "TagEntity_LirpTableDef.kt" }
                ?.readText()
                ?: error("TagEntity_LirpTableDef.kt not generated")
        generated shouldContain "test.UpperCaseConverter.sqlType"
        generated shouldContain "test.UpperCaseConverter.fromSql("
        generated shouldContain "as kotlin.String"
        generated shouldContain "test.UpperCaseConverter.toSql(entity.tag)"
    }

    "TableDefProcessor emits nullable converter-routed fromRow and toParams" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NullableTagEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object NullableConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @PersistenceMapping
                    data class NullableTagEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = NullableConverter::class) val nickname: String?
                    ) : ReactiveEntityBase<Int, NullableTagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NullableTagEntity(id, nickname)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated =
            result.sourcesGeneratedBySymbolProcessor
                .firstOrNull { it.name == "NullableTagEntity_LirpTableDef.kt" }
                ?.readText()
                ?: error("NullableTagEntity_LirpTableDef.kt not generated")
        generated shouldContain "as? kotlin.String"
        generated shouldContain "?.let { test.NullableConverter.fromSql(it) }"
        generated shouldContain "entity.nickname?.let { test.NullableConverter.toSql(it) }"
    }

    "TableDefProcessor refines TextType converter sqlType to VarcharType when length hint is set" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "VarcharTagEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object PathLikeConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @PersistenceMapping
                    data class VarcharTagEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = PathLikeConverter::class, length = 64) val tag: String
                    ) : ReactiveEntityBase<Int, VarcharTagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = VarcharTagEntity(id, tag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated =
            result.sourcesGeneratedBySymbolProcessor
                .firstOrNull { it.name == "VarcharTagEntity_LirpTableDef.kt" }
                ?.readText()
                ?: error("VarcharTagEntity_LirpTableDef.kt not generated")
        generated shouldContain "ColumnType.VarcharType(64)"
        // The base converter sqlType reference must NOT appear for this column — the refinement
        // supersedes it. Search for "PathLikeConverter.sqlType" specifically because the
        // converter is still referenced in fromSql / toSql calls.
        generated shouldNotContain "PathLikeConverter.sqlType"
    }

    "TableDefProcessor refines numeric converter sqlType to DecimalType when precision and scale hints are set" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "MoneyEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class Money(val cents: Long)

                    object MoneyConverter : ColumnConverter<Money, Long> {
                        override val sqlType = ColumnType.LongType
                        override fun toSql(value: Money): Long = value.cents
                        override fun fromSql(raw: Long): Money = Money(raw)
                    }

                    @PersistenceMapping
                    data class MoneyEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = MoneyConverter::class, precision = 19, scale = 4) val amount: Money
                    ) : ReactiveEntityBase<Int, MoneyEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = MoneyEntity(id, amount)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated =
            result.sourcesGeneratedBySymbolProcessor
                .firstOrNull { it.name == "MoneyEntity_LirpTableDef.kt" }
                ?.readText()
                ?: error("MoneyEntity_LirpTableDef.kt not generated")
        generated shouldContain "ColumnType.DecimalType(19, 4)"
    }

    "TableDefProcessor rejects length hint on BooleanType converter as incompatible" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "BadHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object FlagConverter : ColumnConverter<Boolean, Boolean> {
                        override val sqlType = ColumnType.BooleanType
                        override fun toSql(value: Boolean): Boolean = value
                        override fun fromSql(raw: Boolean): Boolean = raw
                    }

                    @PersistenceMapping
                    data class BadHintEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = FlagConverter::class, length = 32) val flag: Boolean
                    ) : ReactiveEntityBase<Int, BadHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BadHintEntity(id, flag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "incompatible with converter"
        result.messages shouldContain "test.BadHintEntity.flag"
        result.messages shouldContain "test.FlagConverter"
    }

    "TableDefProcessor accepts converter with supported S type kotlin.Short" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ShortEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class MyShortWrapper(val value: Short)

                    object ShortConverter : ColumnConverter<MyShortWrapper, Short> {
                        override val sqlType = ColumnType.IntType
                        override fun toSql(value: MyShortWrapper): Short = value.value
                        override fun fromSql(raw: Short): MyShortWrapper = MyShortWrapper(raw)
                    }

                    @PersistenceMapping
                    data class ShortEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = ShortConverter::class) val wrapped: MyShortWrapper
                    ) : ReactiveEntityBase<Int, ShortEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ShortEntity(id, wrapped)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }
})
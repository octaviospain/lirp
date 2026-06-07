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

/**
 * KSP compilation tests for the converter-resolution machinery in [TableDefProcessor],
 * exercising (object-only converters), (supported S allow-list), and the
 * sentinel `ColumnConverter::class` default that means "no converter declared".
 */
@OptIn(ExperimentalCompilerApi::class)
class ConverterCodegenTest : StringSpec({

    "TableDefProcessor accepts sentinel converter default and emits no converter codegen" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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
        val generated = result.generatedFileContent("SentinelEntity_LirpTableDef.kt")
        generated shouldNotContain ".fromSql("
        generated shouldNotContain ".toSql("
    }

    "TableDefProcessor rejects non-object converter with a diagnostic naming the converter" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

    "TableDefProcessor rejects converter with unsupported S type via a diagnostic" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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
        val generated = result.generatedFileContent("TagEntity_LirpTableDef.kt")
        generated shouldContain "test.UpperCaseConverter.sqlType"
        generated shouldContain "test.UpperCaseConverter.fromSql("
        generated shouldContain "as kotlin.String"
        generated shouldContain "test.UpperCaseConverter.toSql(entity.tag)"
    }

    "TableDefProcessor emits nullable converter-routed fromRow and toParams" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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
        val generated = result.generatedFileContent("NullableTagEntity_LirpTableDef.kt")
        generated shouldContain "as? kotlin.String"
        generated shouldContain "?.let { test.NullableConverter.fromSql(it) }"
        generated shouldContain "entity.nickname?.let { test.NullableConverter.toSql(it) }"
    }

    "TableDefProcessor refines TextType converter sqlType to VarcharType when length hint is set" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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
        val generated = result.generatedFileContent("VarcharTagEntity_LirpTableDef.kt")
        generated shouldContain "ColumnType.VarcharType(64)"
        // The base converter sqlType reference must NOT appear for this column — the refinement
        // supersedes it. Search for "PathLikeConverter.sqlType" specifically because the
        // converter is still referenced in fromSql / toSql calls.
        generated shouldNotContain "PathLikeConverter.sqlType"
    }

    "TableDefProcessor refines numeric converter sqlType to DecimalType when precision and scale hints are set" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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
        val generated = result.generatedFileContent("MoneyEntity_LirpTableDef.kt")
        generated shouldContain "ColumnType.DecimalType(19, 4)"
    }

    "TableDefProcessor rejects length hint on BooleanType converter as incompatible" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

    "TableDefProcessor accepts entity when no converter annotation is declared" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NoSupertypeConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    // Control object that does not implement ColumnConverter.
                    // No converter=... annotation is used in this fixture.
                    object NotAConverter

                    @PersistenceMapping
                    data class NoSupertypeConverterEntity(
                        override val id: Int,
                        val tag: String
                    ) : ReactiveEntityBase<Int, NoSupertypeConverterEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NoSupertypeConverterEntity(id, tag)
                    }
                    """
                )
            )

        // No converter annotation => no converter validation path is triggered.
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }

    "TableDefProcessor accepts converter with supported S type kotlin.Byte" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ByteEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class ByteWrapper(val value: Byte)

                    object ByteConverter : ColumnConverter<ByteWrapper, Byte> {
                        override val sqlType = ColumnType.IntType
                        override fun toSql(value: ByteWrapper): Byte = value.value
                        override fun fromSql(raw: Byte): ByteWrapper = ByteWrapper(raw)
                    }

                    @PersistenceMapping
                    data class ByteEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = ByteConverter::class) val flags: ByteWrapper
                    ) : ReactiveEntityBase<Int, ByteEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ByteEntity(id, flags)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }

    "TableDefProcessor accepts converter with supported S type kotlin.Boolean" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BoolConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class TriState(val value: Boolean?)

                    object TriStateConverter : ColumnConverter<TriState, Boolean> {
                        override val sqlType = ColumnType.BooleanType
                        override fun toSql(value: TriState): Boolean = value.value ?: false
                        override fun fromSql(raw: Boolean): TriState = TriState(raw)
                    }

                    @PersistenceMapping
                    data class BoolConverterEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = TriStateConverter::class) val state: TriState
                    ) : ReactiveEntityBase<Int, BoolConverterEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BoolConverterEntity(id, state)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated = result.generatedFileContent("BoolConverterEntity_LirpTableDef.kt")
        // The converter's sqlType reference (not the literal ColumnType.BooleanType) is used when
        // no length/precision/scale hint is present — the base expression delegates to the converter.
        generated shouldContain "test.TriStateConverter.sqlType"
        generated shouldContain "test.TriStateConverter.fromSql("
        generated shouldContain "test.TriStateConverter.toSql("
    }

    "TableDefProcessor accepts converter with supported S type kotlin.Double" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "DoubleConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class Ratio(val value: Double)

                    object RatioConverter : ColumnConverter<Ratio, Double> {
                        override val sqlType = ColumnType.DoubleType
                        override fun toSql(value: Ratio): Double = value.value
                        override fun fromSql(raw: Double): Ratio = Ratio(raw)
                    }

                    @PersistenceMapping
                    data class DoubleConverterEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = RatioConverter::class) val ratio: Ratio
                    ) : ReactiveEntityBase<Int, DoubleConverterEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DoubleConverterEntity(id, ratio)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }

    "TableDefProcessor accepts converter with supported S type kotlin.Float" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "FloatConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class Score(val value: Float)

                    object ScoreConverter : ColumnConverter<Score, Float> {
                        override val sqlType = ColumnType.FloatType
                        override fun toSql(value: Score): Float = value.value
                        override fun fromSql(raw: Float): Score = Score(raw)
                    }

                    @PersistenceMapping
                    data class FloatConverterEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = ScoreConverter::class) val score: Score
                    ) : ReactiveEntityBase<Int, FloatConverterEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = FloatConverterEntity(id, score)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }

    "TableDefProcessor accepts converter with supported S type java.math.BigDecimal" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BigDecimalConverterEntity.kt",
                    """
                    package test
                    import java.math.BigDecimal
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class Price(val cents: BigDecimal)

                    object PriceConverter : ColumnConverter<Price, BigDecimal> {
                        override val sqlType = ColumnType.DecimalType(19, 4)
                        override fun toSql(value: Price): BigDecimal = value.cents
                        override fun fromSql(raw: BigDecimal): Price = Price(raw)
                    }

                    @PersistenceMapping
                    data class BigDecimalConverterEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = PriceConverter::class) val price: Price
                    ) : ReactiveEntityBase<Int, BigDecimalConverterEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BigDecimalConverterEntity(id, price)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }
})
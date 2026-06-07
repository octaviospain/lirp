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
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * KSP compilation tests for the `@PersistenceProperty(type = "…")` type-hint pathway in
 * [ColumnMetaBuilder.mapToColumnTypeExpression] and [ColumnMetaBuilder.refineConverterSqlType].
 * Covers all branches of the hint vocabulary (TEXT, INT, BIGINT, BOOLEAN, DOUBLE, FLOAT, UUID,
 * DATE, DATETIME, DECIMAL, VARCHAR) plus the two error paths: VARCHAR without length, and an
 * unknown hint string.
 */
@OptIn(ExperimentalCompilerApi::class)
class PersistencePropertyTypeHintTest : StringSpec({

    "TableDefProcessor maps type=TEXT hint to ColumnType.TextType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "TextHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class TextHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "TEXT") val notes: String
                    ) : ReactiveEntityBase<Int, TextHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = TextHintEntity(id, notes)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("TextHintEntity_LirpTableDef.kt") shouldContain "ColumnType.TextType"
    }

    "TableDefProcessor maps type=INT hint to ColumnType.IntType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "IntHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class IntHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "INT") val count: Long
                    ) : ReactiveEntityBase<Int, IntHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = IntHintEntity(id, count)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("IntHintEntity_LirpTableDef.kt") shouldContain "ColumnType.IntType"
    }

    "TableDefProcessor maps type=BIGINT hint to ColumnType.LongType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BigintHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class BigintHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "BIGINT") val counter: Int
                    ) : ReactiveEntityBase<Int, BigintHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BigintHintEntity(id, counter)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("BigintHintEntity_LirpTableDef.kt") shouldContain "ColumnType.LongType"
    }

    "TableDefProcessor maps type=BOOLEAN hint to ColumnType.BooleanType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BoolHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class BoolHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "BOOLEAN") val active: Int
                    ) : ReactiveEntityBase<Int, BoolHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BoolHintEntity(id, active)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("BoolHintEntity_LirpTableDef.kt") shouldContain "ColumnType.BooleanType"
    }

    "TableDefProcessor maps type=DOUBLE hint to ColumnType.DoubleType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "DoubleHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class DoubleHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "DOUBLE") val score: String
                    ) : ReactiveEntityBase<Int, DoubleHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DoubleHintEntity(id, score)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("DoubleHintEntity_LirpTableDef.kt") shouldContain "ColumnType.DoubleType"
    }

    "TableDefProcessor maps type=FLOAT hint to ColumnType.FloatType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "FloatHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class FloatHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "FLOAT") val ratio: String
                    ) : ReactiveEntityBase<Int, FloatHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = FloatHintEntity(id, ratio)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("FloatHintEntity_LirpTableDef.kt") shouldContain "ColumnType.FloatType"
    }

    "TableDefProcessor maps type=UUID hint to ColumnType.UuidType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "UuidHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class UuidHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "UUID") val externalRef: String
                    ) : ReactiveEntityBase<Int, UuidHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = UuidHintEntity(id, externalRef)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("UuidHintEntity_LirpTableDef.kt") shouldContain "ColumnType.UuidType"
    }

    "TableDefProcessor maps type=DATE hint to ColumnType.DateType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "DateHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class DateHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "DATE") val createdOn: String
                    ) : ReactiveEntityBase<Int, DateHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DateHintEntity(id, createdOn)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("DateHintEntity_LirpTableDef.kt") shouldContain "ColumnType.DateType"
    }

    "TableDefProcessor maps type=DATETIME hint to ColumnType.DateTimeType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "DatetimeHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class DatetimeHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "DATETIME") val updatedAt: String
                    ) : ReactiveEntityBase<Int, DatetimeHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DatetimeHintEntity(id, updatedAt)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("DatetimeHintEntity_LirpTableDef.kt") shouldContain "ColumnType.DateTimeType"
    }

    "TableDefProcessor maps type=DECIMAL hint to ColumnType.DecimalType with default precision and scale" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "DecimalDefaultHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class DecimalDefaultHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "DECIMAL") val amount: String
                    ) : ReactiveEntityBase<Int, DecimalDefaultHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DecimalDefaultHintEntity(id, amount)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        // Default precision=19, scale=2 when not specified
        result.generatedFileContent("DecimalDefaultHintEntity_LirpTableDef.kt") shouldContain "ColumnType.DecimalType(19, 2)"
    }

    "TableDefProcessor maps type=DECIMAL hint with explicit precision and scale" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "DecimalPrecisionHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class DecimalPrecisionHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "DECIMAL", precision = 10, scale = 3) val rate: String
                    ) : ReactiveEntityBase<Int, DecimalPrecisionHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DecimalPrecisionHintEntity(id, rate)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("DecimalPrecisionHintEntity_LirpTableDef.kt") shouldContain "ColumnType.DecimalType(10, 3)"
    }

    "TableDefProcessor maps type=VARCHAR with length to ColumnType.VarcharType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "VarcharHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class VarcharHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "VARCHAR", length = 128) val code: String
                    ) : ReactiveEntityBase<Int, VarcharHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = VarcharHintEntity(id, code)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileContent("VarcharHintEntity_LirpTableDef.kt") shouldContain "ColumnType.VarcharType(128)"
    }

    "TableDefProcessor rejects type=VARCHAR without length" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "VarcharNoLengthEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class VarcharNoLengthEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "VARCHAR") val code: String
                    ) : ReactiveEntityBase<Int, VarcharNoLengthEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = VarcharNoLengthEntity(id, code)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "requires length > 0"
        result.messages shouldContain "code"
    }

    "TableDefProcessor rejects unknown type hint" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "UnknownHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class UnknownHintEntity(
                        override val id: Int,
                        @PersistenceProperty(type = "CLOB") val data: String
                    ) : ReactiveEntityBase<Int, UnknownHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = UnknownHintEntity(id, data)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "Unknown @PersistenceProperty type hint"
        result.messages shouldContain "CLOB"
    }

    "TableDefProcessor rejects precision/scale hint on String-based converter" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "StringConverterPrecisionEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @PersistenceMapping
                    data class StringConverterPrecisionEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = TagConverter::class, precision = 10) val tag: String
                    ) : ReactiveEntityBase<Int, StringConverterPrecisionEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = StringConverterPrecisionEntity(id, tag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "incompatible with converter"
        result.messages shouldContain "precision/scale"
    }

    "TableDefProcessor rejects length hint on numeric-based converter" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "LongConverterLengthEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class Cents(val value: Long)

                    object CentsConverter : ColumnConverter<Cents, Long> {
                        override val sqlType = ColumnType.LongType
                        override fun toSql(value: Cents): Long = value.value
                        override fun fromSql(raw: Long): Cents = Cents(raw)
                    }

                    @PersistenceMapping
                    data class LongConverterLengthEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = CentsConverter::class, length = 50) val amount: Cents
                    ) : ReactiveEntityBase<Int, LongConverterLengthEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = LongConverterLengthEntity(id, amount)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "incompatible with converter"
        result.messages shouldContain "length"
    }

    "TableDefProcessor accepts type hint on converter column overriding the converter sqlType" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ConverterWithTypeHintEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object CodeConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value.uppercase()
                        override fun fromSql(raw: String): String = raw.lowercase()
                    }

                    @PersistenceMapping
                    data class ConverterWithTypeHintEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = CodeConverter::class, type = "VARCHAR", length = 10) val code: String
                    ) : ReactiveEntityBase<Int, ConverterWithTypeHintEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ConverterWithTypeHintEntity(id, code)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        // type hint overrides converter's sqlType expression
        val content = result.generatedFileContent("ConverterWithTypeHintEntity_LirpTableDef.kt")
        content shouldContain "ColumnType.VarcharType(10)"
        content shouldContain "test.CodeConverter.fromSql("
        content shouldContain "test.CodeConverter.toSql("
    }
})
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
 * KSP compilation tests for the FQN-driven column-type inference in
 * [ColumnMetaBuilder.mapToColumnTypeExpression]. Exercises all branches of the `when` table that
 * are not covered by the main [TableDefProcessorTest] suite: Short/Byte (both map to IntType with
 * narrowing on read), Float, Double, BigDecimal (with and without precision/scale hints), and
 * the unsupported-type error path.
 *
 * Also covers the Short/Byte widening and UUID/LocalDate/LocalDateTime conversion paths in
 * [TableDefSourceEmitter.builtInEntityAccess] and [TableDefSourceEmitter.buildNarrowingIntRowAccess].
 */
@OptIn(ExperimentalCompilerApi::class)
class ColumnTypeInferenceTest : StringSpec({

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

    fun JvmCompilationResult.generatedContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error(
                    "Generated file '$name' not found among: " +
                        sourcesGeneratedBySymbolProcessor.map { it.name }.toList()
                )
        return file.readText()
    }

    "TableDefProcessor maps Short property to IntType with narrowing on read and widening on write" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ShortPropEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class ShortPropEntity(
                        override val id: Int,
                        val priority: Short
                    ) : ReactiveEntityBase<Int, ShortPropEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ShortPropEntity(id, priority)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedContent("ShortPropEntity_LirpTableDef.kt")
        content shouldContain "ColumnType.IntType"
        // Narrowing on read: (row[...] as Int).toShort()
        content shouldContain ".toShort()"
        // Widening on write: entity.priority.toInt()
        content shouldContain ".toInt()"
    }

    "TableDefProcessor maps Byte property to IntType with narrowing on read and widening on write" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "BytePropEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class BytePropEntity(
                        override val id: Int,
                        val flags: Byte
                    ) : ReactiveEntityBase<Int, BytePropEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BytePropEntity(id, flags)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedContent("BytePropEntity_LirpTableDef.kt")
        content shouldContain "ColumnType.IntType"
        content shouldContain ".toByte()"
        content shouldContain ".toInt()"
    }

    "TableDefProcessor maps nullable Short property with narrowing and safe-cast" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NullableShortEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class NullableShortEntity(
                        override val id: Int,
                        val rank: Short?
                    ) : ReactiveEntityBase<Int, NullableShortEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NullableShortEntity(id, rank)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedContent("NullableShortEntity_LirpTableDef.kt")
        content shouldContain "ColumnType.IntType"
        // Nullable narrowing: (rawAccess as? Number)?.toShort()
        content shouldContain "as? Number"
        content shouldContain ".toShort()"
        // Nullable widening: entity.rank?.toInt()
        content shouldContain "?.toInt()"
    }

    "TableDefProcessor maps Float property to FloatType" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "FloatPropEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class FloatPropEntity(
                        override val id: Int,
                        val ratio: Float
                    ) : ReactiveEntityBase<Int, FloatPropEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = FloatPropEntity(id, ratio)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedContent("FloatPropEntity_LirpTableDef.kt") shouldContain "ColumnType.FloatType"
    }

    "TableDefProcessor maps Double property to DoubleType" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "DoublePropEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class DoublePropEntity(
                        override val id: Int,
                        val score: Double
                    ) : ReactiveEntityBase<Int, DoublePropEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DoublePropEntity(id, score)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedContent("DoublePropEntity_LirpTableDef.kt") shouldContain "ColumnType.DoubleType"
    }

    "TableDefProcessor maps BigDecimal property to DecimalType with default precision and scale" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "BigDecimalDefaultEntity.kt",
                    """
                    package test
                    import java.math.BigDecimal
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class BigDecimalDefaultEntity(
                        override val id: Int,
                        val amount: BigDecimal
                    ) : ReactiveEntityBase<Int, BigDecimalDefaultEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BigDecimalDefaultEntity(id, amount)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        // Default: precision=19, scale=2
        result.generatedContent("BigDecimalDefaultEntity_LirpTableDef.kt") shouldContain "ColumnType.DecimalType(19, 2)"
    }

    "TableDefProcessor maps BigDecimal property with explicit length hint to DecimalType" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "BigDecimalPrecisionEntity.kt",
                    """
                    package test
                    import java.math.BigDecimal
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class BigDecimalPrecisionEntity(
                        override val id: Int,
                        @PersistenceProperty(precision = 12, scale = 4) val price: BigDecimal
                    ) : ReactiveEntityBase<Int, BigDecimalPrecisionEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BigDecimalPrecisionEntity(id, price)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedContent("BigDecimalPrecisionEntity_LirpTableDef.kt") shouldContain "ColumnType.DecimalType(12, 4)"
    }

    "TableDefProcessor rejects unsupported column type with error diagnostic" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "UnsupportedTypeEntity.kt",
                    """
                    package test
                    import java.util.LinkedList
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class UnsupportedTypeEntity(
                        override val id: Int,
                        val tags: LinkedList<String>
                    ) : ReactiveEntityBase<Int, UnsupportedTypeEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = UnsupportedTypeEntity(id, tags)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "Unsupported column type"
        result.messages shouldContain "tags"
    }

    "TableDefProcessor maps String property with length annotation to VarcharType" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "VarcharStringEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class VarcharStringEntity(
                        override val id: Int,
                        @PersistenceProperty(length = 255) val name: String
                    ) : ReactiveEntityBase<Int, VarcharStringEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = VarcharStringEntity(id, name)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedContent("VarcharStringEntity_LirpTableDef.kt") shouldContain "ColumnType.VarcharType(255)"
    }

    "TableDefProcessor emits Short/Byte widening for converter with Short S type" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ShortConverterWideningEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class Level(val value: Short)

                    object LevelConverter : ColumnConverter<Level, Short> {
                        override val sqlType = ColumnType.IntType
                        override fun toSql(value: Level): Short = value.value
                        override fun fromSql(raw: Short): Level = Level(raw)
                    }

                    @PersistenceMapping
                    data class ShortConverterWideningEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = LevelConverter::class) val level: Level
                    ) : ReactiveEntityBase<Int, ShortConverterWideningEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ShortConverterWideningEntity(id, level)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedContent("ShortConverterWideningEntity_LirpTableDef.kt")
        // Converter read-side: (row[...] as Int).toShort() routed into fromSql
        content shouldContain "test.LevelConverter.fromSql("
        content shouldContain ".toShort()"
        // Converter write-side: toSql(entity.level).toInt()
        content shouldContain "test.LevelConverter.toSql("
        content shouldContain ".toInt()"
    }

    "TableDefProcessor emits Byte widening for converter with Byte S type on nullable column" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ByteConverterNullableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    data class Flag(val raw: Byte)

                    object FlagConverter : ColumnConverter<Flag, Byte> {
                        override val sqlType = ColumnType.IntType
                        override fun toSql(value: Flag): Byte = value.raw
                        override fun fromSql(raw: Byte): Flag = Flag(raw)
                    }

                    @PersistenceMapping
                    data class ByteConverterNullableEntity(
                        override val id: Int,
                        @PersistenceProperty(converter = FlagConverter::class) val flag: Flag?
                    ) : ReactiveEntityBase<Int, ByteConverterNullableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ByteConverterNullableEntity(id, flag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedContent("ByteConverterNullableEntity_LirpTableDef.kt")
        // Nullable converter read-side: (raw as? Int)?.toByte() routed into fromSql via ?.let
        content shouldContain ".toByte()"
        content shouldContain "?.let { test.FlagConverter.fromSql(it) }"
        // Nullable write-side: entity.flag?.let { ... }?.toInt()
        content shouldContain "?.toInt()"
    }
})
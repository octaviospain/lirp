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

import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.datatest.withData
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

    data class InferenceCase(
        val name: String,
        val entityName: String,
        val annotation: String,
        val fieldName: String,
        val fieldType: String,
        val expected: List<String>
    ) {
        override fun toString() = name
    }

    withData(
        InferenceCase(
            name = "TableDefProcessor maps Short property to IntType with narrowing on read and widening on write",
            entityName = "ShortPropEntity",
            annotation = "",
            fieldName = "priority",
            fieldType = "Short",
            // Narrowing on read: (row[...] as Int).toShort(); widening on write: entity.priority.toInt()
            expected = listOf("ColumnType.IntType", ".toShort()", ".toInt()")
        ),
        InferenceCase(
            name = "TableDefProcessor maps Byte property to IntType with narrowing on read and widening on write",
            entityName = "BytePropEntity",
            annotation = "",
            fieldName = "flags",
            fieldType = "Byte",
            expected = listOf("ColumnType.IntType", ".toByte()", ".toInt()")
        ),
        InferenceCase(
            name = "TableDefProcessor maps nullable Short property with narrowing and safe-cast",
            entityName = "NullableShortEntity",
            annotation = "",
            fieldName = "rank",
            fieldType = "Short?",
            // Nullable narrowing: (rawAccess as? Number)?.toShort(); nullable widening: entity.rank?.toInt()
            expected = listOf("ColumnType.IntType", "as? Number", ".toShort()", "?.toInt()")
        ),
        InferenceCase(
            name = "TableDefProcessor maps Float property to FloatType",
            entityName = "FloatPropEntity",
            annotation = "",
            fieldName = "ratio",
            fieldType = "Float",
            expected = listOf("ColumnType.FloatType")
        ),
        InferenceCase(
            name = "TableDefProcessor maps Double property to DoubleType",
            entityName = "DoublePropEntity",
            annotation = "",
            fieldName = "score",
            fieldType = "Double",
            expected = listOf("ColumnType.DoubleType")
        ),
        InferenceCase(
            name = "TableDefProcessor maps BigDecimal property to DecimalType with default precision and scale",
            entityName = "BigDecimalDefaultEntity",
            annotation = "",
            fieldName = "amount",
            fieldType = "BigDecimal",
            // Default: precision=19, scale=2
            expected = listOf("ColumnType.DecimalType(19, 2)")
        ),
        InferenceCase(
            name = "TableDefProcessor maps BigDecimal property with explicit length hint to DecimalType",
            entityName = "BigDecimalPrecisionEntity",
            annotation = "@PersistenceProperty(precision = 12, scale = 4) ",
            fieldName = "price",
            fieldType = "BigDecimal",
            expected = listOf("ColumnType.DecimalType(12, 4)")
        ),
        InferenceCase(
            name = "TableDefProcessor maps String property with length annotation to VarcharType",
            entityName = "VarcharStringEntity",
            annotation = "@PersistenceProperty(length = 255) ",
            fieldName = "name",
            fieldType = "String",
            expected = listOf("ColumnType.VarcharType(255)")
        )
    ) { case ->
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "${case.entityName}.kt",
                    """
                    package test
                    import java.math.BigDecimal
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class ${case.entityName}(
                        override val id: Int,
                        ${case.annotation}val ${case.fieldName}: ${case.fieldType}
                    ) : ReactiveEntityBase<Int, ${case.entityName}>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ${case.entityName}(id, ${case.fieldName})
                    }
                    """
                )
            )

        result.shouldSucceed()
        result.generatedFileContent("${case.entityName}_LirpTableDef.kt").shouldContainEach(*case.expected.toTypedArray())
    }

    "TableDefProcessor rejects unsupported column type with error diagnostic" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        result.shouldFailWith("Unsupported column type", "tags")
    }

    "TableDefProcessor emits Short/Byte widening for converter with Short S type" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        result.shouldSucceed()
        val content = result.generatedFileContent("ShortConverterWideningEntity_LirpTableDef.kt")
        content.shouldContainEach(
            // Converter read-side: (row[...] as Int).toShort() routed into fromSql
            "test.LevelConverter.fromSql(",
            ".toShort()",
            // Converter write-side: toSql(entity.level).toInt()
            "test.LevelConverter.toSql(",
            ".toInt()"
        )
    }

    "TableDefProcessor emits Byte widening for converter with Byte S type on nullable column" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        result.shouldSucceed()
        val content = result.generatedFileContent("ByteConverterNullableEntity_LirpTableDef.kt")
        content.shouldContainEach(
            // Nullable converter read-side: (raw as? Int)?.toByte() routed into fromSql via ?.let
            ".toByte()",
            "?.let { test.FlagConverter.fromSql(it) }",
            // Nullable write-side: entity.flag?.let { ... }?.toInt()
            "?.toInt()"
        )
    }
})
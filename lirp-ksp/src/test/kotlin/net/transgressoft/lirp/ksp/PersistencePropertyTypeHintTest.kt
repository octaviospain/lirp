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

    data class TypeHintCase(
        val name: String,
        val entityName: String,
        val annotationArgs: String,
        val fieldName: String,
        val fieldType: String,
        val expectedColumnType: String
    ) {
        override fun toString() = name
    }

    withData(
        TypeHintCase(
            "TableDefProcessor maps type=TEXT hint to ColumnType.TextType",
            "TextHintEntity", """type = "TEXT"""", "notes", "String", "ColumnType.TextType"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=INT hint to ColumnType.IntType",
            "IntHintEntity", """type = "INT"""", "count", "Long", "ColumnType.IntType"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=BIGINT hint to ColumnType.LongType",
            "BigintHintEntity", """type = "BIGINT"""", "counter", "Int", "ColumnType.LongType"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=BOOLEAN hint to ColumnType.BooleanType",
            "BoolHintEntity", """type = "BOOLEAN"""", "active", "Int", "ColumnType.BooleanType"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=DOUBLE hint to ColumnType.DoubleType",
            "DoubleHintEntity", """type = "DOUBLE"""", "score", "String", "ColumnType.DoubleType"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=FLOAT hint to ColumnType.FloatType",
            "FloatHintEntity", """type = "FLOAT"""", "ratio", "String", "ColumnType.FloatType"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=UUID hint to ColumnType.UuidType",
            "UuidHintEntity", """type = "UUID"""", "externalRef", "String", "ColumnType.UuidType"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=DATE hint to ColumnType.DateType",
            "DateHintEntity", """type = "DATE"""", "createdOn", "String", "ColumnType.DateType"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=DATETIME hint to ColumnType.DateTimeType",
            "DatetimeHintEntity", """type = "DATETIME"""", "updatedAt", "String", "ColumnType.DateTimeType"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=DECIMAL hint to ColumnType.DecimalType with default precision and scale",
            "DecimalDefaultHintEntity", """type = "DECIMAL"""", "amount", "String", "ColumnType.DecimalType(19, 2)"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=DECIMAL hint with explicit precision and scale",
            "DecimalPrecisionHintEntity", """type = "DECIMAL", precision = 10, scale = 3""", "rate", "String", "ColumnType.DecimalType(10, 3)"
        ),
        TypeHintCase(
            "TableDefProcessor maps type=VARCHAR with length to ColumnType.VarcharType",
            "VarcharHintEntity", """type = "VARCHAR", length = 128""", "code", "String", "ColumnType.VarcharType(128)"
        )
    ) { case ->
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "${case.entityName}.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @PersistenceMapping
                    data class ${case.entityName}(
                        override val id: Int,
                        @PersistenceProperty(${case.annotationArgs}) val ${case.fieldName}: ${case.fieldType}
                    ) : ReactiveEntityBase<Int, ${case.entityName}>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ${case.entityName}(id, ${case.fieldName})
                    }
                    """
                )
            )

        result.shouldSucceed()
        result.generatedFileContent("${case.entityName}_LirpTableDef.kt") shouldContain case.expectedColumnType
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

        result.shouldFailWith("requires length > 0", "code")
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

        result.shouldFailWith("Unknown @PersistenceProperty type hint", "CLOB")
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

        result.shouldFailWith("incompatible with converter", "precision/scale")
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

        result.shouldFailWith("incompatible with converter", "length")
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

        result.shouldSucceed()
        // type hint overrides converter's sqlType expression
        val content = result.generatedFileContent("ConverterWithTypeHintEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "ColumnType.VarcharType(10)",
            "test.CodeConverter.fromSql(",
            "test.CodeConverter.toSql("
        )
    }
})
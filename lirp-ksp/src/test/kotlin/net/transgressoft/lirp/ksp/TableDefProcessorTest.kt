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
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for [TableDefProcessor], verifying that the processor generates correct
 * `_LirpTableDef` descriptor objects for all supported entity shapes.
 *
 * Each test compiles a source entity in-process using kctfork and asserts the content of the generated file.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("TableDefProcessor")
internal class TableDefProcessorTest : FunSpec({

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

    fun JvmCompilationResult.generatedFileContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error("Generated file '$name' not found among: ${sourcesGeneratedBySymbolProcessor.map { it.name }.toList()}")
        return file.readText()
    }

    test("TableDefProcessor generates _LirpTableDef for minimal entity with convention defaults") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "MinimalEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                data class MinimalEntity(override val id: Int) : ReactiveEntityBase<Int, MinimalEntity>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = MinimalEntity(id)
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MinimalEntity_LirpTableDef.kt")
        content shouldContain "tableName: String = \"minimal_entity\""
        content shouldContain "ColumnType.IntType"
        content shouldContain "primaryKey = true"
        content shouldContain "object MinimalEntity_LirpTableDef : LirpTableDef<MinimalEntity>"
    }

    test("TableDefProcessor generates _LirpTableDef with annotation overrides for table name and column config") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ProductEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.PersistenceProperty

                @PersistenceMapping(name = "products")
                data class ProductEntity(
                    override val id: Long,
                    @PersistenceProperty(name = "full_name", length = 100) val name: String,
                    val description: String
                ) : ReactiveEntityBase<Long, ProductEntity>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = ProductEntity(id, name, description)
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ProductEntity_LirpTableDef.kt")
        content shouldContain "tableName: String = \"products\""
        content shouldContain "name = \"full_name\""
        content shouldContain "ColumnType.VarcharType(100)"
        content shouldContain "name = \"description\""
        content shouldContain "ColumnType.TextType"
        content shouldContain "ColumnType.LongType"
    }

    test("TableDefProcessor maps reactiveProperty delegate to declared type") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ReactiveEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                data class ReactiveEntity(
                    override val id: Int,
                    val label: String
                ) : ReactiveEntityBase<Int, ReactiveEntity>() {
                    var mutableLabel: String by reactiveProperty(label)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = ReactiveEntity(id, label)
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ReactiveEntity_LirpTableDef.kt")
        content shouldContain "name = \"mutable_label\""
        content shouldContain "ColumnType.TextType"
    }

    test("TableDefProcessor excludes @PersistenceIgnore properties from generated descriptor") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "EntityWithIgnored.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.PersistenceIgnore

                @PersistenceMapping
                data class EntityWithIgnored(
                    override val id: Int,
                    val name: String,
                    @PersistenceIgnore val transientData: String
                ) : ReactiveEntityBase<Int, EntityWithIgnored>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = EntityWithIgnored(id, name, transientData)
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("EntityWithIgnored_LirpTableDef.kt")
        content shouldContain "name = \"name\""
        content shouldNotContain "transient_data"
        content shouldNotContain "transientData"
    }

    test("TableDefProcessor triggers on @PersistenceProperty without class-level @PersistenceMapping") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ImplicitEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceProperty

                data class ImplicitEntity(
                    override val id: Int,
                    @PersistenceProperty(name = "label") val name: String
                ) : ReactiveEntityBase<Int, ImplicitEntity>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = ImplicitEntity(id, name)
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ImplicitEntity_LirpTableDef.kt")
        content shouldContain "tableName: String = \"implicit_entity\""
        content shouldContain "name = \"label\""
    }

    test("TableDefProcessor reports KSP error for unsupported property type") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "BadEntity.kt",
                    """
                package test
                import java.io.File
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                data class BadEntity(
                    override val id: Int,
                    val file: File
                ) : ReactiveEntityBase<Int, BadEntity>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = BadEntity(id, file)
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "Unsupported column type"
    }
})
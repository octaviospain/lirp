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

    test("generates _LirpTableDef for minimal entity with convention defaults") {
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
        content shouldContain "object MinimalEntity_LirpTableDef : SqlTableDef<MinimalEntity>"
    }

    test("generates _LirpTableDef with annotation overrides for table name and column config") {
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

    test("maps reactiveProperty delegate to declared type") {
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

    test("excludes @PersistenceIgnore properties from generated descriptor") {
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

    test("triggers on @PersistenceProperty without class-level @PersistenceMapping") {
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

    test("generates SqlTableDef implementation for entity with all-mutable non-PK properties") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "MutableEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class MutableEntity(val id: Int) {
                    var name: String = ""
                    var score: Int = 0
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MutableEntity_LirpTableDef.kt")
        content shouldContain "object MutableEntity_LirpTableDef : SqlTableDef<MutableEntity>"
        content shouldContain "import net.transgressoft.lirp.persistence.sql.SqlTableDef"
        content shouldContain "import org.jetbrains.exposed.v1.core.ResultRow"
        content shouldContain "import org.jetbrains.exposed.v1.core.Table"
    }

    test("generates fromRow that constructs entity with id and sets mutable properties") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "EntityWithFromRow.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class EntityWithFromRow(val id: Int) {
                    var name: String = ""
                    var active: Boolean = false
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("EntityWithFromRow_LirpTableDef.kt")
        content shouldContain "override fun fromRow(row: ResultRow, table: Table): EntityWithFromRow"
        content shouldContain "val entity = EntityWithFromRow("
        content shouldContain "entity.name ="
        content shouldContain "entity.active ="
        content shouldContain "return entity"
    }

    test("generates toParams that returns all column-value pairs including PK") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "EntityWithToParams.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class EntityWithToParams(val id: Long) {
                    var description: String = ""
                    var count: Int = 0
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("EntityWithToParams_LirpTableDef.kt")
        content shouldContain "override fun toParams(entity: EntityWithToParams, table: Table): Map<Column<*>, Any?>"
        content shouldContain "cols[\"id\"]!! to entity.id"
        content shouldContain "cols[\"description\"]!! to entity.description"
        content shouldContain "cols[\"count\"]!! to entity.count"
    }

    test("generates descriptor-only LirpTableDef when entity has immutable non-PK properties") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ImmutableEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class ImmutableEntity(val id: Int, val name: String)
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ImmutableEntity_LirpTableDef.kt")
        content shouldContain "object ImmutableEntity_LirpTableDef : LirpTableDef<ImmutableEntity>"
        content shouldNotContain "SqlTableDef"
        content shouldNotContain "fromRow"
        content shouldNotContain "toParams"
    }

    test("generates correct Exposed v1 imports in SqlTableDef generated code") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ImportCheckEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class ImportCheckEntity(val id: Int) {
                    var label: String = ""
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ImportCheckEntity_LirpTableDef.kt")
        content shouldContain "import net.transgressoft.lirp.persistence.sql.SqlTableDef"
        content shouldContain "import org.jetbrains.exposed.v1.core.Column"
        content shouldContain "import org.jetbrains.exposed.v1.core.ResultRow"
        content shouldContain "import org.jetbrains.exposed.v1.core.Table"
    }

    test("generates correct enum handling as String in fromRow and toParams") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "EntityWithEnum.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                enum class Status { ACTIVE, INACTIVE }

                @PersistenceMapping
                class EntityWithEnum(val id: Int) {
                    var status: Status = Status.ACTIVE
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("EntityWithEnum_LirpTableDef.kt")
        content shouldContain "ColumnType.EnumType"
        content shouldContain "enumValueOf<Status>"
        content shouldContain "entity.status.name"
    }

    test("reports KSP error for unsupported property type") {
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

    test("generates UUID primary key column for entity with UUID id") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "UuidKeyEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import java.util.UUID

                @PersistenceMapping
                data class UuidKeyEntity(override val id: UUID) : ReactiveEntityBase<UUID, UuidKeyEntity>() {
                    var label: String by reactiveProperty("")
                    override val uniqueId: String get() = id.toString()
                    override fun clone() = UuidKeyEntity(id).also { it.label = label }
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("UuidKeyEntity_LirpTableDef.kt")
        content shouldContain "ColumnType.UuidType"
        content shouldContain "primaryKey = true"
        content shouldContain "tableName: String = \"uuid_key_entity\""
        content shouldContain "SqlTableDef<UuidKeyEntity>"
        content shouldContain "toJavaUuid()"
    }

    test("generates nullable columns for entity with all nullable non-PK properties") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NullableEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class NullableEntity(val id: Int) {
                    var name: String? = null
                    var score: Int? = null
                    var active: Boolean? = null
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("NullableEntity_LirpTableDef.kt")
        content shouldContain "name = \"name\""
        content shouldContain "nullable = true"
        content shouldContain "name = \"score\""
        content shouldContain "name = \"active\""
    }

    test("generates descriptor-only LirpTableDef for entity with mixed val/var non-PK properties") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "MixedEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class MixedEntity(val id: Int, val readOnly: String) {
                    var mutable: String = ""
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MixedEntity_LirpTableDef.kt")
        content shouldContain "object MixedEntity_LirpTableDef : LirpTableDef<MixedEntity>"
        content shouldNotContain "SqlTableDef"
        content shouldNotContain "fromRow"
        content shouldNotContain "toParams"
        content shouldContain "name = \"read_only\""
        content shouldContain "name = \"mutable\""
    }

    test("generates correct descriptor for UUID PK entity with @PersistenceIgnore field") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "UuidIgnoreEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.PersistenceIgnore
                import java.util.UUID

                @PersistenceMapping
                data class UuidIgnoreEntity(override val id: UUID) : ReactiveEntityBase<UUID, UuidIgnoreEntity>() {
                    var name: String by reactiveProperty("")
                    @PersistenceIgnore var transientField: String = "ignored"
                    override val uniqueId: String get() = id.toString()
                    override fun clone() = UuidIgnoreEntity(id).also { it.name = name }
                }
                """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("UuidIgnoreEntity_LirpTableDef.kt")
        content shouldContain "ColumnType.UuidType"
        content shouldContain "primaryKey = true"
        content shouldContain "name = \"name\""
        content shouldNotContain "transient_field"
        content shouldNotContain "transientField"
    }
})
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

import com.tschuchort.compiletesting.SourceFile
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

    test("generates _LirpTableDef for minimal entity with convention defaults") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("MinimalEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "tableName: String = \"minimal_entity\"",
            "ColumnType.IntType",
            "primaryKey = true",
            "object MinimalEntity_LirpTableDef : SqlTableDef<MinimalEntity>"
        )
    }

    test("generates _LirpTableDef with annotation overrides for table name and column config") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("ProductEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "tableName: String = \"products\"",
            "name = \"full_name\"",
            "ColumnType.VarcharType(100)",
            "name = \"description\"",
            "ColumnType.TextType",
            "ColumnType.LongType"
        )
    }

    test("maps reactiveProperty delegate to declared type") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("ReactiveEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "name = \"mutable_label\"",
            "ColumnType.TextType"
        )
    }

    test("excludes @PersistenceIgnore properties from generated descriptor") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("EntityWithIgnored_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present = listOf("name = \"name\""),
            absent = listOf("transient_data", "transientData")
        )
    }

    test("triggers on @PersistenceProperty without class-level @PersistenceMapping") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("ImplicitEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "tableName: String = \"implicit_entity\"",
            "name = \"label\""
        )
    }

    test("generates SqlTableDef implementation for entity with all-mutable non-PK properties") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("MutableEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "object MutableEntity_LirpTableDef : SqlTableDef<MutableEntity>",
            "import net.transgressoft.lirp.persistence.sql.SqlTableDef",
            "import org.jetbrains.exposed.v1.core.ResultRow",
            "import org.jetbrains.exposed.v1.core.Table"
        )
    }

    test("generates fromRow that constructs entity with id and sets mutable properties") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("EntityWithFromRow_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present =
                listOf(
                    "override fun fromRow(row: ResultRow, table: Table): EntityWithFromRow",
                    "val entity = EntityWithFromRow(",
                    "entity.name =",
                    "entity.active =",
                    "return entity"
                ),
            // Non-reactive @PersistenceMapping classes have no withEventsDisabled — must not be wrapped.
            absent = listOf("withEventsDisabled")
        )
    }

    test("generates fromRow that hydrates a reactive entity inside withEventsDisabled") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ReactiveFromRowEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class ReactiveFromRowEntity(override val id: Int) : ReactiveEntityBase<Int, ReactiveFromRowEntity>() {
                    var name: String by reactiveProperty("")
                    var score: Int by reactiveProperty(0)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = ReactiveFromRowEntity(id).also { it.name = name; it.score = score }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("ReactiveFromRowEntity_LirpTableDef.kt")
        // Hydration of a reactive entity must not emit mutation events — emitting during load would
        // schedule a stray write-back of the just-loaded values that races the repository's mutation
        // subscription (observed as an intermittently-lost update). The body-declared reactive
        // setters are therefore wrapped in withEventsDisabled.
        content.shouldContainEach(
            "entity.withEventsDisabled {",
            "entity.name =",
            "entity.score ="
        )
    }

    test("generates toParams that returns all column-value pairs including PK") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("EntityWithToParams_LirpTableDef.kt")
        content.shouldContainEach(
            "override fun toParams(entity: EntityWithToParams, table: Table): Map<Column<*>, Any?>",
            "cols[\"id\"]!! to entity.id",
            "cols[\"description\"]!! to entity.description",
            "cols[\"count\"]!! to entity.count"
        )
    }

    test("generates SqlTableDef when every non-PK column is a primary-constructor val") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("ImmutableEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "object ImmutableEntity_LirpTableDef : SqlTableDef<ImmutableEntity>",
            // fromRow rebuilds the entity through the primary constructor (both args are ctor params).
            "val entity = ImmutableEntity(",
            // applyRow has no mutable non-PK columns to reassign — emits the documented no-op branch.
            "No mutable non-PK columns"
        )
        // Critically: applyRow MUST NOT attempt `entity.name =` reassignment on the val column.
        val applyRowBlock = content.substringAfter("override fun applyRow").substringBefore("override fun ")
        applyRowBlock shouldNotContain "entity.name ="
    }

    test("generates correct Exposed v1 imports in SqlTableDef generated code") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("ImportCheckEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "import net.transgressoft.lirp.persistence.sql.SqlTableDef",
            "import org.jetbrains.exposed.v1.core.Column",
            "import org.jetbrains.exposed.v1.core.ResultRow",
            "import org.jetbrains.exposed.v1.core.Table"
        )
    }

    test("generates correct enum handling as String in fromRow and toParams") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("EntityWithEnum_LirpTableDef.kt")
        content.shouldContainEach(
            "ColumnType.EnumType",
            "enumValueOf<Status>",
            "entity.status.name"
        )
    }

    test("reports KSP error for unsupported property type") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        result.shouldFailWith("Unsupported column type")
    }

    test("generates UUID primary key column for entity with UUID id") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("UuidKeyEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "ColumnType.UuidType",
            "primaryKey = true",
            "tableName: String = \"uuid_key_entity\"",
            "SqlTableDef<UuidKeyEntity>",
            "toJavaUuid()"
        )
    }

    test("generates nullable columns for entity with all nullable non-PK properties") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("NullableEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "name = \"name\"",
            "nullable = true",
            "name = \"score\"",
            "name = \"active\""
        )
    }

    test("generates SqlTableDef for entity mixing ctor-param val and body-level var properties") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("MixedEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "object MixedEntity_LirpTableDef : SqlTableDef<MixedEntity>",
            "name = \"read_only\"",
            "name = \"mutable\"",
            // fromRow constructs the entity passing readOnly through the primary constructor.
            "val entity = MixedEntity("
        )
        // applyRow reassigns only the mutable body-level var; the immutable ctor-val is skipped.
        val applyRowBlock = content.substringAfter("override fun applyRow").substringBefore("override fun ")
        applyRowBlock shouldContain "entity.mutable ="
        applyRowBlock shouldNotContain "entity.readOnly ="
    }

    test("generates SqlTableDef for ReactiveEntityBase data class with ctor-param val and sibling reactive var") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "CtorValReactive.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                data class CtorValReactive(
                    override val id: String,
                    val label: String
                ) : ReactiveEntityBase<String, CtorValReactive>() {
                    var notes: String by reactiveProperty("")
                    override val uniqueId: String get() = id
                    override fun clone() = CtorValReactive(id, label).also { it.notes = notes }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("CtorValReactive_LirpTableDef.kt")
        content shouldContain "object CtorValReactive_LirpTableDef : SqlTableDef<CtorValReactive>"
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        fromRowBlock.shouldContainEachAndNone(
            // Both id and label are ctor params, so fromRow passes them positionally to the constructor.
            present = listOf("val entity = CtorValReactive(", "entity.notes ="),
            // The ctor-val `label` must never appear on the left-hand side of an assignment.
            absent = listOf("entity.label =")
        )
        val applyRowBlock = content.substringAfter("override fun applyRow").substringBefore("override fun ")
        applyRowBlock.shouldContainEachAndNone(
            present = listOf("entity.notes ="),
            absent = listOf("entity.label =")
        )
    }

    test("still falls back to LirpTableDef when a non-ctor non-PK property is immutable") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BodyValEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                // `description` has a backing field, is not in the primary constructor, and is `val`.
                // applyRow cannot reassign it, so the mutability gate must still reject this shape.
                @PersistenceMapping
                class BodyValEntity(val id: Int) {
                    val description: String = "fixed"
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("BodyValEntity_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present = listOf("object BodyValEntity_LirpTableDef : LirpTableDef<BodyValEntity>"),
            absent = listOf("SqlTableDef")
        )
    }

    test("generates correct descriptor for UUID PK entity with @PersistenceIgnore field") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
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

        val content = result.shouldSucceed().generatedFileContent("UuidIgnoreEntity_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present = listOf("ColumnType.UuidType", "primaryKey = true", "name = \"name\""),
            absent = listOf("transient_field", "transientField")
        )
    }

    test("generates SqlTableDef via resolver detection without KSP options") {
        // Verifies that SqlTableDef generation works through resolver.getClassDeclarationByName()
        // alone, which is the sole detection mechanism after removed options["lirp.sql"].
        // In monorepo tests, inheritClassPath = true means the resolver finds SqlTableDef.
        // For external consumers, the net.transgressoft.lirp.sql Gradle plugin adds lirp-sql
        // to the ksp configuration so the resolver finds it as well.
        val source =
            SourceFile.kotlin(
                "ResolverDetectedEntity.kt",
                """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class ResolverDetectedEntity(val id: Int) {
                    var label: String = ""
                }
                """
            )
        // No options passed — resolver-only detection
        val result = KspTestSupport.compile(TableDefProcessorProvider(), source)

        val content = result.shouldSucceed().generatedFileContent("ResolverDetectedEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "SqlTableDef<ResolverDetectedEntity>",
            "override fun fromRow(row: ResultRow, table: Table): ResolverDetectedEntity",
            "override fun toParams(entity: ResolverDetectedEntity, table: Table)"
        )
    }

    test("documents monorepo behavior: resolver still generates SqlTableDef without options") {
        // In monorepo tests with inheritClassPath = true, the resolver always finds SqlTableDef.
        // This test documents the expected behavior: even without any KSP options, the resolver
        // detects SqlTableDef and generates SqlTableDef (not LirpTableDef).
        // The LirpTableDef fallback path (with info diagnostic log) is exercised only when
        // lirp-sql is genuinely absent from the classpath, which cannot be easily simulated
        // in the monorepo test harness without stripping lirp-sql from the test compilation.
        val source =
            SourceFile.kotlin(
                "FallbackEntity.kt",
                """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class FallbackEntity(val id: Int) {
                    var name: String = ""
                }
                """
            )
        val result = KspTestSupport.compile(TableDefProcessorProvider(), source, options = emptyMap())

        result.shouldSucceed()
        // In monorepo, resolver finds SqlTableDef — so SqlTableDef is still generated
        val content = result.generatedFileContent("FallbackEntity_LirpTableDef.kt")
        content shouldContain "SqlTableDef<FallbackEntity>"
    }

    test("generates nullable UUID, LocalDate, LocalDateTime, and enum conversions in fromRow and toParams") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NullableTypesEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import java.util.UUID
                import java.time.LocalDate
                import java.time.LocalDateTime

                enum class Status { ACTIVE, INACTIVE }

                @PersistenceMapping
                class NullableTypesEntity(override val id: UUID) : ReactiveEntityBase<UUID, NullableTypesEntity>() {
                    var parentId: UUID? by reactiveProperty(null)
                    var startDate: LocalDate? by reactiveProperty(null)
                    var modifiedAt: LocalDateTime? by reactiveProperty(null)
                    var status: Status? by reactiveProperty(null)
                    var label: String? by reactiveProperty(null)
                    override val uniqueId: String get() = id.toString()
                    override fun clone() = NullableTypesEntity(id)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("NullableTypesEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "SqlTableDef<NullableTypesEntity>",
            "@OptIn(ExperimentalUuidApi::class)",
            // Nullable UUID conversions
            "as? kotlin.uuid.Uuid)?.toJavaUuid()",
            "entity.parentId?.toKotlinUuid()",
            // Non-null UUID PK conversion
            "as kotlin.uuid.Uuid).toJavaUuid()",
            "entity.id.toKotlinUuid()",
            // Nullable LocalDate conversions
            "as? kotlinx.datetime.LocalDate)?.toJavaLocalDate()",
            "entity.startDate?.toKotlinLocalDate()",
            // Nullable LocalDateTime conversions
            "as? kotlinx.datetime.LocalDateTime)?.toJavaLocalDateTime()",
            "entity.modifiedAt?.toKotlinLocalDateTime()",
            // Nullable enum conversions
            """as? String)?.let { enumValueOf<Status>(it) }""",
            "entity.status?.name",
            // Nullable String
            "as? String"
        )
    }

    test("generates BigDecimal import and correct column type for DecimalType properties") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "DecimalEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.PersistenceProperty
                import java.math.BigDecimal

                @PersistenceMapping
                class DecimalEntity(val id: Int) {
                    @PersistenceProperty(precision = 10, scale = 2)
                    var price: BigDecimal = BigDecimal.ZERO

                    @PersistenceProperty(precision = 14, scale = 4)
                    var rate: BigDecimal? = null
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("DecimalEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "SqlTableDef<DecimalEntity>",
            "import java.math.BigDecimal",
            "ColumnType.DecimalType(10, 2)",
            "ColumnType.DecimalType(14, 4)",
            "as BigDecimal",
            "as? BigDecimal"
        )
    }

    test("generates correct ordered multi-param constructor call in fromRow") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "MultiParamEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import java.util.UUID

                @PersistenceMapping
                class MultiParamEntity(
                    override val id: UUID,
                    tenantId: UUID
                ) : ReactiveEntityBase<UUID, MultiParamEntity>() {
                    var tenantId: UUID by reactiveProperty(tenantId)
                    var name: String by reactiveProperty("")
                    override val uniqueId: String get() = id.toString()
                    override fun clone() = MultiParamEntity(id, tenantId)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("MultiParamEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "SqlTableDef<MultiParamEntity>",
            // Constructor args in declaration order: id first, tenantId second
            "val entity = MultiParamEntity(",
            "entity.name ="
        )
        // In fromRow, tenantId must be in the constructor call (not a setter) since it's a ctor param.
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        fromRowBlock shouldNotContain "entity.tenantId ="
    }

    test("falls back to LirpTableDef when constructor param has no matching column") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "UnmappedCtorEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.PersistenceIgnore

                @PersistenceMapping
                class UnmappedCtorEntity(val id: Int, @PersistenceIgnore val transientParam: String) {
                    var name: String = ""
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("UnmappedCtorEntity_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            // Should fall back to LirpTableDef since transientParam is excluded from columns
            present = listOf("LirpTableDef<UnmappedCtorEntity>"),
            absent = listOf("SqlTableDef", "fromRow")
        )
    }

    test("generates isVersion = true in ColumnDef for a valid @Version property") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "VersionedEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.Version

                @PersistenceMapping
                class VersionedEntity(override val id: Int) : ReactiveEntityBase<Int, VersionedEntity>() {
                    @Version var version: Long by reactiveProperty(0L)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = VersionedEntity(id)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("VersionedEntity_LirpTableDef.kt")
        content shouldContain "isVersion = true"
        // The id column should still carry isVersion = false
        val idColumnLine = content.lines().first { it.contains("name = \"id\"") }
        idColumnLine shouldContain "isVersion = false"
    }

    test("generates symmetric applyRow function for entity with @Version") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "VersionedEntity2.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.Version

                @PersistenceMapping
                class VersionedEntity2(override val id: Int) : ReactiveEntityBase<Int, VersionedEntity2>() {
                    var name: String by reactiveProperty("")
                    @Version var version: Long by reactiveProperty(0L)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = VersionedEntity2(id)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("VersionedEntity2_LirpTableDef.kt")
        content.shouldContainEach(
            "override fun applyRow(entity: VersionedEntity2, row: ResultRow, table: Table)",
            "entity.name =",
            "entity.version ="
        )
        // The id (PK) should NOT appear in applyRow assignments
        val applyRowBlock = content.substringAfter("override fun applyRow").substringBefore("\n    }")
        applyRowBlock shouldNotContain "entity.id ="
    }

    test("rejects @Version on a non-Long property") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BadVersion1.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.Version

                @PersistenceMapping
                class BadVersion1(override val id: Int) : ReactiveEntityBase<Int, BadVersion1>() {
                    @Version var version: Int by reactiveProperty(0)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = BadVersion1(id)
                }
                """
                )
            )

        result.shouldFailWith("must be of type 'Long'")
    }

    test("rejects @Version on a val property") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BadVersion2.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.Version

                @PersistenceMapping
                class BadVersion2(override val id: Int) : ReactiveEntityBase<Int, BadVersion2>() {
                    @Version val version: Long by reactiveProperty(0L)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = BadVersion2(id)
                }
                """
                )
            )

        result.shouldFailWith("must be declared with 'var'")
    }

    test("rejects multiple @Version properties on one class") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BadVersion3.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.Version

                @PersistenceMapping
                class BadVersion3(override val id: Int) : ReactiveEntityBase<Int, BadVersion3>() {
                    @Version var v1: Long by reactiveProperty(0L)
                    @Version var v2: Long by reactiveProperty(0L)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = BadVersion3(id)
                }
                """
                )
            )

        result.shouldFailWith("multiple @Version properties")
    }

    test("rejects @Version on a non-delegated property") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BadVersion4.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.Version

                @PersistenceMapping
                class BadVersion4(override val id: Int) : ReactiveEntityBase<Int, BadVersion4>() {
                    @Version var version: Long = 0L
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = BadVersion4(id)
                }
                """
                )
            )

        result.shouldFailWith("must use the 'reactiveProperty' delegate")
    }

    test("entity without @Version has isVersion = false on all columns") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "Unversioned.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class Unversioned(override val id: Int) : ReactiveEntityBase<Int, Unversioned>() {
                    var name: String by reactiveProperty("")
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Unversioned(id)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Unversioned_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present = listOf("isVersion = false"),
            absent = listOf("isVersion = true")
        )
    }

    test("generates bumpVersion override for entity with @Version") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BumpCheck.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.Version

                @PersistenceMapping
                class BumpCheck(override val id: Int) : ReactiveEntityBase<Int, BumpCheck>() {
                    @Version var version: Long by reactiveProperty(0L)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = BumpCheck(id)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("BumpCheck_LirpTableDef.kt")
        content.shouldContainEach(
            "override fun bumpVersion(entity: BumpCheck, newVersion: Long)",
            "entity.version = newVersion",
            "override fun versionOf(entity: BumpCheck): Long",
            "return entity.version"
        )
    }

    test("does NOT generate bumpVersion override for entity without @Version") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NoBump.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class NoBump(override val id: Int) : ReactiveEntityBase<Int, NoBump>() {
                    var name: String by reactiveProperty("")
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = NoBump(id)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("NoBump_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present = emptyList(),
            absent = listOf("override fun bumpVersion", "override fun versionOf")
        )
    }

    // ---- Junction tables and FK constraints (#144) ----

    test("emits Playlist_Items_LirpJunctionTableDef for aggregateList collection ref") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "Playlist.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.IdentifiableEntity
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.ToManyAggregates
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.aggregateList

                @PersistenceMapping
                data class Track(override val id: Int, val title: String) : ReactiveEntityBase<Int, Track>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Track(id, title)
                }

                @PersistenceMapping
                class Playlist(override val id: Int) : ReactiveEntityBase<Int, Playlist>() {
                    var trackIds: List<Int> = emptyList()
                    @ToManyAggregates
                    val tracks by aggregateList<Int, Track>(trackIds)

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Playlist(id).also { it.trackIds = trackIds.toList() }
                }
                """
                )
            )

        val junction = result.shouldSucceed().generatedFileContent("Playlist_Tracks_LirpJunctionTableDef.kt")
        junction.shouldContainEach(
            "object Playlist_Tracks_LirpJunctionTableDef : JunctionTableDef",
            "tableName: String = \"playlist_tracks\"",
            "parentTableName: String = \"playlist\"",
            "itemTableName: String = \"track\"",
            "isOrdered: Boolean = true",
            "JunctionColumnDef(name = \"parent_id\"",
            "JunctionColumnDef(name = \"item_id\"",
            "JunctionColumnDef(name = \"position\"",
            "parentFkOnDelete: CascadeAction = CascadeAction.CASCADE",
            "itemFkOnDelete: CascadeAction = CascadeAction.DETACH"
        )
    }

    test("emits unordered junction descriptor without position column for aggregateSet") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "Album.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.ToManyAggregates
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.aggregateSet

                @PersistenceMapping
                data class Tag(override val id: Int, val label: String) : ReactiveEntityBase<Int, Tag>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Tag(id, label)
                }

                @PersistenceMapping
                class Album(override val id: Int) : ReactiveEntityBase<Int, Album>() {
                    var tagIds: Set<Int> = emptySet()
                    @ToManyAggregates
                    val tags by aggregateSet<Int, Tag>(tagIds)

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Album(id).also { it.tagIds = tagIds.toSet() }
                }
                """
                )
            )

        val junction = result.shouldSucceed().generatedFileContent("Album_Tags_LirpJunctionTableDef.kt")
        junction.shouldContainEachAndNone(
            present = listOf("isOrdered: Boolean = false"),
            absent = listOf("position")
        )
    }

    test("attaches RESTRICT foreign key to @ToOneAggregate scalar") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "Order.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.CascadeAction
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.ToOneAggregate

                @PersistenceMapping
                class Customer(override val id: Int) : ReactiveEntityBase<Int, Customer>() {
                    var name: String by reactiveProperty("")
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Customer(id)
                }

                @PersistenceMapping
                class Order(override val id: Long, customerId: Int) : ReactiveEntityBase<Long, Order>() {
                    @ToOneAggregate(target = Customer::class, onDelete = CascadeAction.RESTRICT)
                    var customerId: Int by reactiveProperty(customerId)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Order(id, customerId)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Order_LirpTableDef.kt")
        content.shouldContainEach(
            "override fun foreignKeys(): List<ForeignKeyDef>",
            "ForeignKeyDef(columnName = \"customer_id\"",
            "referencedTable = \"customer\"",
            "referencedColumn = \"id\"",
            "onDelete = CascadeAction.RESTRICT"
        )
    }

    test("rejects DETACH on non-nullable @ToOneAggregate scalar at compile time") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BadDetach.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.CascadeAction
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.ToOneAggregate

                @PersistenceMapping
                class Country(override val id: Long) : ReactiveEntityBase<Long, Country>() {
                    var code: String by reactiveProperty("")
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Country(id)
                }

                @PersistenceMapping
                class Address(override val id: Long, countryId: Long) : ReactiveEntityBase<Long, Address>() {
                    @ToOneAggregate(target = Country::class, onDelete = CascadeAction.DETACH)
                    var countryId: Long by reactiveProperty(countryId)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Address(id, countryId)
                }
                """
                )
            )

        result.shouldFailWith("requires a nullable backing scalar")
    }

    test("allows DETACH on nullable @ToOneAggregate scalar and emits SET_NULL semantics") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "GoodDetach.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.CascadeAction
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.ToOneAggregate

                @PersistenceMapping
                class Region(override val id: Long) : ReactiveEntityBase<Long, Region>() {
                    var name: String by reactiveProperty("")
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Region(id)
                }

                @PersistenceMapping
                class Site(override val id: Long, regionId: Long?) : ReactiveEntityBase<Long, Site>() {
                    @ToOneAggregate(target = Region::class, onDelete = CascadeAction.DETACH)
                    var regionId: Long? by reactiveProperty(regionId)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Site(id, regionId)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Site_LirpTableDef.kt")
        content.shouldContainEach(
            "ForeignKeyDef(columnName = \"region_id\"",
            "onDelete = CascadeAction.DETACH"
        )
    }

    test("emits SET_NULL FK for nullable @ToOneAggregate scalar with DETACH action") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "OptionalAggregateRef.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.CascadeAction
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.ToOneAggregate
                import java.util.UUID

                @PersistenceMapping
                class Tenant(override val id: UUID, parentTenantId: UUID? = null) : ReactiveEntityBase<UUID, Tenant>() {
                    @ToOneAggregate(target = Tenant::class, onDelete = CascadeAction.DETACH)
                    var parentTenantId: UUID? by reactiveProperty(parentTenantId)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Tenant(id, parentTenantId)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Tenant_LirpTableDef.kt")
        content.shouldContainEach(
            "ForeignKeyDef(columnName = \"parent_tenant_id\"",
            "onDelete = CascadeAction.DETACH"
        )
    }

    // ---- Junction accessor wiring on _LirpTableDef (#144 / FK-04, plan 53-03a) ----

    test("_LirpTableDef overrides junctionTableDefs for entity with aggregateList collection ref") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "PlaylistJunctionWiring.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.ToManyAggregates
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.aggregateList

                @PersistenceMapping
                data class Track(override val id: Int, val title: String) : ReactiveEntityBase<Int, Track>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Track(id, title)
                }

                @PersistenceMapping
                class Playlist(override val id: Int) : ReactiveEntityBase<Int, Playlist>() {
                    var trackIds: List<Int> = emptyList()
                    @ToManyAggregates
                    val tracks by aggregateList<Int, Track>(trackIds)

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Playlist(id).also { it.trackIds = trackIds.toList() }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Playlist_LirpTableDef.kt")
        content.shouldContainEach(
            "import net.transgressoft.lirp.persistence.sql.JunctionAccessor",
            "import net.transgressoft.lirp.persistence.sql.JunctionTableDef",
            "override val junctionTableDefs: List<JunctionTableDef>",
            "Playlist_Tracks_LirpJunctionTableDef"
        )
    }

    test("_LirpTableDef overrides junctionAccessors with idsOf returning the backing field") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "PlaylistAccessorWiring.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.ToManyAggregates
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.aggregateList

                @PersistenceMapping
                data class Track(override val id: Int, val title: String) : ReactiveEntityBase<Int, Track>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Track(id, title)
                }

                @PersistenceMapping
                class Playlist(override val id: Int) : ReactiveEntityBase<Int, Playlist>() {
                    var trackIds: List<Int> = emptyList()
                    @ToManyAggregates
                    val tracks by aggregateList<Int, Track>(trackIds)

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Playlist(id).also { it.trackIds = trackIds.toList() }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Playlist_LirpTableDef.kt")
        content.shouldContainEach(
            "override val junctionAccessors: List<JunctionAccessor<Playlist>>",
            "object : JunctionAccessor<Playlist>",
            "override val descriptor: JunctionTableDef = Playlist_Tracks_LirpJunctionTableDef",
            "override fun idsOf(entity: Playlist): Collection<Any> = entity.trackIds"
        )
    }

    test("_LirpTableDef overrides applyJunctionRows wrapping mutation in withEventsDisabled") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "PlaylistApplyJunction.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.ToManyAggregates
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.aggregateList

                @PersistenceMapping
                data class Track(override val id: Int, val title: String) : ReactiveEntityBase<Int, Track>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Track(id, title)
                }

                @PersistenceMapping
                class Playlist(override val id: Int) : ReactiveEntityBase<Int, Playlist>() {
                    var trackIds: List<Int> = emptyList()
                    @ToManyAggregates
                    val tracks by aggregateList<Int, Track>(trackIds)

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Playlist(id).also { it.trackIds = trackIds.toList() }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Playlist_LirpTableDef.kt")
        content.shouldContainEach(
            "override fun applyJunctionRows(",
            "entity.withEventsDisabled",
            "Playlist_Tracks_LirpJunctionTableDef ->",
            "entity.trackIds = ids.filterIsInstance<Int>()"
        )
    }

    test("_LirpTableDef does NOT override junction members when entity has no collection aggregates") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "Plain.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                data class Plain(override val id: Int, val label: String) : ReactiveEntityBase<Int, Plain>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Plain(id, label)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Plain_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present = emptyList(),
            absent = listOf("junctionTableDefs", "junctionAccessors", "applyJunctionRows")
        )
    }

    test("KSP-emitted _LirpTableDef applies junction rows at runtime without firing MutationEvents") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "RuntimePlaylist.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.ToManyAggregates
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.aggregateList

                @PersistenceMapping
                data class Track(override val id: Int, val title: String) : ReactiveEntityBase<Int, Track>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Track(id, title)
                }

                @PersistenceMapping
                class RuntimePlaylist(override val id: Int) : ReactiveEntityBase<Int, RuntimePlaylist>() {
                    var trackIds: List<Int> = emptyList()
                    @ToManyAggregates
                    val tracks by aggregateList<Int, Track>(trackIds)

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = RuntimePlaylist(id).also { it.trackIds = trackIds.toList() }
                }
                """
                )
            )

        result.shouldSucceed()

        val cl = result.classLoader
        val playlistClass = cl.loadClass("test.RuntimePlaylist")
        val tableDefClass = cl.loadClass("test.RuntimePlaylist_LirpTableDef")
        val junctionClass = cl.loadClass("test.RuntimePlaylist_Tracks_LirpJunctionTableDef")

        val tableDef = tableDefClass.getField("INSTANCE").get(null)
        val junctionDescriptor = junctionClass.getField("INSTANCE").get(null)

        // junctionTableDefs.size == 1 and contains the expected descriptor instance
        val descriptors = tableDefClass.getMethod("getJunctionTableDefs").invoke(tableDef) as List<*>
        descriptors.size shouldBe 1
        (descriptors.first() === junctionDescriptor) shouldBe true

        // junctionAccessors.size == 1, descriptor matches, idsOf returns trackIds contents
        val accessors = tableDefClass.getMethod("getJunctionAccessors").invoke(tableDef) as List<*>
        accessors.size shouldBe 1
        val accessor = accessors.first()!!
        val accessorDescriptor =
            accessor.javaClass.getMethod("getDescriptor").invoke(accessor)
        (accessorDescriptor === junctionDescriptor) shouldBe true

        // Construct an entity, subscribe to MutationEvents via a counter, then call applyJunctionRows.
        val entity = playlistClass.getConstructor(Int::class.java).newInstance(7)
        val mutationCount = java.util.concurrent.atomic.AtomicInteger(0)
        val subscribeMethod =
            playlistClass.methods.first {
                it.name == "subscribeAsync" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == kotlin.jvm.functions.Function2::class.java
            }
        val action: suspend (Any?) -> Unit = { _ ->
            mutationCount.incrementAndGet()
            Unit
        }
        @Suppress("UNCHECKED_CAST")
        subscribeMethod.invoke(entity, action as kotlin.jvm.functions.Function2<Any?, kotlin.coroutines.Continuation<in Unit>, Any?>)

        // applyJunctionRows(entity, descriptor, ids)
        val applyMethod =
            tableDefClass.methods.first { it.name == "applyJunctionRows" && it.parameterCount == 3 }
        applyMethod.invoke(tableDef, entity, junctionDescriptor, listOf(1, 2, 3))

        // Field is a plain `var` — direct read of the backing trackIds reflects the assignment
        val trackIdsField = playlistClass.getMethod("getTrackIds").invoke(entity) as List<*>
        trackIdsField shouldBe listOf(1, 2, 3)

        // idsOf returns the same content (and same reference, since the trackIds field is a plain var)
        val idsOfMethod = accessor.javaClass.getMethod("idsOf", Any::class.java)
        val idsResult = idsOfMethod.invoke(accessor, entity) as Collection<*>
        idsResult.toList() shouldBe listOf(1, 2, 3)

        // Brief wait so any erroneous async event delivery would have happened.
        Thread.sleep(100)
        mutationCount.get() shouldBe 0
    }

    test("KSP emits a clear error when aggregateList backing field is not a writable List<K>") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BadBacking.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.ToManyAggregates
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.aggregateList

                @PersistenceMapping
                data class Track(override val id: Int, val title: String) : ReactiveEntityBase<Int, Track>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = Track(id, title)
                }

                @PersistenceMapping
                class BadPlaylist(override val id: Int) : ReactiveEntityBase<Int, BadPlaylist>() {
                    // No `var trackIds: List<Int>` backing field — KSP must surface this.
                    @ToManyAggregates
                    val tracks by aggregateList<Int, Track>(emptyList())

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = BadPlaylist(id)
                }
                """
                )
            )

        result.shouldFailWith("KSP[FK-04]", "must be a 'var List<K>'")
    }

    // ---- Short / Byte column-type inference (#207-B2) ----

    test("TableDefProcessor emits internal object declaration for top-level internal entity") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "InternalPersisted.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                internal data class InternalPersisted(override val id: Int) : ReactiveEntityBase<Int, InternalPersisted>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = InternalPersisted(id)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("InternalPersisted_LirpTableDef.kt")
        content shouldContain "internal object InternalPersisted_LirpTableDef"
    }

    test("TableDefProcessor emits internal object declaration for internal entity with multiple properties") {
        // Verifies that the internal modifier is propagated correctly for an internal entity with several columns
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "InternalMultiProp.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                internal data class InternalMultiProp(override val id: Int, val name: String) : ReactiveEntityBase<Int, InternalMultiProp>() {
                    var score: Int by reactiveProperty(0)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = InternalMultiProp(id, name).also { it.score = score }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("InternalMultiProp_LirpTableDef.kt")
        content.shouldContainEach(
            "internal object InternalMultiProp_LirpTableDef",
            "name = \"name\"",
            "name = \"score\""
        )
    }

    test("TableDefProcessor fails compilation for private-nested entity with @PersistenceMapping") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "PrivateOuterTableDef.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                private class PrivateOuterTableDef {
                    @PersistenceMapping
                    data class HiddenPersisted(override val id: Int) : ReactiveEntityBase<Int, HiddenPersisted>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = HiddenPersisted(id)
                    }
                }
                """
                )
            )

        result.shouldFailWith(
            "must be public or internal",
            "Private and protected entities cannot have accessible generated code"
        )
    }

    test("TableDefProcessor maps kotlin.Short to ColumnType.IntType and emits .toShort() narrowing in fromRow") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ShortEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class ShortEntity(override val id: Int) : ReactiveEntityBase<Int, ShortEntity>() {
                    var year: Short by reactiveProperty(0)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = ShortEntity(id).also { it.year = year }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("ShortEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "name = \"year\", type = ColumnType.IntType",
            "as Number).toShort()",
            "entity.year.toInt()"
        )
    }

    test("TableDefProcessor maps kotlin.Byte to ColumnType.IntType and emits .toByte() narrowing in fromRow") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ByteEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class ByteEntity(override val id: Int) : ReactiveEntityBase<Int, ByteEntity>() {
                    var flag: Byte by reactiveProperty(0)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = ByteEntity(id).also { it.flag = flag }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("ByteEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "name = \"flag\", type = ColumnType.IntType",
            "as Number).toByte()",
            "entity.flag.toInt()"
        )
    }

    test("TableDefProcessor maps nullable kotlin.Short to nullable ColumnType.IntType with safe narrowing") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NullableShortEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class NullableShortEntity(override val id: Int) : ReactiveEntityBase<Int, NullableShortEntity>() {
                    var year: Short? by reactiveProperty(null)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = NullableShortEntity(id).also { it.year = year }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("NullableShortEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "name = \"year\", type = ColumnType.IntType, nullable = true",
            "as? Number)?.toShort()",
            "entity.year?.toInt()"
        )
    }

    test("TableDefProcessor maps nullable kotlin.Byte to nullable ColumnType.IntType with safe narrowing") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NullableByteEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class NullableByteEntity(override val id: Int) : ReactiveEntityBase<Int, NullableByteEntity>() {
                    var flag: Byte? by reactiveProperty(null)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = NullableByteEntity(id).also { it.flag = flag }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("NullableByteEntity_LirpTableDef.kt")
        content.shouldContainEach(
            "name = \"flag\", type = ColumnType.IntType, nullable = true",
            "as? Number)?.toByte()",
            "entity.flag?.toInt()"
        )
    }

    test("TableDefProcessor excludes @kotlinx.serialization.Transient mirror and generates delegate-backed column for lirp+kotlinx+reactive-delegate entity") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "AudioItem.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.PersistenceProperty
                import kotlinx.serialization.Transient as KxTransient

                @PersistenceMapping
                data class AudioItem(
                    override val id: Int,
                    @PersistenceProperty val title: String
                ) : ReactiveEntityBase<Int, AudioItem>() {
                    @KxTransient
                    val titleProperty: Any = Any()

                    var displayName: String by reactiveProperty(title)

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = AudioItem(id, title).also { it.displayName = displayName }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("AudioItem_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present = listOf("\"title\"", "\"display_name\""),
            absent = listOf("titleProperty", "title_property")
        )
        result.messages shouldNotContain "Unsupported column type"
    }

    test("TableDefProcessor preserves existing kotlin.Int code generation when Short and Byte are also present") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "MixedIntegersEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class MixedIntegersEntity(override val id: Int) : ReactiveEntityBase<Int, MixedIntegersEntity>() {
                    var a: Int by reactiveProperty(0)
                    var b: Short by reactiveProperty(0)
                    var c: Byte by reactiveProperty(0)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = MixedIntegersEntity(id).also { copy ->
                        copy.a = a; copy.b = b; copy.c = c
                    }
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("MixedIntegersEntity_LirpTableDef.kt")
        content.shouldContainEach(
            // All three columns resolve to IntType.
            "name = \"a\", type = ColumnType.IntType",
            "name = \"b\", type = ColumnType.IntType",
            "name = \"c\", type = ColumnType.IntType",
            // The Int branch must NOT pick up Short/Byte narrowing.
            "entity.a = row[table.columns.first { it.name == \"a\" }] as Int",
            "cols[\"a\"]!! to entity.a",
            // Short/Byte still get the narrowing/widening treatment.
            "as Number).toShort()",
            "as Number).toByte()",
            "entity.b.toInt()",
            "entity.c.toInt()"
        )
    }

    // ---- Deferral — terminal diagnostic for permanently-unresolved types (#219) ----

    test("TableDefProcessor emits targeted diagnostic for permanently unresolved property type") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BrokenEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.PersistenceProperty

                @PersistenceMapping
                class BrokenEntity(val id: Int) {
                    @PersistenceProperty
                    var score: DoesNotExist = error("unreachable")
                }
                """
                )
            )

        result.messages shouldContain "still unresolved after final round"
    }

    test("TableDefProcessor emits no terminal diagnostic when types resolve") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ResolvableEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class ResolvableEntity(val id: Int) {
                    var name: String = ""
                    var count: Int = 0
                }
                """
                )
            )

        result.shouldSucceed()
        result.messages shouldNotContain "still unresolved after final round"
    }

    // ---- Reactive self-type R resolution (#220) ----

    test("TableDefProcessor generates descriptor typed on distinct reactive interface for MutableAudioItem/AudioItem split") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "AudioItem.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntity
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                interface AudioItem : ReactiveEntity<Int, AudioItem> {
                    val title: String
                }

                @PersistenceMapping
                class MutableAudioItem(override val id: Int, title: String) : ReactiveEntityBase<Int, AudioItem>(), AudioItem {
                    override var title: String by reactiveProperty(title)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = MutableAudioItem(id, title)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("MutableAudioItem_LirpTableDef.kt")
        content.shouldContainEach(
            "object MutableAudioItem_LirpTableDef : SqlTableDef<test.AudioItem>",
            "override fun fromRow(row: ResultRow, table: Table): test.AudioItem",
            "entityRef as MutableAudioItem"
        )
    }

    test("TableDefProcessor resolves R through an intermediate reactive base class") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "MultiLevelEntity.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntity
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                interface MediaItem : ReactiveEntity<Int, MediaItem> {
                    val name: String
                }

                abstract class MediaBase<K : Comparable<K>, R : ReactiveEntity<K, R>> : ReactiveEntityBase<K, R>()

                @PersistenceMapping
                class ConcreteMedia(override val id: Int, name: String) : MediaBase<Int, MediaItem>(), MediaItem {
                    override var name: String by reactiveProperty(name)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = ConcreteMedia(id, name)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("ConcreteMedia_LirpTableDef.kt")
        content.shouldContainEach(
            "object ConcreteMedia_LirpTableDef : SqlTableDef<test.MediaItem>",
            "override fun fromRow(row: ResultRow, table: Table): test.MediaItem",
            "entityRef as ConcreteMedia"
        )
    }

    test("TableDefProcessor falls back to concrete class typing when R is not resolvable") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "PlainMapped.kt",
                    """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class PlainMapped(val id: Int) {
                    var label: String = ""
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("PlainMapped_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present = listOf("object PlainMapped_LirpTableDef : SqlTableDef<PlainMapped>"),
            absent = listOf("entityRef as")
        )
    }

    test("TableDefProcessor types the descriptor on the class itself for a self-referential reactive entity") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "SelfReactive.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class SelfReactive(override val id: Int, label: String) : ReactiveEntityBase<Int, SelfReactive>() {
                    var label: String by reactiveProperty(label)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = SelfReactive(id, label)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("SelfReactive_LirpTableDef.kt")
        content.shouldContainEachAndNone(
            present =
                listOf(
                    "object SelfReactive_LirpTableDef : SqlTableDef<SelfReactive>",
                    "override fun fromRow(row: ResultRow, table: Table): SelfReactive"
                ),
            // R == class: no downcast alias is emitted, preserving the original byte-identical layout.
            absent = listOf("entityRef as")
        )
    }

    test("TableDefProcessor skips generation when R stays an unsubstituted type parameter on a generic entity") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "GenericReactive.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntity
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class GenericReactive<R : ReactiveEntity<Int, R>>(override val id: Int) : ReactiveEntityBase<Int, R>() {
                    override val uniqueId: String get() = "${'$'}id"
                    @Suppress("UNCHECKED_CAST")
                    override fun clone() = GenericReactive<R>(id) as R
                }
                """
                )
            )

        // A raw SqlTableDef<GenericReactive> would not compile, so the processor skips generation.
        result.messages shouldContain "Skipping _LirpTableDef generation for test.GenericReactive"
        result.generatedNames().any { it == "GenericReactive_LirpTableDef.kt" } shouldBe false
    }

    test("TableDefProcessor renders type arguments for a concrete parameterized reactive self-type") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "Tagged.kt",
                    """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntity
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                interface Tagged<M> : ReactiveEntity<Int, Tagged<M>> {
                    val tag: String
                }

                @PersistenceMapping
                class StringTagged(override val id: Int, tag: String) : ReactiveEntityBase<Int, Tagged<String>>(), Tagged<String> {
                    override var tag: String by reactiveProperty(tag)
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = StringTagged(id, tag)
                }
                """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("StringTagged_LirpTableDef.kt")
        content.shouldContainEach(
            "object StringTagged_LirpTableDef : SqlTableDef<test.Tagged<kotlin.String>>",
            "override fun fromRow(row: ResultRow, table: Table): test.Tagged<kotlin.String>",
            "entityRef as StringTagged"
        )
    }
})
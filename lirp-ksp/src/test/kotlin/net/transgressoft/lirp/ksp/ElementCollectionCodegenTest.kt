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
 * KSP compilation tests for the JSON-array encode/decode codegen emitted by [TableDefSourceEmitter]
 * for `@ElementCollection` columns. Each test compiles a fixture entity carrying an
 * `@ElementCollection`-annotated property and asserts on the generated `_LirpTableDef.kt` source
 * string, exercising the JSON-array encode/decode emission.
 */
@OptIn(ExperimentalCompilerApi::class)
class ElementCollectionCodegenTest : StringSpec({

    // Codegen assertions use KSP-only compilation (no full Kotlin compile needed)
    fun compile(vararg sources: SourceFile) =
        KspTestSupport.compile(TableDefProcessorProvider(), *sources, withCompilation = false)

    "emits .toSet() terminal for Set<E> element-collection in fromRow" {
        val result =
            compile(
                SourceFile.kotlin(
                    "SetTagEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object StringTagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @PersistenceMapping
                    data class SetTagEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = StringTagConverter::class) val tags: Set<String>
                    ) : ReactiveEntityBase<Int, SetTagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = SetTagEntity(id, tags)
                    }
                    """
                )
            )

        val generated = result.generatedFileContent("SetTagEntity_LirpTableDef.kt")
        generated shouldContain "Json.decodeFromString<kotlin.collections.List<kotlin.String>>"
        generated shouldContain ".map { test.StringTagConverter.fromSql(it) }.toSet()"
    }

    "emits no terminal for List<E> element-collection in fromRow" {
        val result =
            compile(
                SourceFile.kotlin(
                    "ListTagEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object ListTagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @PersistenceMapping
                    data class ListTagEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = ListTagConverter::class) val tags: List<String>
                    ) : ReactiveEntityBase<Int, ListTagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ListTagEntity(id, tags)
                    }
                    """
                )
            )

        val generated = result.generatedFileContent("ListTagEntity_LirpTableDef.kt")
        generated shouldContain ".map { test.ListTagConverter.fromSql(it) }"
        generated shouldNotContain ".map { test.ListTagConverter.fromSql(it) }.toSet()"
    }

    "emits Json.encodeToString with native-S type parameter for non-String S converter" {
        val result =
            compile(
                SourceFile.kotlin(
                    "RatingEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    data class Rating(val value: Int)

                    object IntRatingConverter : ColumnConverter<Rating, Int> {
                        override val sqlType = ColumnType.IntType
                        override fun toSql(value: Rating): Int = value.value
                        override fun fromSql(raw: Int): Rating = Rating(raw)
                    }

                    @PersistenceMapping
                    data class RatingEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = IntRatingConverter::class) val ratings: Set<Rating>
                    ) : ReactiveEntityBase<Int, RatingEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = RatingEntity(id, ratings)
                    }
                    """
                )
            )

        val generated = result.generatedFileContent("RatingEntity_LirpTableDef.kt")
        generated shouldContain "Json.encodeToString<kotlin.collections.List<kotlin.Int>>"
        generated shouldNotContain "Json.encodeToString<kotlin.collections.List<kotlin.String>>"
    }

    "emits defaultExpression = \"[]\" in the ColumnDef literal for @ElementCollection columns" {
        val result =
            compile(
                SourceFile.kotlin(
                    "DefaultExprEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @PersistenceMapping
                    data class DefaultExprEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = TagConverter::class) val tags: Set<String>
                    ) : ReactiveEntityBase<Int, DefaultExprEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DefaultExprEntity(id, tags)
                    }
                    """
                )
            )

        val generated = result.generatedFileContent("DefaultExprEntity_LirpTableDef.kt")
        generated shouldContain "defaultExpression = \"[]\""
    }

    "emits kotlinx.serialization imports when an entity declares an @ElementCollection column" {
        val result =
            compile(
                SourceFile.kotlin(
                    "ImportCheckEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object LabelConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @PersistenceMapping
                    data class ImportCheckEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = LabelConverter::class) val labels: Set<String>
                    ) : ReactiveEntityBase<Int, ImportCheckEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ImportCheckEntity(id, labels)
                    }
                    """
                )
            )

        val generated = result.generatedFileContent("ImportCheckEntity_LirpTableDef.kt")
        generated shouldContain "import kotlinx.serialization.json.Json"
    }

    "does not emit kotlinx.serialization imports when no @ElementCollection column is present" {
        val result =
            compile(
                SourceFile.kotlin(
                    "PlainEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class PlainEntity(
                        override val id: Int,
                        val name: String
                    ) : ReactiveEntityBase<Int, PlainEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = PlainEntity(id, name)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generated = result.generatedFileContent("PlainEntity_LirpTableDef.kt")
        generated shouldNotContain "import kotlinx.serialization.json.Json"
    }
})
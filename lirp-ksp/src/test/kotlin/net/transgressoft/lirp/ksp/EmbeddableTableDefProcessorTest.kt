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
 * KSP compilation tests for the happy-path flattening of `@Embedded` value objects in
 * [TableDefProcessor]. Locks the column-naming rules (verbatim prefix, empty-string
 * reverts to auto-derive, D-Annot-1 recursive prefix concatenation), the 3-level recursion
 * surface, and composition with `@PersistenceProperty(converter = …)` at scalar leaves.
 */
@OptIn(ExperimentalCompilerApi::class)
class EmbeddableTableDefProcessorTest : StringSpec({

    "flattens @Embedded with default prefix derives snake_case property name" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "DefaultPrefixEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class ArtistEmbeddable(val name: String, val countryCode: String)

                    @PersistenceMapping
                    data class DefaultPrefixEntity(
                        override val id: Int,
                        @Embedded val performer: ArtistEmbeddable
                    ) : ReactiveEntityBase<Int, DefaultPrefixEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = DefaultPrefixEntity(id, performer)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("DefaultPrefixEntity_LirpTableDef.kt")
        content shouldContain "name = \"performer_name\""
        content shouldContain "name = \"performer_country_code\""
    }

    "flattens @Embedded with explicit prefix uses verbatim string" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ExplicitPrefixEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class ArtistEmbeddable(val name: String, val countryCode: String)

                    @PersistenceMapping
                    data class ExplicitPrefixEntity(
                        override val id: Int,
                        @Embedded(prefix = "ART_") val performer: ArtistEmbeddable
                    ) : ReactiveEntityBase<Int, ExplicitPrefixEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ExplicitPrefixEntity(id, performer)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ExplicitPrefixEntity_LirpTableDef.kt")
        content shouldContain "name = \"ART_name\""
        content shouldContain "name = \"ART_country_code\""
    }

    "flattens @Embedded with empty-string prefix reverts to auto-derived default" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "EmptyPrefixEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class ArtistEmbeddable(val name: String, val countryCode: String)

                    @PersistenceMapping
                    data class EmptyPrefixEntity(
                        override val id: Int,
                        @Embedded(prefix = "") val performer: ArtistEmbeddable
                    ) : ReactiveEntityBase<Int, EmptyPrefixEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = EmptyPrefixEntity(id, performer)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("EmptyPrefixEntity_LirpTableDef.kt")
        content shouldContain "name = \"performer_name\""
        content shouldContain "name = \"performer_country_code\""
    }

    "flattens 2-level recursive @Embedded concatenating prefixes parent to child" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "TwoLevelEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class ArtistEmbeddable(val name: String, val countryCode: String)

                    @Embeddable
                    data class AlbumEmbeddable(
                        val name: String,
                        @Embedded val performer: ArtistEmbeddable
                    )

                    @PersistenceMapping
                    data class TwoLevelEntity(
                        override val id: Int,
                        @Embedded val album: AlbumEmbeddable
                    ) : ReactiveEntityBase<Int, TwoLevelEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = TwoLevelEntity(id, album)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("TwoLevelEntity_LirpTableDef.kt")
        content shouldContain "name = \"album_name\""
        content shouldContain "name = \"album_performer_name\""
        content shouldContain "name = \"album_performer_country_code\""
    }

    "flattens 3-level recursive @Embedded" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ThreeLevelEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class LevelThreeEmbeddable(val value: String)

                    @Embeddable
                    data class LevelTwoEmbeddable(@Embedded val level3: LevelThreeEmbeddable)

                    @Embeddable
                    data class LevelOneEmbeddable(@Embedded val level2: LevelTwoEmbeddable)

                    @PersistenceMapping
                    data class ThreeLevelEntity(
                        override val id: Int,
                        @Embedded val container: LevelOneEmbeddable
                    ) : ReactiveEntityBase<Int, ThreeLevelEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ThreeLevelEntity(id, container)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ThreeLevelEntity_LirpTableDef.kt")
        content shouldContain "name = \"container_level2_level3_value\""
    }

    "flattens @Embedded scalar leaf with @PersistenceProperty converter" {
        // lirp-ksp does NOT depend on lirp-sql, so the shared PathConverter test fixture is
        // unreachable here. Inline a minimal String→String converter as a stand-in; the codegen
        // path is identical regardless of the S/T types involved.
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ConverterInEmbeddableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object PathConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @Embeddable
                    data class MediaEmbeddable(
                        val name: String,
                        @PersistenceProperty(converter = PathConverter::class) val path: String
                    )

                    @PersistenceMapping
                    data class ConverterInEmbeddableEntity(
                        override val id: Int,
                        @Embedded val media: MediaEmbeddable
                    ) : ReactiveEntityBase<Int, ConverterInEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ConverterInEmbeddableEntity(id, media)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ConverterInEmbeddableEntity_LirpTableDef.kt")
        content shouldContain "name = \"media_path\""
        content shouldContain "test.PathConverter.fromSql("
        content shouldContain "test.PathConverter.toSql("
    }

    "reconstructs @Embedded value object in fromRow via nested constructor expression" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "FromRowShapeEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class ArtistEmbeddable(val name: String, val countryCode: String)

                    @PersistenceMapping
                    data class FromRowShapeEntity(
                        override val id: Int,
                        @Embedded val performer: ArtistEmbeddable
                    ) : ReactiveEntityBase<Int, FromRowShapeEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = FromRowShapeEntity(id, performer)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("FromRowShapeEntity_LirpTableDef.kt")
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        // The embeddable must be reconstructed via a nested constructor expression named after
        // its fully-qualified type. The exact argument shape (positional vs named) is allowed to
        // vary, but the constructor call FQN + the embeddable's parameter names must appear.
        fromRowBlock shouldContain "test.ArtistEmbeddable("
    }

    "reconstructs body-declared @Embedded var in fromRow, not only in applyScalarRow" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BodyEmbeddedEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class ArtistEmbeddable(val name: String, val countryCode: String)

                    @PersistenceMapping
                    class BodyEmbeddedEntity(override val id: Int) : ReactiveEntityBase<Int, BodyEmbeddedEntity>() {
                        @Embedded var performer: ArtistEmbeddable by reactiveProperty(ArtistEmbeddable("", ""))
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BodyEmbeddedEntity(id).also { it.performer = performer }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("BodyEmbeddedEntity_LirpTableDef.kt")
        // A standalone fromRow() (e.g. the conflict-recovery reload) must hydrate body-declared
        // @Embedded var properties, not leave them at their default. The reconstruction runs inside
        // the withEventsDisabled hydration block.
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        fromRowBlock shouldContain "entity.withEventsDisabled {"
        fromRowBlock shouldContain "entity.performer = test.ArtistEmbeddable("
    }

    "nested explicit prefix preserves ancestor prefix segment" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NestedExplicitPrefixEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class GeoCoord(val lat: Double, val lng: Double)

                    @Embeddable
                    data class AddressEmbeddable(
                        val street: String,
                        @Embedded(prefix = "geo_") val geo: GeoCoord
                    )

                    @PersistenceMapping
                    data class NestedExplicitPrefixEntity(
                        override val id: Int,
                        @Embedded val address: AddressEmbeddable
                    ) : ReactiveEntityBase<Int, NestedExplicitPrefixEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NestedExplicitPrefixEntity(id, address)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("NestedExplicitPrefixEntity_LirpTableDef.kt")
        content shouldContain "name = \"address_street\""
        // The explicit "geo_" prefix on the nested @Embedded overrides only the current
        // segment's auto-derived portion ("geo_"), while the ancestor prefix "address_" is
        // preserved — producing "address_geo_lat", not "geo_lat".
        content shouldContain "name = \"address_geo_lat\""
        content shouldContain "name = \"address_geo_lng\""
    }

    "@PersistenceIgnore non-null defaulted embeddable ctor param omitted from fromRow" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "IgnoredDefaultedParamEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceIgnore
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class TagEmbeddable(
                        val name: String,
                        @PersistenceIgnore val cachedHash: Int = 0
                    )

                    @PersistenceMapping
                    data class IgnoredDefaultedParamEntity(
                        override val id: Int,
                        @Embedded val tag: TagEmbeddable
                    ) : ReactiveEntityBase<Int, IgnoredDefaultedParamEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = IgnoredDefaultedParamEntity(id, tag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("IgnoredDefaultedParamEntity_LirpTableDef.kt")
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        // The persisted scalar must appear in the embeddable reconstruction expression.
        fromRowBlock shouldContain "name = "
        // The @PersistenceIgnore non-null defaulted param must be omitted entirely — neither named
        // nor passed null — so the default value applies at instantiation time.
        fromRowBlock shouldNotContain "cachedHash"
    }

    "top-level explicit prefix does not bleed into nested auto-derived prefix" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "TopLevelExplicitPrefixEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class InnerEmbeddable(val value: String)

                    @Embeddable
                    data class OuterEmbeddable(
                        val label: String,
                        @Embedded val inner: InnerEmbeddable
                    )

                    @PersistenceMapping
                    data class TopLevelExplicitPrefixEntity(
                        override val id: Int,
                        @Embedded(prefix = "custom_") val outer: OuterEmbeddable
                    ) : ReactiveEntityBase<Int, TopLevelExplicitPrefixEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = TopLevelExplicitPrefixEntity(id, outer)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("TopLevelExplicitPrefixEntity_LirpTableDef.kt")
        // The top-level explicit "custom_" prefix replaces the auto-derived "outer_" prefix;
        // the nested auto-derived prefix for "inner" appends onto "custom_", not "outer_".
        content shouldContain "name = \"custom_label\""
        content shouldContain "name = \"custom_inner_value\""
    }
})
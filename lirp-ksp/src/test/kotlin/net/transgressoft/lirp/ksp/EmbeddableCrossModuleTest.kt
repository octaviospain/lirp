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
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import com.tschuchort.compiletesting.symbolProcessorProviders
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Two-stage KSP compilation-testing fixture for cross-module `@Embeddable` annotation resolution.
 *
 * Each test compiles an `@Embeddable` value type in stage 1 (no KSP, producing compiled bytecode),
 * feeds that output directory into stage 2's classpath via [addPreviousResultToClasspath], then runs
 * KSP with [TableDefProcessorProvider] in stage 2. This wiring causes KSP to read the stage-1
 * classes with `Origin.KOTLIN_LIB`, where data-class constructor annotations are placed on the
 * `VALUE_PARAMETER` rather than the synthesized property — the real cross-module shape that a single
 * compilation cannot reproduce.
 *
 * The KOTLIN_LIB probe (the first test) de-risks the assumption that kctfork's classpath mechanism
 * produces genuine cross-module metadata before any behavioral test is trusted.
 */
@OptIn(ExperimentalCompilerApi::class)
class EmbeddableCrossModuleTest : StringSpec({

    // Stage 1: compile the @Embeddable with no KSP — produces KOTLIN_LIB metadata for stage 2
    fun compileEmbeddableLib(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        // No symbolProcessorProviders — intentionally no KSP so stage-2 reads this as KOTLIN_LIB
        return compilation.compile()
    }

    // Stage 2: compile the entity + KSP, with the embeddable lib on the classpath
    fun compileEntityWithProcessor(
        libResult: JvmCompilationResult,
        vararg sources: SourceFile
    ): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.addPreviousResultToClasspath(libResult) // feeds KOTLIN_LIB classes into KSP
        compilation.configureKsp { withCompilation = true }
        compilation.symbolProcessorProviders += TableDefProcessorProvider()
        return compilation.compile()
    }

    fun JvmCompilationResult.generatedFileContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error(
                    "Generated file '$name' not found among: " +
                        sourcesGeneratedBySymbolProcessor.map { it.name }.toList()
                )
        return file.readText()
    }

    // ── KOTLIN_LIB probe ────────────────────────────────────────────────────────────────────────
    // This is the de-risking probe for the cross-module annotation-resolution mechanism.
    //
    // A stage-1 @Embeddable carries @PersistenceProperty(converter = ...) on its ctor val. In a
    // classpath-supplied class (KOTLIN_LIB origin), Kotlin metadata places constructor annotations
    // on the VALUE_PARAMETER only — not on the synthesized property. If KSP were to read only
    // prop.annotations (the pre-fix path), it would miss the converter annotation entirely and
    // fall back to generating a plain String column with no converter round-trip calls.
    //
    // The assertion that the generated _LirpTableDef calls UriConverter.fromSql / UriConverter.toSql
    // is only satisfiable if resolvePersistenceAnnotations merged prop.annotations + ctorParam.annotations
    // and found the @PersistenceProperty(converter = ...) on the VALUE_PARAMETER. A passing assertion
    // here proves KOTLIN_LIB param-only placement was handled correctly.
    "cross-module @Embeddable flattens scalar fields using KOTLIN_LIB annotation resolution" {
        val libResult =
            compileEmbeddableLib(
                SourceFile.kotlin(
                    "Track.kt",
                    """
                    package lib
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object TrackIdConverter : ColumnConverter<Int, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: Int): String = value.toString()
                        override fun fromSql(raw: String): Int = raw.toInt()
                    }

                    @Embeddable
                    data class Track(
                        @PersistenceProperty(converter = TrackIdConverter::class) val externalId: Int,
                        val durationMs: Int
                    )
                    """
                )
            )
        libResult.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val entityResult =
            compileEntityWithProcessor(
                libResult,
                SourceFile.kotlin(
                    "Album.kt",
                    """
                    package test
                    import lib.Track
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class Album(
                        override val id: Int,
                        @Embedded val track: Track
                    ) : ReactiveEntityBase<Int, Album>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )
        entityResult.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = entityResult.generatedFileContent("Album_LirpTableDef.kt")
        // Converter round-trip calls are only emitted if KSP resolved @PersistenceProperty(converter=...)
        // from the VALUE_PARAMETER of the KOTLIN_LIB class (prop.annotations is empty for it).
        // A plain Int column with no converter would be generated if the annotation were missed.
        content shouldContain "lib.TrackIdConverter.fromSql("
        content shouldContain "lib.TrackIdConverter.toSql("
        content shouldContain "track_duration_ms"
    }

    // ── @PersistenceIgnore cross-module ─────────────────────────────────────────────────────────
    "cross-module @PersistenceIgnore on constructor parameter excludes field from generated columns" {
        val libResult =
            compileEmbeddableLib(
                SourceFile.kotlin(
                    "Coordinate.kt",
                    """
                    package lib
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.PersistenceIgnore

                    @Embeddable
                    data class Coordinate(
                        val latitude: Double,
                        @PersistenceIgnore val internalTag: String?
                    )
                    """
                )
            )
        libResult.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val entityResult =
            compileEntityWithProcessor(
                libResult,
                SourceFile.kotlin(
                    "Location.kt",
                    """
                    package test
                    import lib.Coordinate
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class Location(
                        override val id: Int,
                        @Embedded val position: Coordinate
                    ) : ReactiveEntityBase<Int, Location>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )
        entityResult.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = entityResult.generatedFileContent("Location_LirpTableDef.kt")
        content shouldContain "position_latitude"
        // @PersistenceIgnore on VALUE_PARAMETER of KOTLIN_LIB class must produce IgnoredCtorSlot
        // which emits null in fromRow but must not generate a ColumnDef for the ignored field
        content shouldNotContain "position_internal_tag"
        content shouldNotContain "\"internal_tag\""
        // IgnoredCtorSlot correctly emits null for the nullable ctor param in fromRow
        content shouldContain "internalTag = null"
    }

    // ── @PersistenceProperty(converter) cross-module leaf ───────────────────────────────────────
    "cross-module @PersistenceProperty(converter) at leaf resolves converter type in generated code" {
        val libResult =
            compileEmbeddableLib(
                SourceFile.kotlin(
                    "MediaFile.kt",
                    """
                    package lib
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object UriConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String): String = value
                        override fun fromSql(raw: String): String = raw
                    }

                    @Embeddable
                    data class MediaFile(
                        val name: String,
                        @PersistenceProperty(converter = UriConverter::class) val uri: String
                    )
                    """
                )
            )
        libResult.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val entityResult =
            compileEntityWithProcessor(
                libResult,
                SourceFile.kotlin(
                    "Catalogue.kt",
                    """
                    package test
                    import lib.MediaFile
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class Catalogue(
                        override val id: Int,
                        @Embedded val file: MediaFile
                    ) : ReactiveEntityBase<Int, Catalogue>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )
        entityResult.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = entityResult.generatedFileContent("Catalogue_LirpTableDef.kt")
        // The converter is found on the VALUE_PARAMETER of the KOTLIN_LIB class; if it were missed
        // the column would be generated as a plain String column without converter round-trip calls
        content shouldContain "\"file_uri\""
        content shouldContain "lib.UriConverter.fromSql("
        content shouldContain "lib.UriConverter.toSql("
    }

    // ── Cross-module @Embeddable with @PersistenceIgnore on its own ctor VALUE_PARAMETER (WR-01) ──
    // Proves that processCtorParam in EmbeddableAnalyzer forwards `param` to isExcluded so
    // @PersistenceIgnore on a cross-module @Embeddable VALUE_PARAMETER is detected at the
    // top-level entity's ctor-param dispatch (not just in buildEmbeddableChildSlot).
    // Stage 1 compiles an @Embeddable with @PersistenceIgnore on a nullable ctor param.
    // Stage 2 processes the entity and must exclude that column from the generated TableDef.
    "cross-module @Embeddable top-level ctor @PersistenceIgnore on VALUE_PARAMETER excludes column from generated TableDef" {
        val libResult =
            compileEmbeddableLib(
                SourceFile.kotlin(
                    "PluginLib.kt",
                    """
                    package lib
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.PersistenceIgnore

                    @Embeddable
                    data class Plugin(
                        val version: String,
                        @PersistenceIgnore val debugInfo: String?
                    )
                    """
                )
            )
        libResult.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val entityResult =
            compileEntityWithProcessor(
                libResult,
                SourceFile.kotlin(
                    "Extension.kt",
                    """
                    package test
                    import lib.Plugin
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class Extension(
                        override val id: Int,
                        @Embedded val plugin: Plugin
                    ) : ReactiveEntityBase<Int, Extension>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )
        entityResult.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = entityResult.generatedFileContent("Extension_LirpTableDef.kt")
        content shouldContain "plugin_version"
        // @PersistenceIgnore on VALUE_PARAMETER of KOTLIN_LIB @Embeddable must be excluded
        content shouldNotContain "plugin_debug_info"
        content shouldNotContain "\"debug_info\""
        // IgnoredCtorSlot emits null for the nullable param
        content shouldContain "debugInfo = null"
    }

    // ── Non-nullable no-default @PersistenceIgnore embeddable ctor param guard (CR-01) ──────────
    // A nullable @PersistenceIgnore embeddable ctor param is accepted (IgnoredCtorSlot emits null).
    // A non-nullable no-default @PersistenceIgnore ctor param must fail with a clear diagnostic.
    "cross-module @Embeddable with nullable @PersistenceIgnore ctor param produces valid TableDef" {
        val libResult =
            compileEmbeddableLib(
                SourceFile.kotlin(
                    "NullableIgnoreLib.kt",
                    """
                    package lib
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.PersistenceIgnore

                    @Embeddable
                    data class Metadata(
                        val title: String,
                        @PersistenceIgnore val optionalTag: String?
                    )
                    """
                )
            )
        libResult.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val entityResult =
            compileEntityWithProcessor(
                libResult,
                SourceFile.kotlin(
                    "Document.kt",
                    """
                    package test
                    import lib.Metadata
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class Document(
                        override val id: Int,
                        @Embedded val meta: Metadata
                    ) : ReactiveEntityBase<Int, Document>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )
        entityResult.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = entityResult.generatedFileContent("Document_LirpTableDef.kt")
        content shouldContain "meta_title"
        content shouldNotContain "optional_tag"
        // IgnoredCtorSlot emits null for the nullable param in the nested ctor expression
        content shouldContain "optionalTag = null"
    }

    "cross-module @Embeddable with non-nullable no-default @PersistenceIgnore ctor param fails with diagnostic" {
        val libResult =
            compileEmbeddableLib(
                SourceFile.kotlin(
                    "NonNullableIgnoreLib.kt",
                    """
                    package lib
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.PersistenceIgnore

                    @Embeddable
                    data class Config(
                        val name: String,
                        @PersistenceIgnore val requiredSecret: String
                    )
                    """
                )
            )
        libResult.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val entityResult =
            compileEntityWithProcessor(
                libResult,
                SourceFile.kotlin(
                    "Service.kt",
                    """
                    package test
                    import lib.Config
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class Service(
                        override val id: Int,
                        @Embedded val config: Config
                    ) : ReactiveEntityBase<Int, Service>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )
        // The guard rejects the non-nullable no-default ignored param and emits a KSP error,
        // which causes the overall compilation to fail.
        entityResult.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        entityResult.messages shouldContain "non-nullable and has no default value"
    }

    // ── ≥2-level nested cross-module @Embedded ──────────────────────────────────────────────────
    // This test covers the prefix-concatenation rule for a cross-module value graph.
    // Entity → Album → albumArtist: Artist / label: Label mirrors the music-commons shape without
    // literal coupling to that project's class names.
    "cross-module nested @Embedded two levels produces correctly prefixed column names" {
        val libResult =
            compileEmbeddableLib(
                SourceFile.kotlin(
                    "AlbumLib.kt",
                    """
                    package lib
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded

                    @Embeddable
                    data class Artist(val name: String, val countryCode: String)

                    @Embeddable
                    data class Label(val title: String, val countryCode: String)

                    @Embeddable
                    data class Album(
                        @Embedded val albumArtist: Artist,
                        @Embedded val label: Label
                    )
                    """
                )
            )
        libResult.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val entityResult =
            compileEntityWithProcessor(
                libResult,
                SourceFile.kotlin(
                    "Release.kt",
                    """
                    package test
                    import lib.Album
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class Release(
                        override val id: Int,
                        @Embedded val album: Album
                    ) : ReactiveEntityBase<Int, Release>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )
        // A clean exit confirms the nested cross-module embeddable is NOT rejected as a
        // custom-getter type: isSourceDeclaredCustomGetter uses Origin.KOTLIN, so KOTLIN_LIB
        // compiled accessors are accepted, not falsely flagged as custom getters.
        entityResult.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = entityResult.generatedFileContent("Release_LirpTableDef.kt")
        // Prefix concatenation: entity_field + _ + embeddable_field + _ + leaf_field
        content shouldContain "album_album_artist_name"
        content shouldContain "album_album_artist_country_code"
        content shouldContain "album_label_title"
        content shouldContain "album_label_country_code"
    }
})
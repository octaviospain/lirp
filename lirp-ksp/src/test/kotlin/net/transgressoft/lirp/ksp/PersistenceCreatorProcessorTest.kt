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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * KSP compilation tests for the `@PersistenceCreator` code-generation path in
 * [TableDefProcessor] and [EmbeddableAnalyzer]: companion-factory and secondary-constructor
 * emission, defaulted-parameter omission, creator precedence over a public primary constructor,
 * the multiple-creator and unmatched-parameter hard errors, primary-constructor fallback with a
 * warning, and the internal-entity non-public-creator warning.
 */
@OptIn(ExperimentalCompilerApi::class)
class PersistenceCreatorProcessorTest : StringSpec({

    // flyweight @Embeddable (internal ctor) reconstructed via its companion @PersistenceCreator
    "flyweight @Embeddable with internal ctor uses companion factory in fromRow" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "FlyweightArtistEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class Artist internal constructor(val name: String, val countryCode: String) {
                        companion object {
                            @PersistenceCreator
                            fun of(name: String, countryCode: String): Artist = Artist(name, countryCode)
                        }
                    }

                    @PersistenceMapping
                    data class FlyweightArtistEntity(
                        override val id: Int,
                        @Embedded val artist: Artist
                    ) : ReactiveEntityBase<Int, FlyweightArtistEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = FlyweightArtistEntity(id, artist)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("FlyweightArtistEntity_LirpTableDef.kt")
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        fromRowBlock shouldContain "Artist.of(name = "
        fromRowBlock shouldNotContain "Artist(name = "
    }

    // internal entity reconstructed via a public secondary-constructor @PersistenceCreator
    "internal entity uses public secondary ctor @PersistenceCreator in fromRow" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "InternalTrackEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    internal data class InternalTrackEntity internal constructor(
                        override val id: Int,
                        val title: String,
                        val durationMs: Long
                    ) : ReactiveEntityBase<Int, InternalTrackEntity>() {
                        @PersistenceCreator
                        constructor(id: Int, title: String) : this(id, title, 0L)
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = InternalTrackEntity(id, title, durationMs)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InternalTrackEntity_LirpTableDef.kt")
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        // The creator takes only (id, title); the call must be by NAMED args limited to those two so
        // it binds to the secondary @PersistenceCreator (not positionally to the 3-arg primary ctor).
        // durationMs is omitted entirely — it has the default 0L.
        fromRowBlock shouldContain "InternalTrackEntity(id = "
        fromRowBlock shouldContain "title = "
        fromRowBlock shouldNotContain "durationMs"
    }

    // a creator taking a reordered subset of the entity's params binds by name, not positionally
    "entity @PersistenceCreator with reordered subset binds by named args" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ReorderedCreatorEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class ReorderedCreatorEntity(
                        override val id: Int,
                        val first: String,
                        val second: String
                    ) : ReactiveEntityBase<Int, ReorderedCreatorEntity>() {
                        companion object {
                            // Declared in (second, id, first) order — deliberately not the ctor order.
                            @PersistenceCreator
                            fun of(second: String, id: Int, first: String): ReorderedCreatorEntity =
                                ReorderedCreatorEntity(id, first, second)
                        }
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ReorderedCreatorEntity(id, first, second)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ReorderedCreatorEntity_LirpTableDef.kt")
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        // Args emitted in the creator's declared order (second, id, first), each named.
        fromRowBlock shouldContain "ReorderedCreatorEntity.of(second = "
        val secondIdx = fromRowBlock.indexOf("second = ")
        val idIdx = fromRowBlock.indexOf("id = ")
        val firstIdx = fromRowBlock.indexOf("first = ")
        (secondIdx < idIdx && idIdx < firstIdx) shouldBe true
    }

    // an internal entity whose @PersistenceCreator is a non-public SECONDARY CONSTRUCTOR warns
    "internal entity with non-public secondary-ctor creator warns only" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "InternalSecondaryCtorEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    internal data class InternalSecondaryCtorEntity internal constructor(
                        override val id: Int,
                        val name: String,
                        val extra: Int
                    ) : ReactiveEntityBase<Int, InternalSecondaryCtorEntity>() {
                        @PersistenceCreator
                        internal constructor(id: Int, name: String) : this(id, name, 0)
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = InternalSecondaryCtorEntity(id, name, extra)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.messages shouldContain "not public"
    }

    // a creator referencing a @PersistenceIgnore'd (unmapped) ctor param without a default errors
    "entity creator param bound to an excluded ctor param produces compilation error" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ExcludedParamCreatorEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceIgnore
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class ExcludedParamCreatorEntity(
                        override val id: Int,
                        @PersistenceIgnore val transientNote: String
                    ) : ReactiveEntityBase<Int, ExcludedParamCreatorEntity>() {
                        companion object {
                            @PersistenceCreator
                            fun of(id: Int, transientNote: String): ExcludedParamCreatorEntity =
                                ExcludedParamCreatorEntity(id, transientNote)
                        }
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ExcludedParamCreatorEntity(id, transientNote)
                    }
                    """
                )
            )

        result.exitCode shouldNotBe KotlinCompilation.ExitCode.OK
        result.messages shouldContain "transientNote"
        result.messages shouldContain "no mapped column source"
    }

    // @PersistenceIgnore non-null defaulted embeddable ctor param omitted from fromRow
    "@PersistenceIgnore non-null param with default is omitted from fromRow" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ConfigEmbeddableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceIgnore
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class Config(
                        val host: String,
                        @PersistenceIgnore val timeout: Int = 5000
                    )

                    @PersistenceMapping
                    data class ConfigEmbeddableEntity(
                        override val id: Int,
                        @Embedded val config: Config
                    ) : ReactiveEntityBase<Int, ConfigEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ConfigEmbeddableEntity(id, config)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("ConfigEmbeddableEntity_LirpTableDef.kt")
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        fromRowBlock shouldContain "host = "
        fromRowBlock shouldNotContain "timeout = "
        fromRowBlock shouldNotContain "timeout = null"
    }

    // @PersistenceCreator wins over a public primary constructor
    "@PersistenceCreator wins over a public primary ctor" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "PublicCtorWithCreatorEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class Label(val name: String, val country: String) {
                        companion object {
                            @PersistenceCreator
                            fun of(name: String, country: String): Label = Label(name, country)
                        }
                    }

                    @PersistenceMapping
                    data class PublicCtorWithCreatorEntity(
                        override val id: Int,
                        @Embedded val label: Label
                    ) : ReactiveEntityBase<Int, PublicCtorWithCreatorEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = PublicCtorWithCreatorEntity(id, label)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PublicCtorWithCreatorEntity_LirpTableDef.kt")
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        // The companion factory wins even though the primary ctor is also public.
        fromRowBlock shouldContain "Label.of("
        fromRowBlock shouldNotContain "Label(name = "
    }

    // multiple @PersistenceCreator on the same type produces a compilation error
    "multiple @PersistenceCreator on same type produces compilation error" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "AmbiguousCreatorEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class AmbiguousCreatorEntity(
                        override val id: Int,
                        val name: String
                    ) : ReactiveEntityBase<Int, AmbiguousCreatorEntity>() {
                        @PersistenceCreator
                        constructor(id: Int) : this(id, "")
                        companion object {
                            @PersistenceCreator
                            fun of(id: Int, name: String): AmbiguousCreatorEntity = AmbiguousCreatorEntity(id, name)
                        }
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AmbiguousCreatorEntity(id, name)
                    }
                    """
                )
            )

        result.exitCode shouldNotBe KotlinCompilation.ExitCode.OK
        result.messages shouldContain "Multiple @PersistenceCreator"
    }

    // unmatched creator param without a default produces a compilation error
    "unmatched creator param without default produces compilation error" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "UnmatchedParamEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class UnmatchedParamEntity(
                        override val id: Int,
                        val title: String
                    ) : ReactiveEntityBase<Int, UnmatchedParamEntity>() {
                        companion object {
                            @PersistenceCreator
                            fun of(id: Int, title: String, unmappedExtra: String): UnmatchedParamEntity =
                                UnmatchedParamEntity(id, title)
                        }
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = UnmatchedParamEntity(id, title)
                    }
                    """
                )
            )

        result.exitCode shouldNotBe KotlinCompilation.ExitCode.OK
        result.messages shouldContain "unmappedExtra"
    }

    // non-public primary ctor without a creator falls back to the primary ctor and warns
    "non-public primary ctor without creator falls back to primary ctor with warning" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NonPublicCtorEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class NonPublicCtorEntity internal constructor(
                        override val id: Int,
                        val name: String
                    ) : ReactiveEntityBase<Int, NonPublicCtorEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NonPublicCtorEntity(id, name)
                    }
                    """
                )
            )

        // Codegen continues (fallback to primary ctor); the warn path does not abort.
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("NonPublicCtorEntity_LirpTableDef.kt")
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        // Bare ctor call is used (no creator, so className is the call target).
        fromRowBlock shouldContain "NonPublicCtorEntity("
        // The warn message is emitted about the non-public ctor.
        result.messages shouldContain "non-public primary constructor"
    }

    // internal entity with a non-public resolved creator warns only (no error; codegen proceeds)
    "internal entity with non-public resolved creator warns only" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "InternalEntityInternalCreator.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    internal data class InternalEntityInternalCreator(
                        override val id: Int,
                        val value: String
                    ) : ReactiveEntityBase<Int, InternalEntityInternalCreator>() {
                        companion object {
                            @PersistenceCreator
                            internal fun create(id: Int, value: String): InternalEntityInternalCreator =
                                InternalEntityInternalCreator(id, value)
                        }
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = InternalEntityInternalCreator(id, value)
                    }
                    """
                )
            )

        // Warn only — codegen proceeds, no error.
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InternalEntityInternalCreator_LirpTableDef.kt")
        content shouldContain "InternalEntityInternalCreator"
        result.messages shouldContain "not public"
    }

    // Gap A + Gap B regression: a top-level body-declared @Embedded var whose @Embeddable has a
    // companion @PersistenceCreator must reconstruct through the factory (not the internal primary
    // ctor), and the factory reference must be fully qualified so the generated descriptor — emitted
    // into a different package without an import — compiles.
    "body-declared @Embedded var uses fully-qualified companion factory in fromRow" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BodyFlyweightEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class FlyLabel internal constructor(val name: String, val countryCode: String) {
                        companion object {
                            @PersistenceCreator
                            fun of(name: String, countryCode: String): FlyLabel = FlyLabel(name, countryCode)
                        }
                    }

                    @PersistenceMapping
                    class BodyFlyweightEntity(override val id: Int) : ReactiveEntityBase<Int, BodyFlyweightEntity>() {
                        @Embedded var label: FlyLabel by reactiveProperty(FlyLabel.of("", ""))
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BodyFlyweightEntity(id).also { it.label = label }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("BodyFlyweightEntity_LirpTableDef.kt")
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        // Gap A: setter reconstruction routes through the factory, not the internal primary ctor.
        fromRowBlock shouldContain "entity.label = test.FlyLabel.of(name = "
        // Gap B: the factory owner is fully qualified (no bare `FlyLabel.of` that would be unresolved).
        fromRowBlock shouldNotContain "entity.label = FlyLabel.of"
        fromRowBlock shouldNotContain "entity.label = test.FlyLabel(name = "
    }

    // multiple @PersistenceCreator on a nested @Embeddable produces a compilation error naming both
    "multiple @PersistenceCreator on an @Embeddable produces compilation error" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "AmbiguousEmbeddableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class Coord internal constructor(val lat: Double, val lng: Double) {
                        companion object {
                            @PersistenceCreator fun of(lat: Double, lng: Double): Coord = Coord(lat, lng)
                            @PersistenceCreator fun create(lat: Double, lng: Double): Coord = Coord(lat, lng)
                        }
                    }

                    @PersistenceMapping
                    data class AmbiguousEmbeddableEntity(
                        override val id: Int,
                        @Embedded val coord: Coord
                    ) : ReactiveEntityBase<Int, AmbiguousEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AmbiguousEmbeddableEntity(id, coord)
                    }
                    """
                )
            )

        result.exitCode shouldNotBe KotlinCompilation.ExitCode.OK
        result.messages shouldContain "Multiple @PersistenceCreator"
        result.messages shouldContain "of"
        result.messages shouldContain "create"
    }

    // a creator param on an @Embeddable with no mapped column and no default is a compilation error
    "unmatched @Embeddable creator param without default produces compilation error" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "UnmatchedEmbeddableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class Money(val amount: Long) {
                        companion object {
                            @PersistenceCreator
                            fun of(amount: Long, currency: String): Money = Money(amount)
                        }
                    }

                    @PersistenceMapping
                    data class UnmatchedEmbeddableEntity(
                        override val id: Int,
                        @Embedded val price: Money
                    ) : ReactiveEntityBase<Int, UnmatchedEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = UnmatchedEmbeddableEntity(id, price)
                    }
                    """
                )
            )

        result.exitCode shouldNotBe KotlinCompilation.ExitCode.OK
        result.messages shouldContain "currency"
        result.messages shouldContain "no mapped column source"
    }

    // an internal @Embeddable whose resolved creator is not public warns only; codegen proceeds
    "internal @Embeddable with non-public creator warns only" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "InternalEmbeddableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceCreator
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    internal data class Tag internal constructor(val value: String) {
                        companion object {
                            @PersistenceCreator internal fun of(value: String): Tag = Tag(value)
                        }
                    }

                    @PersistenceMapping
                    internal data class InternalEmbeddableEntity(
                        override val id: Int,
                        @Embedded val tag: Tag
                    ) : ReactiveEntityBase<Int, InternalEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = InternalEmbeddableEntity(id, tag)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.messages shouldContain "not public"
        val content = result.generatedFileContent("InternalEmbeddableEntity_LirpTableDef.kt")
        content.substringAfter("override fun fromRow") shouldContain "test.Tag.of(value = "
    }

    // an @Embeddable with a non-public primary ctor and no creator falls back and warns
    "@Embeddable with non-public primary ctor and no creator falls back with warning" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NoCreatorEmbeddableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class Ratio internal constructor(val numerator: Int, val denominator: Int)

                    @PersistenceMapping
                    data class NoCreatorEmbeddableEntity(
                        override val id: Int,
                        @Embedded val ratio: Ratio
                    ) : ReactiveEntityBase<Int, NoCreatorEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NoCreatorEmbeddableEntity(id, ratio)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.messages shouldContain "non-public primary constructor and no"
        val content = result.generatedFileContent("NoCreatorEmbeddableEntity_LirpTableDef.kt")
        content.substringAfter("override fun fromRow") shouldContain "test.Ratio(numerator = "
    }
})
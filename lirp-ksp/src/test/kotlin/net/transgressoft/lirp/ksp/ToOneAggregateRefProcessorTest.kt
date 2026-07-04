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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for `@ToOneAggregate` handling in [ReactiveEntityRefProcessor].
 *
 * Verifies RefEntry code generation, nullability inference for optional vs required FK references,
 * and the four compile-time diagnostics emitted for misuse.
 *
 * Each test compiles a minimal inline entity pair (owner + target) so that KSP type resolution
 * can find the target class in the same compilation unit.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("ToOneAggregateRefProcessor")
internal class ToOneAggregateRefProcessorTest : FunSpec({

    val vehicleAndCompanySource =
        SourceFile.kotlin(
            "VehicleEntity.kt",
            """
            package test
            import net.transgressoft.lirp.entity.ReactiveEntityBase
            import net.transgressoft.lirp.persistence.PersistenceMapping
            import net.transgressoft.lirp.persistence.ToOneAggregate
            import java.util.UUID

            @PersistenceMapping
            class Company(override val id: UUID) : ReactiveEntityBase<UUID, Company>() {
                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = Company(id)
            }

            @PersistenceMapping
            class Vehicle(override val id: UUID, companyId: UUID?) : ReactiveEntityBase<UUID, Vehicle>() {
                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = Vehicle(id, companyId)

                @ToOneAggregate(target = Company::class)
                var companyId: UUID? by reactiveProperty(companyId)
            }
            """
        )

    test("ReactiveEntityRefProcessor generates RefEntry for entity with @ToOneAggregate scalar") {
        val result = KspTestSupport.compile(ReactiveEntityRefProcessorProvider(), vehicleAndCompanySource)

        val content = result.shouldSucceed().generatedFileContent("Vehicle_LirpRefAccessor.kt")
        content.shouldContainEach(
            "refName = \"company\"",
            "idGetter = { it.companyId as Comparable<Any>? }",
            "referencedClass = Company::class.java",
            "cascadeAction = CascadeAction.DETACH",
            "bubbleUp = false",
            "getOrComputeToOneRef(\"company\""
        )
    }

    test("ReactiveEntityRefProcessor generates RefEntry with bubbleUp=true when specified") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "AlbumWithArtist.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate

                    @PersistenceMapping
                    class Artist(override val id: Int) : ReactiveEntityBase<Int, Artist>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Artist(id)
                    }

                    @PersistenceMapping
                    class Album(override val id: Int, artistId: Int) : ReactiveEntityBase<Int, Album>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Album(id, artistId)

                        @ToOneAggregate(target = Artist::class, bubbleUp = true)
                        var artistId: Int by reactiveProperty(artistId)
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Album_LirpRefAccessor.kt")
        content.shouldContainEach(
            "refName = \"artist\"",
            "bubbleUp = true"
        )
    }

    test("ReactiveEntityRefProcessor infers optional reference from nullable scalar") {
        val result = KspTestSupport.compile(ReactiveEntityRefProcessorProvider(), vehicleAndCompanySource)

        val content = result.shouldSucceed().generatedFileContent("Vehicle_LirpRefAccessor.kt")
        // Nullable scalar UUID? — idGetter uses null-safe cast so null FK returns null (not NPE)
        content shouldContain "idGetter = { it.companyId as Comparable<Any>? }"
    }

    test("ReactiveEntityRefProcessor infers required reference from non-nullable scalar") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "TrackEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate

                    @PersistenceMapping
                    class Playlist(override val id: Int) : ReactiveEntityBase<Int, Playlist>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Playlist(id)
                    }

                    @PersistenceMapping
                    class Track(override val id: Int, playlistId: Int) : ReactiveEntityBase<Int, Track>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Track(id, playlistId)

                        @ToOneAggregate(target = Playlist::class)
                        var playlistId: Int by reactiveProperty(playlistId)
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Track_LirpRefAccessor.kt")
        content.shouldContainEach(
            "refName = \"playlist\"",
            "idGetter = { it.playlistId as Comparable<Any> }"
        )
    }

    test("ReactiveEntityRefProcessor emits diagnostic (a) when target lacks @PersistenceMapping") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "BadTargetEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate

                    data class NotAnEntity(val id: Int)

                    @PersistenceMapping
                    class Owner(override val id: Int, notEntityId: Int) : ReactiveEntityBase<Int, Owner>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Owner(id, notEntityId)

                        @ToOneAggregate(target = NotAnEntity::class)
                        var notEntityId: Int by reactiveProperty(notEntityId)
                    }
                    """
                ),
                withCompilation = false
            )

        result.messages shouldContain "@ToOneAggregate 'target' 'NotAnEntity' must be annotated with @PersistenceMapping"
    }

    test("ReactiveEntityRefProcessor emits diagnostic (b) when scalar type does not match target PK") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "TypeMismatchEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import java.util.UUID

                    @PersistenceMapping
                    class Label(override val id: UUID) : ReactiveEntityBase<UUID, Label>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Label(id)
                    }

                    @PersistenceMapping
                    class Recording(override val id: Int, labelId: Int) : ReactiveEntityBase<Int, Recording>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Recording(id, labelId)

                        @ToOneAggregate(target = Label::class)
                        var labelId: Int by reactiveProperty(labelId)
                    }
                    """
                ),
                withCompilation = false
            )

        result.messages shouldContain "does not match target PK type"
    }

    test("ReactiveEntityRefProcessor emits diagnostic (c) when @ToOneAggregate placed on collection property") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "CollectionMisuse.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate

                    @PersistenceMapping
                    class Genre(override val id: Int) : ReactiveEntityBase<Int, Genre>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Genre(id)
                    }

                    @PersistenceMapping
                    class AudioLibrary(override val id: Int) : ReactiveEntityBase<Int, AudioLibrary>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioLibrary(id)

                        @ToOneAggregate(target = Genre::class)
                        val genreId: List<Int> = emptyList()
                    }
                    """
                ),
                withCompilation = false
            )

        result.messages shouldContain "must not be placed on a collection-typed property"
    }

    test("ReactiveEntityRefProcessor emits diagnostic (d) when scalar name has no Id suffix") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "NoSuffixEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate

                    @PersistenceMapping
                    class Publisher(override val id: Int) : ReactiveEntityBase<Int, Publisher>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Publisher(id)
                    }

                    @PersistenceMapping
                    class Book(override val id: Int, publisher: Int) : ReactiveEntityBase<Int, Book>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Book(id, publisher)

                        @ToOneAggregate(target = Publisher::class)
                        var publisher: Int by reactiveProperty(publisher)
                    }
                    """
                ),
                withCompilation = false
            )

        result.messages shouldContain "scalar name has no 'Id' suffix"
    }

    test("ReactiveEntityRefProcessor emits diagnostic (d) when scalar is named exactly 'Id'") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "ExactlyIdEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import java.util.UUID

                    @PersistenceMapping
                    class Studio(override val id: UUID) : ReactiveEntityBase<UUID, Studio>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Studio(id)
                    }

                    @PersistenceMapping
                    class Recording(override val id: UUID, Id: UUID?) : ReactiveEntityBase<UUID, Recording>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Recording(id, Id)

                        @ToOneAggregate(target = Studio::class)
                        var Id: UUID? by reactiveProperty(Id)
                    }
                    """
                ),
                withCompilation = false
            )

        // A scalar named exactly "Id" produces an empty accessor name — must emit diagnostic (d),
        // not emit a broken accessor with an empty property name.
        result.messages shouldContain "scalar name has no 'Id' suffix"
    }

    test("ReactiveEntityRefProcessor emits null-safe idGetter for optional FK scalar") {
        val result = KspTestSupport.compile(ReactiveEntityRefProcessorProvider(), vehicleAndCompanySource)

        val content = result.shouldSucceed().generatedFileContent("Vehicle_LirpRefAccessor.kt")
        // Optional scalar UUID? must use null-safe cast so idGetter returns null for null FK
        content shouldContain "idGetter = { it.companyId as Comparable<Any>? }"
    }

    test("ReactiveEntityRefProcessor emits non-null idGetter for required FK scalar") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "RequiredFkEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate

                    @PersistenceMapping
                    class Venue(override val id: Int) : ReactiveEntityBase<Int, Venue>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Venue(id)
                    }

                    @PersistenceMapping
                    class Concert(override val id: Int, venueId: Int) : ReactiveEntityBase<Int, Concert>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Concert(id, venueId)

                        @ToOneAggregate(target = Venue::class)
                        var venueId: Int by reactiveProperty(venueId)
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Concert_LirpRefAccessor.kt")
        // Non-nullable scalar Int must use non-null cast
        content shouldContain "idGetter = { it.venueId as Comparable<Any> }"
    }

    test("ReactiveEntityRefProcessor generates RefEntry for @ToOneAggregate on aggregate{} delegate val") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "PlaylistWithDelegateRef.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    @PersistenceMapping
                    class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioTrack(id)
                    }

                    @PersistenceMapping
                    class AudioPlaylist(
                        override val id: Int,
                        var audioTrackId: Int
                    ) : ReactiveEntityBase<Int, AudioPlaylist>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioPlaylist(id, audioTrackId)

                        @ToOneAggregate(target = AudioTrack::class)
                        val audioTrack by aggregate<Int, AudioTrack> { audioTrackId }
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("AudioPlaylist_LirpRefAccessor.kt")
        content.shouldContainEach(
            "refName = \"audioTrack\"",
            "idGetter = { it.audioTrack.referenceId }",
            "delegateGetter = { it.audioTrack as AggregateRefDelegate<*, *> }",
            "referencedClass = AudioTrack::class.java",
            "bubbleUp = false",
            "cascadeAction = CascadeAction.DETACH"
        )
    }

    test("ReactiveEntityRefProcessor does not generate extension accessor for @ToOneAggregate delegate val") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "PlaylistNoDelegateNav.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    @PersistenceMapping
                    class AudioItem(override val id: Int) : ReactiveEntityBase<Int, AudioItem>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioItem(id)
                    }

                    @PersistenceMapping
                    class TrackPlaylist(
                        override val id: Int,
                        var audioItemId: Int
                    ) : ReactiveEntityBase<Int, TrackPlaylist>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = TrackPlaylist(id, audioItemId)

                        @ToOneAggregate(target = AudioItem::class)
                        val audioItem by aggregate<Int, AudioItem> { audioItemId }
                    }
                    """
                )
            )

        result.shouldSucceed()
        // No _LirpToOneExtAccessor file should be generated for delegate-val form
        val generatedNames = result.generatedNames()
        generatedNames shouldNotContain "TrackPlaylist_LirpToOneExtAccessor.kt"
        generatedNames.find { it == "TrackPlaylist_LirpRefAccessor.kt" }.shouldNotBeNull()
    }

    test("ReactiveEntityRefProcessor does NOT require Id suffix for @ToOneAggregate delegate val") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "PlaylistNonIdSuffix.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    @PersistenceMapping
                    class AudioItem(override val id: Int) : ReactiveEntityBase<Int, AudioItem>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioItem(id)
                    }

                    @PersistenceMapping
                    class MusicLibrary(
                        override val id: Int,
                        var featuredItemId: Int
                    ) : ReactiveEntityBase<Int, MusicLibrary>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = MusicLibrary(id, featuredItemId)

                        @ToOneAggregate(target = AudioItem::class)
                        val featuredItem by aggregate<Int, AudioItem> { featuredItemId }
                    }
                    """
                )
            )

        // Must compile without error (no Id suffix required for delegate-val form)
        val content = result.shouldSucceed().generatedFileContent("MusicLibrary_LirpRefAccessor.kt")
        content shouldContain "refName = \"featuredItem\""
    }

    test("ReactiveEntityRefProcessor generates RefEntry with bubbleUp=true for @ToOneAggregate delegate val") {
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "BubblePlaylist.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    @PersistenceMapping
                    class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioTrack(id)
                    }

                    @PersistenceMapping
                    class BubblePlaylist(
                        override val id: Int,
                        var trackId: Int
                    ) : ReactiveEntityBase<Int, BubblePlaylist>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BubblePlaylist(id, trackId)

                        @ToOneAggregate(target = AudioTrack::class, bubbleUp = true)
                        val track by aggregate<Int, AudioTrack> { trackId }
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("BubblePlaylist_LirpRefAccessor.kt")
        content.shouldContainEach(
            "bubbleUp = true",
            "refName = \"track\"",
            "idGetter = { it.track.referenceId }"
        )
    }

    test("ReactiveEntityRefProcessor emits error when @ToOneAggregate target mismatches delegate entity type") {
        // Regression: the delegate-val branch previously trusted target= without verifying it
        // against the actual entity type argument of aggregate<K, E>. A mismatch would silently
        // generate a RefEntry wired to the wrong class.
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "MismatchedTargetPlaylist.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    @PersistenceMapping
                    class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioTrack(id)
                    }

                    @PersistenceMapping
                    class AudioItem(override val id: Int) : ReactiveEntityBase<Int, AudioItem>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioItem(id)
                    }

                    @PersistenceMapping
                    class MismatchedPlaylist(
                        override val id: Int,
                        var trackId: Int
                    ) : ReactiveEntityBase<Int, MismatchedPlaylist>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = MismatchedPlaylist(id, trackId)

                        // target says AudioItem but delegate type argument is AudioTrack — mismatch
                        @ToOneAggregate(target = AudioItem::class)
                        val track by aggregate<Int, AudioTrack> { trackId }
                    }
                    """
                ),
                withCompilation = false
            )

        result.messages shouldContain "does not match the delegate's entity type"
        result.messages shouldContain "AudioItem"
        result.messages shouldContain "AudioTrack"
    }

    test("ReactiveEntityRefProcessor keeps a scalar FK scalar when immediately followed by a delegate-val aggregate property") {
        // Regression: detection previously scanned trailing lines, so a scalar FK whose declaration is
        // immediately followed by `val x by aggregate<...> { ... }` had that next line pulled in and was
        // misclassified as a delegate-val — generating delegate code (`it.companyId.referenceId`) against
        // a plain scalar. The scalar must keep its scalar idGetter while the delegate-val keeps its own.
        val result =
            KspTestSupport.compile(
                ReactiveEntityRefProcessorProvider(),
                SourceFile.kotlin(
                    "AdjacentRefsEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    @PersistenceMapping
                    class Company(override val id: Int) : ReactiveEntityBase<Int, Company>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Company(id)
                    }

                    @PersistenceMapping
                    class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioTrack(id)
                    }

                    @PersistenceMapping
                    class Vehicle(
                        override val id: Int,
                        companyId: Int,
                        var audioTrackId: Int
                    ) : ReactiveEntityBase<Int, Vehicle>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Vehicle(id, companyId, audioTrackId)

                        @ToOneAggregate(target = Company::class)
                        var companyId: Int by reactiveProperty(companyId)
                        @ToOneAggregate(target = AudioTrack::class)
                        val audioTrack by aggregate<Int, AudioTrack> { audioTrackId }
                    }
                    """
                )
            )

        val content = result.shouldSucceed().generatedFileContent("Vehicle_LirpRefAccessor.kt")
        // companyId stays a scalar FK: scalar cast idGetter, not the delegate's `referenceId` form
        // (a misclassification would emit `idGetter = { it.companyId.referenceId }` instead).
        // audioTrack is correctly the delegate-val.
        content.shouldContainEach(
            "refName = \"company\"",
            "idGetter = { it.companyId as Comparable<Any> }",
            "refName = \"audioTrack\"",
            "idGetter = { it.audioTrack.referenceId }"
        )
    }

    test("delegate-val detection regex matches every by-delegation form, with or without a space before the brace") {
        // mutableAggregate is a forward-looking guard (no single-ref factory exists yet), so its
        // detection — including the spaced `mutableAggregate {` form missed by the old substring check
        // — is verified directly against the regex rather than through a compilation that cannot exist.
        val delegateForms =
            listOf(
                "val x by aggregate<Int, X> { xId }",
                "val x by aggregate { xId }",
                "val x by optionalAggregate<Int, X> { xId }",
                "val x by optionalAggregate { xId }",
                "val x by mutableAggregate<Int, X> { xId }",
                "val x by mutableAggregate { xId }",
                "val x by mutableAggregate{ xId }"
            )
        delegateForms.forEach { it to TO_ONE_DELEGATE_VAL_REGEX.containsMatchIn(it) shouldBe (it to true) }

        val scalarForms =
            listOf(
                "var companyId: Int by reactiveProperty(companyId)",
                "var companyId: Int? by reactiveProperty(companyId)",
                "val items by aggregateList<Int, X>(itemIds)"
            )
        scalarForms.forEach { it to TO_ONE_DELEGATE_VAL_REGEX.containsMatchIn(it) shouldBe (it to false) }
    }
})
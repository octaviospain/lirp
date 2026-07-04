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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for polymorphic aggregate codegen, verifying that both
 * [TableDefProcessor] and [ReactiveEntityRefProcessor] emit the correct per-arm metadata
 * for `polymorphicAggregate(arm<K,E>("label") { scalar }, …)` declarations.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("PolymorphicRefProcessor")
internal class PolymorphicRefProcessorTest : FunSpec({

    // Two-arm entity source used across multiple tests.
    // Constructor params are named to match the reactive property names so all params map
    // to columns, keeping unmappedCtorParams empty and enabling SqlTableDef (with foreignKeys()).
    val twoArmEntitySource =
        SourceFile.kotlin(
            "AudioContribution.kt",
            """
            package test

            import net.transgressoft.lirp.entity.ReactiveEntityBase
            import net.transgressoft.lirp.persistence.PersistenceMapping
            import net.transgressoft.lirp.persistence.polymorphicAggregate
            import net.transgressoft.lirp.persistence.arm

            @PersistenceMapping
            data class AudioItem(
                override val id: Int,
                val title: String
            ) : ReactiveEntityBase<Int, AudioItem>() {
                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = copy()
            }

            @PersistenceMapping
            data class MutableAudioPlaylist(
                override val id: Int,
                val name: String
            ) : ReactiveEntityBase<Int, MutableAudioPlaylist>() {
                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = copy()
            }

            @PersistenceMapping
            class AudioContribution(
                override val id: Int,
                audioItemId: Int? = null,
                audioPlaylistId: Int? = null
            ) : ReactiveEntityBase<Int, AudioContribution>() {
                var audioItemId: Int? by reactiveProperty(audioItemId)
                var audioPlaylistId: Int? by reactiveProperty(audioPlaylistId)

                val target by polymorphicAggregate(
                    arm<Int, AudioItem>("item") { audioItemId },
                    arm<Int, MutableAudioPlaylist>("playlist") { audioPlaylistId }
                )

                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = AudioContribution(id, audioItemId, audioPlaylistId)
            }
            """
        )

    // Three-arm entity source used for arity > 2 tests.
    val threeArmEntitySource =
        SourceFile.kotlin(
            "ThreeArmContribution.kt",
            """
            package test

            import net.transgressoft.lirp.entity.ReactiveEntityBase
            import net.transgressoft.lirp.persistence.PersistenceMapping
            import net.transgressoft.lirp.persistence.polymorphicAggregate
            import net.transgressoft.lirp.persistence.arm

            @PersistenceMapping
            data class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = copy()
            }

            @PersistenceMapping
            data class AudioAlbum(override val id: Int) : ReactiveEntityBase<Int, AudioAlbum>() {
                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = copy()
            }

            @PersistenceMapping
            data class AudioArtist(override val id: Int) : ReactiveEntityBase<Int, AudioArtist>() {
                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = copy()
            }

            @PersistenceMapping
            class ThreeArmContribution(
                override val id: Int,
                audioTrackId: Int? = null,
                audioAlbumId: Int? = null,
                audioArtistId: Int? = null
            ) : ReactiveEntityBase<Int, ThreeArmContribution>() {
                var audioTrackId: Int? by reactiveProperty(audioTrackId)
                var audioAlbumId: Int? by reactiveProperty(audioAlbumId)
                var audioArtistId: Int? by reactiveProperty(audioArtistId)

                val ref by polymorphicAggregate(
                    arm<Int, AudioTrack>("alpha") { audioTrackId },
                    arm<Int, AudioAlbum>("beta") { audioAlbumId },
                    arm<Int, AudioArtist>("gamma") { audioArtistId }
                )

                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = ThreeArmContribution(id, audioTrackId, audioAlbumId, audioArtistId)
            }
            """
        )

    // arm<K,E>() is an inline+reified function compiled with JVM target 21 in lirp-core.
    // The test compilation must target the same version to inline the bytecode.
    fun compileWithBothProcessors(vararg sources: SourceFile) =
        KspTestSupport.compile(
            providers = listOf(TableDefProcessorProvider(), ReactiveEntityRefProcessorProvider()),
            sources = sources.toList(),
            jvmTarget = "21"
        )

    test("emits per-arm ForeignKeyDef entries in _LirpTableDef for a two-arm polymorphicAggregate") {
        val result = compileWithBothProcessors(twoArmEntitySource)

        result.shouldSucceed()
        val content = result.generatedFileContent("AudioContribution_LirpTableDef.kt")
        content.shouldContainEach(
            "override fun foreignKeys(): List<ForeignKeyDef>",
            "ForeignKeyDef(columnName = \"audio_item_id\"",
            "referencedTable = \"audio_item\"",
            "referencedColumn = \"id\"",
            "ForeignKeyDef(columnName = \"audio_playlist_id\"",
            "referencedTable = \"mutable_audio_playlist\""
        )
    }

    test("emits per-arm RefEntry in _LirpRefAccessor for a two-arm polymorphicAggregate") {
        val result = compileWithBothProcessors(twoArmEntitySource)

        result.shouldSucceed()
        val content = result.generatedFileContent("AudioContribution_LirpRefAccessor.kt")
        content.shouldContainEach(
            // Item arm
            "refName = \"target.item\"",
            """armDelegate("item").referenceId""",
            """armDelegate("item") as AggregateRefDelegate<*, *>""",
            // Playlist arm
            "refName = \"target.playlist\"",
            """armDelegate("playlist").referenceId""",
            """armDelegate("playlist") as AggregateRefDelegate<*, *>""",
            // Arms do not bubble up
            "bubbleUp = false"
        )
    }

    test("arm onDelete defaults to DETACH when not specified") {
        val result = compileWithBothProcessors(twoArmEntitySource)

        result.shouldSucceed()
        val tableDef = result.generatedFileContent("AudioContribution_LirpTableDef.kt")
        tableDef shouldContain "onDelete = CascadeAction.DETACH"
    }

    test("arm with onDelete = NONE emits no FK entry in _LirpTableDef") {
        val noneArmSource =
            SourceFile.kotlin(
                "ContributionWithNone.kt",
                """
                package test

                import net.transgressoft.lirp.entity.CascadeAction
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.polymorphicAggregate
                import net.transgressoft.lirp.persistence.arm

                @PersistenceMapping
                data class RefTarget(override val id: Int) : ReactiveEntityBase<Int, RefTarget>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }

                @PersistenceMapping
                class NoConstraintArm(override val id: Int, refTargetId: Int? = null) : ReactiveEntityBase<Int, NoConstraintArm>() {
                    var refTargetId: Int? by reactiveProperty(refTargetId)

                    val ref by polymorphicAggregate(
                        arm<Int, RefTarget>("target", onDelete = CascadeAction.NONE) { refTargetId }
                    )

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = NoConstraintArm(id, refTargetId)
                }
                """
            )
        val result = compileWithBothProcessors(noneArmSource)

        result.shouldSucceed()
        val content = result.generatedFileContent("NoConstraintArm_LirpTableDef.kt")
        // NONE arms skip FK emission
        content shouldNotContain "override fun foreignKeys()"
    }

    test("three-arm polymorphicAggregate yields three FK entries and three RefEntry entries") {
        val result = compileWithBothProcessors(threeArmEntitySource)

        result.shouldSucceed()
        val tableDef = result.generatedFileContent("ThreeArmContribution_LirpTableDef.kt")
        tableDef.shouldContainEach(
            "audio_track_id",
            "audio_album_id",
            "audio_artist_id",
            "audio_track",
            "audio_album",
            "audio_artist"
        )

        val refAccessor = result.generatedFileContent("ThreeArmContribution_LirpRefAccessor.kt")
        refAccessor.shouldContainEach(
            "refName = \"ref.alpha\"",
            "refName = \"ref.beta\"",
            "refName = \"ref.gamma\"",
            """armDelegate("alpha")""",
            """armDelegate("beta")""",
            """armDelegate("gamma")"""
        )
    }

    test("per-arm RefEntry delegateGetter reaches inner delegate via armDelegate") {
        val result = compileWithBothProcessors(twoArmEntitySource)

        result.shouldSucceed()
        val content = result.generatedFileContent("AudioContribution_LirpRefAccessor.kt")
        content.shouldContainEach(
            "it.target.armDelegate(",
            ") as AggregateRefDelegate<*, *>"
        )
    }

    test("positional onDelete cascade argument is honoured and emits the per-arm cascade action") {
        val positionalSource =
            SourceFile.kotlin(
                "PositionalArm.kt",
                """
                package test

                import net.transgressoft.lirp.entity.CascadeAction
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.polymorphicAggregate
                import net.transgressoft.lirp.persistence.arm

                @PersistenceMapping
                data class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }

                @PersistenceMapping
                class PositionalArm(override val id: Int, audioTrackId: Int? = null) : ReactiveEntityBase<Int, PositionalArm>() {
                    var audioTrackId: Int? by reactiveProperty(audioTrackId)

                    val ref by polymorphicAggregate(
                        arm<Int, AudioTrack>("track", CascadeAction.CASCADE) { audioTrackId }
                    )

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = PositionalArm(id, audioTrackId)
                }
                """
            )
        val result = compileWithBothProcessors(positionalSource)

        result.shouldSucceed()
        // The arm is not dropped: it reaches both the FK table-def and the ref-accessor with CASCADE.
        val tableDef = result.generatedFileContent("PositionalArm_LirpTableDef.kt")
        tableDef shouldContain "onDelete = CascadeAction.CASCADE"
        val refAccessor = result.generatedFileContent("PositionalArm_LirpRefAccessor.kt")
        refAccessor.shouldContainEach(
            "refName = \"ref.track\"",
            "cascadeAction = CascadeAction.CASCADE"
        )
    }

    test("this-qualified scalar in the arm lambda captures the backing scalar, not 'this'") {
        val qualifiedSource =
            SourceFile.kotlin(
                "QualifiedScalar.kt",
                """
                package test

                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.polymorphicAggregate
                import net.transgressoft.lirp.persistence.arm

                @PersistenceMapping
                data class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }

                @PersistenceMapping
                class QualifiedScalar(override val id: Int, audioTrackId: Int? = null) : ReactiveEntityBase<Int, QualifiedScalar>() {
                    var audioTrackId: Int? by reactiveProperty(audioTrackId)

                    val ref by polymorphicAggregate(
                        arm<Int, AudioTrack>("track") { this.audioTrackId }
                    )

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = QualifiedScalar(id, audioTrackId)
                }
                """
            )
        val result = compileWithBothProcessors(qualifiedSource)

        // Before the fix this failed with "references unknown scalar 'this'".
        result.shouldSucceed()
        val tableDef = result.generatedFileContent("QualifiedScalar_LirpTableDef.kt")
        tableDef shouldContain "audio_track_id"
    }

    test("duplicate arm labels are rejected with a clear diagnostic") {
        val duplicateSource =
            SourceFile.kotlin(
                "DuplicateLabels.kt",
                """
                package test

                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.polymorphicAggregate
                import net.transgressoft.lirp.persistence.arm

                @PersistenceMapping
                data class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }

                @PersistenceMapping
                data class AudioAlbum(override val id: Int) : ReactiveEntityBase<Int, AudioAlbum>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }

                @PersistenceMapping
                class DuplicateLabels(override val id: Int, audioTrackId: Int? = null, audioAlbumId: Int? = null) :
                    ReactiveEntityBase<Int, DuplicateLabels>() {
                    var audioTrackId: Int? by reactiveProperty(audioTrackId)
                    var audioAlbumId: Int? by reactiveProperty(audioAlbumId)

                    val ref by polymorphicAggregate(
                        arm<Int, AudioTrack>("dup") { audioTrackId },
                        arm<Int, AudioAlbum>("dup") { audioAlbumId }
                    )

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = DuplicateLabels(id, audioTrackId, audioAlbumId)
                }
                """
            )
        val result = compileWithBothProcessors(duplicateSource)

        result.shouldFailWith("duplicate polymorphic arm label")
    }

    test("non-identifier arm label is rejected with a clear diagnostic") {
        val badLabelSource =
            SourceFile.kotlin(
                "BadLabel.kt",
                """
                package test

                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.polymorphicAggregate
                import net.transgressoft.lirp.persistence.arm

                @PersistenceMapping
                data class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }

                @PersistenceMapping
                class BadLabel(override val id: Int, audioTrackId: Int? = null) : ReactiveEntityBase<Int, BadLabel>() {
                    var audioTrackId: Int? by reactiveProperty(audioTrackId)

                    val ref by polymorphicAggregate(
                        arm<Int, AudioTrack>("my-track") { audioTrackId }
                    )

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = BadLabel(id, audioTrackId)
                }
                """
            )
        val result = compileWithBothProcessors(badLabelSource)

        result.shouldFailWith("not valid Kotlin identifiers")
    }

    test("six-arm and multiline-formatted declarations are not truncated by the scan window") {
        val sixArmSource =
            SourceFile.kotlin(
                "SixArmContribution.kt",
                """
                package test

                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.polymorphicAggregate
                import net.transgressoft.lirp.persistence.arm

                @PersistenceMapping
                data class A1(override val id: Int) : ReactiveEntityBase<Int, A1>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }
                @PersistenceMapping
                data class A2(override val id: Int) : ReactiveEntityBase<Int, A2>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }
                @PersistenceMapping
                data class A3(override val id: Int) : ReactiveEntityBase<Int, A3>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }
                @PersistenceMapping
                data class A4(override val id: Int) : ReactiveEntityBase<Int, A4>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }
                @PersistenceMapping
                data class A5(override val id: Int) : ReactiveEntityBase<Int, A5>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }
                @PersistenceMapping
                data class A6(override val id: Int) : ReactiveEntityBase<Int, A6>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }

                @PersistenceMapping
                class SixArmContribution(
                    override val id: Int,
                    a1Id: Int? = null,
                    a2Id: Int? = null,
                    a3Id: Int? = null,
                    a4Id: Int? = null,
                    a5Id: Int? = null,
                    a6Id: Int? = null
                ) : ReactiveEntityBase<Int, SixArmContribution>() {
                    var a1Id: Int? by reactiveProperty(a1Id)
                    var a2Id: Int? by reactiveProperty(a2Id)
                    var a3Id: Int? by reactiveProperty(a3Id)
                    var a4Id: Int? by reactiveProperty(a4Id)
                    var a5Id: Int? by reactiveProperty(a5Id)
                    var a6Id: Int? by reactiveProperty(a6Id)

                    val ref by polymorphicAggregate(
                        arm<Int, A1>("one")
                            { a1Id },
                        arm<Int, A2>("two")
                            { a2Id },
                        arm<Int, A3>("three")
                            { a3Id },
                        arm<Int, A4>("four")
                            { a4Id },
                        arm<Int, A5>("five")
                            { a5Id },
                        arm<Int, A6>("six")
                            { a6Id }
                    )

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = SixArmContribution(id, a1Id, a2Id, a3Id, a4Id, a5Id, a6Id)
                }
                """
            )
        val result = compileWithBothProcessors(sixArmSource)

        result.shouldSucceed()
        // The trailing arms past the old 12-line window still reach codegen.
        val refAccessor = result.generatedFileContent("SixArmContribution_LirpRefAccessor.kt")
        refAccessor.shouldContainEach(
            "refName = \"ref.one\"",
            "refName = \"ref.six\""
        )
        val sealed = result.generatedFileContent("SixArmContributionRefArm.kt")
        sealed shouldContain "data class Six(val entity: A6)"
    }

    test("a parenthesis inside an arm label does not corrupt the span scan") {
        // A non-identifier label containing parens must be rejected as an invalid identifier rather
        // than prematurely closing the balanced-paren span and silently dropping later arms.
        val parenLabelSource =
            SourceFile.kotlin(
                "ParenLabel.kt",
                """
                package test

                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.polymorphicAggregate
                import net.transgressoft.lirp.persistence.arm

                @PersistenceMapping
                data class AudioTrack(override val id: Int) : ReactiveEntityBase<Int, AudioTrack>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }

                @PersistenceMapping
                data class AudioAlbum(override val id: Int) : ReactiveEntityBase<Int, AudioAlbum>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }

                @PersistenceMapping
                class ParenLabel(override val id: Int, audioTrackId: Int? = null, audioAlbumId: Int? = null) :
                    ReactiveEntityBase<Int, ParenLabel>() {
                    var audioTrackId: Int? by reactiveProperty(audioTrackId)
                    var audioAlbumId: Int? by reactiveProperty(audioAlbumId)

                    val ref by polymorphicAggregate(
                        arm<Int, AudioTrack>("foo)bar") { audioTrackId },
                        arm<Int, AudioAlbum>("album") { audioAlbumId }
                    )

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = ParenLabel(id, audioTrackId, audioAlbumId)
                }
                """
            )
        val result = compileWithBothProcessors(parenLabelSource)

        // The span walk skips the ')' inside the string literal, so the SECOND arm is still seen and
        // the first label is reported as a non-identifier rather than silently swallowing the rest.
        result.shouldFailWith("not valid Kotlin identifiers")
    }

    test("aliased arm target import resolves to the aliased FQN") {
        val aliasedTargetSource =
            SourceFile.kotlin(
                "AliasedTarget.kt",
                """
                package test.other

                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                data class RemoteTrack(override val id: Int) : ReactiveEntityBase<Int, RemoteTrack>() {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = copy()
                }
                """
            )
        val aliasedConsumerSource =
            SourceFile.kotlin(
                "AliasedConsumer.kt",
                """
                package test

                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.persistence.PersistenceMapping
                import net.transgressoft.lirp.persistence.polymorphicAggregate
                import net.transgressoft.lirp.persistence.arm
                import test.other.RemoteTrack as Track

                @PersistenceMapping
                class AliasedConsumer(override val id: Int, trackId: Int? = null) : ReactiveEntityBase<Int, AliasedConsumer>() {
                    var trackId: Int? by reactiveProperty(trackId)

                    val ref by polymorphicAggregate(
                        arm<Int, Track>("track") { trackId }
                    )

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = AliasedConsumer(id, trackId)
                }
                """
            )
        val result = compileWithBothProcessors(aliasedTargetSource, aliasedConsumerSource)

        result.shouldSucceed()
        // The arm binds to the aliased FQN's table, not to a wrong same-simple-name import.
        val tableDef = result.generatedFileContent("AliasedConsumer_LirpTableDef.kt")
        tableDef shouldContain "referencedTable = \"remote_track\""
        val sealed = result.generatedFileContent("AliasedConsumerRefArm.kt")
        sealed shouldContain "import test.other.RemoteTrack"
    }
})
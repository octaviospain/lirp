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
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.symbolProcessorProviders
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for [PolymorphicRefEmitter], verifying that [TableDefProcessor] generates
 * correct sealed-union accessor types for `polymorphicAggregate` property declarations.
 *
 * Tests cover: sealed-type structure, exactly-typed data-class subtypes, the `activeArm()` extension,
 * exhaustive `when` compilation without an `else` branch, and phantom-type disambiguation when
 * multiple polymorphic properties coexist on a single entity.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("PolymorphicSealedUnionGenerator")
internal class PolymorphicSealedUnionGeneratorTest : FunSpec({

    // Two-arm entity source: AudioContribution with AudioItem and MutableAudioPlaylist arms.
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

    // Entity with two distinct polymorphicAggregate properties for phantom-type disambiguation tests.
    val twoPropertiesEntitySource =
        SourceFile.kotlin(
            "DualPolymorphicEntity.kt",
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
            class DualPolymorphicEntity(
                override val id: Int,
                primaryItemId: Int? = null,
                primaryPlaylistId: Int? = null,
                secondaryItemId: Int? = null,
                secondaryPlaylistId: Int? = null
            ) : ReactiveEntityBase<Int, DualPolymorphicEntity>() {
                var primaryItemId: Int? by reactiveProperty(primaryItemId)
                var primaryPlaylistId: Int? by reactiveProperty(primaryPlaylistId)
                var secondaryItemId: Int? by reactiveProperty(secondaryItemId)
                var secondaryPlaylistId: Int? by reactiveProperty(secondaryPlaylistId)

                val primary by polymorphicAggregate(
                    arm<Int, AudioItem>("item") { primaryItemId },
                    arm<Int, MutableAudioPlaylist>("playlist") { primaryPlaylistId }
                )

                val secondary by polymorphicAggregate(
                    arm<Int, AudioItem>("item") { secondaryItemId },
                    arm<Int, MutableAudioPlaylist>("playlist") { secondaryPlaylistId }
                )

                override val uniqueId: String get() = "${'$'}id"
                override fun clone() = DualPolymorphicEntity(id, primaryItemId, primaryPlaylistId, secondaryItemId, secondaryPlaylistId)
            }
            """
        )

    fun compileWithTableDefProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
                jvmTarget = "21"
            }
        compilation.configureKsp { this.withCompilation = true }
        compilation.symbolProcessorProviders += TableDefProcessorProvider()
        return compilation.compile()
    }

    test("generates sealed class with one data-class subtype per arm for a two-arm entity") {
        val result = compileWithTableDefProcessor(twoArmEntitySource)

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("AudioContributionTargetArm.kt")
        content shouldContain "sealed class AudioContributionTargetArm"
        content shouldContain "data class Item(val entity: AudioItem) : AudioContributionTargetArm()"
        content shouldContain "data class Playlist(val entity: MutableAudioPlaylist) : AudioContributionTargetArm()"
        content shouldContain "fun PolymorphicResolution<AudioContributionTargetArm>.activeArm(): AudioContributionTargetArm"
    }

    test("generated activeArm() resolves label and entity in one scan and returns exact subtype") {
        val result = compileWithTableDefProcessor(twoArmEntitySource)

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("AudioContributionTargetArm.kt")
        // A single resolveActive() scan feeds both dispatch and the typed cast — no second
        // resolveArm() scan that could observe a different active arm (TOCTOU).
        content shouldContain "val (label, entity) = this.resolveActive()"
        content shouldContain """"item" -> AudioContributionTargetArm.Item(entity as AudioItem)"""
        content shouldContain """"playlist" -> AudioContributionTargetArm.Playlist(entity as MutableAudioPlaylist)"""
        content shouldNotContain "resolveArm("
        content shouldContain "else -> error("
    }

    test("exhaustive when over activeArm() compiles without else branch") {
        // The consumer source references the generated sealed type by name. It compiles together
        // with the KSP-generated sealed file (withCompilation = true), proving that the generated
        // activeArm() extension enables compiler-enforced exhaustion.
        val consumerSource =
            SourceFile.kotlin(
                "Consumer.kt",
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

                // Exhaustive when with no else — the resolution is cast to the typed PolymorphicResolution
                // so the compiler can resolve the generated activeArm() extension and enforce exhaustion.
                @Suppress("UNCHECKED_CAST")
                fun describeTarget(contribution: AudioContribution): String {
                    val resolution = contribution.target.resolution() as net.transgressoft.lirp.persistence.PolymorphicResolution<AudioContributionTargetArm>
                    return when (val arm = resolution.activeArm()) {
                        is AudioContributionTargetArm.Item -> "item: ${'$'}{arm.entity.title}"
                        is AudioContributionTargetArm.Playlist -> "playlist: ${'$'}{arm.entity.name}"
                    }
                }
                """
            )

        val result = compileWithTableDefProcessor(consumerSource)
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }

    test("two polymorphicAggregate properties on one entity produce two distinct sealed files") {
        val result = compileWithTableDefProcessor(twoPropertiesEntitySource)

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val primaryContent = result.generatedFileContent("DualPolymorphicEntityPrimaryArm.kt")
        val secondaryContent = result.generatedFileContent("DualPolymorphicEntitySecondaryArm.kt")
        primaryContent shouldContain "sealed class DualPolymorphicEntityPrimaryArm"
        primaryContent shouldContain "fun PolymorphicResolution<DualPolymorphicEntityPrimaryArm>.activeArm()"
        secondaryContent shouldContain "sealed class DualPolymorphicEntitySecondaryArm"
        secondaryContent shouldContain "fun PolymorphicResolution<DualPolymorphicEntitySecondaryArm>.activeArm()"
    }

    test("two distinct activeArm() extensions compile without conflict on a dual-property entity") {
        // The phantom type parameter on PolymorphicResolution<A> ensures both extensions have
        // distinct receiver types so the Kotlin compiler resolves them independently.
        val consumerSource =
            SourceFile.kotlin(
                "DualConsumer.kt",
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
                class DualPolymorphicEntity(
                    override val id: Int,
                    primaryItemId: Int? = null,
                    primaryPlaylistId: Int? = null,
                    secondaryItemId: Int? = null,
                    secondaryPlaylistId: Int? = null
                ) : ReactiveEntityBase<Int, DualPolymorphicEntity>() {
                    var primaryItemId: Int? by reactiveProperty(primaryItemId)
                    var primaryPlaylistId: Int? by reactiveProperty(primaryPlaylistId)
                    var secondaryItemId: Int? by reactiveProperty(secondaryItemId)
                    var secondaryPlaylistId: Int? by reactiveProperty(secondaryPlaylistId)

                    val primary by polymorphicAggregate(
                        arm<Int, AudioItem>("item") { primaryItemId },
                        arm<Int, MutableAudioPlaylist>("playlist") { primaryPlaylistId }
                    )

                    val secondary by polymorphicAggregate(
                        arm<Int, AudioItem>("item") { secondaryItemId },
                        arm<Int, MutableAudioPlaylist>("playlist") { secondaryPlaylistId }
                    )

                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = DualPolymorphicEntity(id, primaryItemId, primaryPlaylistId, secondaryItemId, secondaryPlaylistId)
                }

                // Both properties call activeArm() via typed resolution casts.
                // The phantom type parameter distinguishes the two extensions by receiver type.
                @Suppress("UNCHECKED_CAST")
                fun describePrimary(entity: DualPolymorphicEntity): String {
                    val resolution = entity.primary.resolution() as net.transgressoft.lirp.persistence.PolymorphicResolution<DualPolymorphicEntityPrimaryArm>
                    return when (val arm = resolution.activeArm()) {
                        is DualPolymorphicEntityPrimaryArm.Item -> "primary-item: ${'$'}{arm.entity.title}"
                        is DualPolymorphicEntityPrimaryArm.Playlist -> "primary-playlist: ${'$'}{arm.entity.name}"
                    }
                }

                @Suppress("UNCHECKED_CAST")
                fun describeSecondary(entity: DualPolymorphicEntity): String {
                    val resolution = entity.secondary.resolution() as net.transgressoft.lirp.persistence.PolymorphicResolution<DualPolymorphicEntitySecondaryArm>
                    return when (val arm = resolution.activeArm()) {
                        is DualPolymorphicEntitySecondaryArm.Item -> "secondary-item: ${'$'}{arm.entity.title}"
                        is DualPolymorphicEntitySecondaryArm.Playlist -> "secondary-playlist: ${'$'}{arm.entity.name}"
                    }
                }
                """
            )

        val result = compileWithTableDefProcessor(consumerSource)
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }
})
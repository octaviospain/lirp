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
 * KSP compilation tests verifying that [TableDefProcessor] injects a `deleted_at` column
 * into the generated `_LirpTableDef` for entities whose supertype hierarchy includes
 * [net.transgressoft.lirp.entity.SoftDeletable], and emits no such column for entities
 * that do not implement it.
 *
 * The injected column is synthesized by the processor even when the `deletedAt` property
 * is not explicitly declared on the entity class — it suffices for the supertype chain to
 * include `SoftDeletable`.
 */
@OptIn(ExperimentalCompilerApi::class)
internal class SoftDeleteColumnInjectionTest : StringSpec({

    /**
     * Compiles two sources together: a base abstract class that declares `deletedAt` via
     * `SoftDeletable`, and a concrete `@PersistenceMapping` entity that inherits it without
     * re-declaring the property. This exercises the synthesized-column path.
     */
    "TableDefProcessor injects a nullable deleted_at column for an audio track inheriting SoftDeletable via a base class" {
        val baseSource =
            SourceFile.kotlin(
                "AbstractSoftDeletableAudioTrack.kt",
                """
                package test
                import net.transgressoft.lirp.entity.ReactiveEntityBase
                import net.transgressoft.lirp.entity.SoftDeletable
                import java.time.Instant

                abstract class AbstractSoftDeletableAudioTrack<E : AbstractSoftDeletableAudioTrack<E>>(
                    override val id: Int
                ) : ReactiveEntityBase<Int, E>(), SoftDeletable {
                    override var deletedAt: Instant? by reactiveProperty(null)
                }
                """
            )
        val entitySource =
            SourceFile.kotlin(
                "SoftDeletableAudioTrack.kt",
                """
                package test
                import net.transgressoft.lirp.persistence.PersistenceMapping

                @PersistenceMapping
                class SoftDeletableAudioTrack(
                    id: Int,
                    var title: String
                ) : AbstractSoftDeletableAudioTrack<SoftDeletableAudioTrack>(id) {
                    override val uniqueId: String get() = "${'$'}id"
                    override fun clone() = SoftDeletableAudioTrack(id, title).also { it.deletedAt = deletedAt }
                }
                """
            )

        val result = KspTestSupport.compile(TableDefProcessorProvider(), baseSource, entitySource)

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("SoftDeletableAudioTrack_LirpTableDef.kt")
        content shouldContain "deleted_at"
        // deleted_at must be nullable and not the version column
        val deletedAtLine = content.lines().first { it.contains("\"deleted_at\"") }
        deletedAtLine shouldContain "nullable = true"
        deletedAtLine shouldContain "isVersion = false"
        // InstantColumnConverter must be used for Instant→TEXT mapping
        content shouldContain "net.transgressoft.lirp.persistence.InstantColumnConverter"
    }

    "TableDefProcessor does not inject deleted_at for an audio track entity without SoftDeletable" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "PlainAudioTrack.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    class PlainAudioTrack(
                        override val id: Int,
                        var title: String
                    ) : ReactiveEntityBase<Int, PlainAudioTrack>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = PlainAudioTrack(id, title)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PlainAudioTrack_LirpTableDef.kt")
        content shouldNotContain "deleted_at"
    }
})
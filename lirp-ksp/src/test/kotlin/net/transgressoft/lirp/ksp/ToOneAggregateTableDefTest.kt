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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for FK emission driven by
 * [@ToOneAggregate][net.transgressoft.lirp.persistence.ToOneAggregate] in [TableDefProcessor].
 *
 * Verifies that a `@ToOneAggregate`-annotated scalar produces the correct [ForeignKeyDef] entry
 * in the generated `_LirpTableDef`, mirroring the behaviour previously driven by the
 * `@ToManyAggregates + aggregate { scalarId }` two-declaration pattern.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("ToOneAggregateTableDef")
internal class ToOneAggregateTableDefTest : FunSpec({

    test("TableDefProcessor emits ForeignKeyDef for entity with @ToOneAggregate scalar") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "VehicleOrder.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.CascadeAction
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate

                    @PersistenceMapping
                    class Company(override val id: Int) : ReactiveEntityBase<Int, Company>() {
                        var name: String by reactiveProperty("")
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Company(id)
                    }

                    @PersistenceMapping
                    class VehicleOrder(override val id: Long, companyId: Int) : ReactiveEntityBase<Long, VehicleOrder>() {
                        @ToOneAggregate(target = Company::class, onDelete = CascadeAction.RESTRICT)
                        var companyId: Int by reactiveProperty(companyId)
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = VehicleOrder(id, companyId)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("VehicleOrder_LirpTableDef.kt")
        content shouldContain "override fun foreignKeys(): List<ForeignKeyDef>"
        content shouldContain "ForeignKeyDef(columnName = \"company_id\""
        content shouldContain "referencedTable = \"company\""
        content shouldContain "referencedColumn = \"id\""
    }

    test("TableDefProcessor emits ForeignKeyDef with correct onDelete action for @ToOneAggregate") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "AlbumTrack.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.CascadeAction
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate

                    @PersistenceMapping
                    class AudioAlbum(override val id: Int) : ReactiveEntityBase<Int, AudioAlbum>() {
                        var title: String by reactiveProperty("")
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioAlbum(id)
                    }

                    @PersistenceMapping
                    class AlbumTrack(override val id: Long, albumId: Int) : ReactiveEntityBase<Long, AlbumTrack>() {
                        @ToOneAggregate(target = AudioAlbum::class, onDelete = CascadeAction.CASCADE)
                        var albumId: Int by reactiveProperty(albumId)
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AlbumTrack(id, albumId)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("AlbumTrack_LirpTableDef.kt")
        content shouldContain "ForeignKeyDef(columnName = \"album_id\""
        content shouldContain "onDelete = CascadeAction.CASCADE"
    }

    test("TableDefProcessor emits ForeignKeyDef with SET_NULL semantics for nullable @ToOneAggregate scalar") {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "OptionalRef.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.CascadeAction
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import java.util.UUID

                    @PersistenceMapping
                    class Distributor(override val id: UUID) : ReactiveEntityBase<UUID, Distributor>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = Distributor(id)
                    }

                    @PersistenceMapping
                    class AudioItem(override val id: UUID, distributorId: UUID?) : ReactiveEntityBase<UUID, AudioItem>() {
                        @ToOneAggregate(target = Distributor::class, onDelete = CascadeAction.DETACH)
                        var distributorId: UUID? by reactiveProperty(distributorId)
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioItem(id, distributorId)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("AudioItem_LirpTableDef.kt")
        content shouldContain "ForeignKeyDef(columnName = \"distributor_id\""
        content shouldContain "onDelete = CascadeAction.DETACH"
    }

    test("TableDefProcessor emits ForeignKeyDef targeting the lambda scalar for delegate-val @ToOneAggregate") {
        // Regression: the FK column must be derived from the lambda body (`labelId`), not the
        // delegate property name (`label`). Previously `backingScalarName` was always set to
        // the property's own simple name, producing `ForeignKeyDef(columnName = "label", …)`.
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "ReleaseWithDelegateRef.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.CascadeAction
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.ToOneAggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    @PersistenceMapping
                    class AudioLabel(override val id: Int) : ReactiveEntityBase<Int, AudioLabel>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioLabel(id)
                    }

                    @PersistenceMapping
                    class AudioRelease(
                        override val id: Int,
                        var labelId: Int
                    ) : ReactiveEntityBase<Int, AudioRelease>() {
                        @ToOneAggregate(target = AudioLabel::class, onDelete = CascadeAction.RESTRICT)
                        val label by aggregate<Int, AudioLabel> { labelId }
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AudioRelease(id, labelId)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("AudioRelease_LirpTableDef.kt")
        // FK column must reference the backing scalar `label_id`, not the delegate name `label`.
        content shouldContain "ForeignKeyDef(columnName = \"label_id\""
        content shouldContain "referencedTable = \"audio_label\""
    }
})
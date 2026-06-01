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
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import com.tschuchort.compiletesting.symbolProcessorProviders
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * KSP compilation tests covering edge cases and validation paths in [EmbeddableAnalyzer] and
 * [ColumnMetaBuilder] that are not exercised by the main happy-path or diagnostics suites.
 * Targets: body-declared @Embedded inside a nested @Embeddable, validation on nested
 * @Embedded var parameters inside an @Embeddable, nullable embedded leaf columns, non-ctor
 * setter columns alongside @Embedded ctor params, and @PersistenceProperty name override on
 * embedded leaves.
 */
@OptIn(ExperimentalCompilerApi::class)
class EmbeddableEdgeCasesTest : StringSpec({

    fun compileWithProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
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

    "rejects @Embedded body-declared property inside nested @Embeddable during recursive descent" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "BodyEmbeddedInsideEmbeddable.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class LeafEmbeddable(val value: String)

                    @Embeddable
                    data class MiddleEmbeddable(val label: String) {
                        @Embedded
                        val nested: LeafEmbeddable = LeafEmbeddable("x")
                    }

                    @PersistenceMapping
                    data class BodyEmbeddedInsideEmbeddableEntity(
                        override val id: Int,
                        @Embedded val middle: MiddleEmbeddable
                    ) : ReactiveEntityBase<Int, BodyEmbeddedInsideEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BodyEmbeddedInsideEmbeddableEntity(id, middle)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "primary-constructor parameter"
        result.messages shouldContain "test.MiddleEmbeddable.nested"
    }

    "accepts @Embedded on var constructor parameter inside nested @Embeddable" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "VarEmbeddedInsideEmbeddable.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class InnerEmbeddable(val code: String)

                    @Embeddable
                    data class OuterEmbeddable(@Embedded var inner: InnerEmbeddable)

                    @PersistenceMapping
                    data class VarNestedEmbeddedEntity(
                        override val id: Int,
                        @Embedded val outer: OuterEmbeddable
                    ) : ReactiveEntityBase<Int, VarNestedEmbeddedEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = VarNestedEmbeddedEntity(id, outer)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
    }

    "rejects @Embedded referencing non-@Embeddable type at nested level" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NonEmbeddableNestedTarget.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    data class PlainInner(val x: String)

                    @Embeddable
                    data class OuterEmbeddable(@Embedded val inner: PlainInner)

                    @PersistenceMapping
                    data class NonEmbeddableNestedEntity(
                        override val id: Int,
                        @Embedded val outer: OuterEmbeddable
                    ) : ReactiveEntityBase<Int, NonEmbeddableNestedEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NonEmbeddableNestedEntity(id, outer)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "must reference an @Embeddable type"
        result.messages shouldContain "test.PlainInner"
    }

    "flattens nullable @Embeddable leaf columns with correct nullability in ColumnDef" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NullableLeafEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class ContactEmbeddable(val email: String, val phone: String?)

                    @PersistenceMapping
                    data class NullableLeafEntity(
                        override val id: Int,
                        @Embedded val contact: ContactEmbeddable
                    ) : ReactiveEntityBase<Int, NullableLeafEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NullableLeafEntity(id, contact)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("NullableLeafEntity_LirpTableDef.kt")
        content shouldContain "name = \"contact_email\""
        content shouldContain "name = \"contact_phone\""
        // The nullable leaf must declare nullable = true
        content shouldContain "nullable = true"
    }

    "flattens @Embedded ctor param alongside non-ctor setter column" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "MixedCtorAndSetterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class CoordEmbeddable(val lat: Double, val lng: Double)

                    @PersistenceMapping
                    class MixedCtorAndSetterEntity(
                        override val id: Int,
                        @Embedded val coord: CoordEmbeddable
                    ) : ReactiveEntityBase<Int, MixedCtorAndSetterEntity>() {
                        var label: String by reactiveProperty("default")
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = MixedCtorAndSetterEntity(id, coord)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MixedCtorAndSetterEntity_LirpTableDef.kt")
        content shouldContain "name = \"coord_lat\""
        content shouldContain "name = \"coord_lng\""
        content shouldContain "name = \"label\""
        // The non-ctor setter column must appear in applyRow, not the embedded-derived columns
        val applyRowBlock = content.substringAfter("override fun applyRow").substringBefore("override fun ")
        applyRowBlock shouldContain "entity.label"
        applyRowBlock shouldNotContain "entity.coord"
    }

    "ignores `@PersistenceProperty`(name=...) on `@Embeddable` leaf during flattening" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NamedLeafEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @Embeddable
                    data class AddressEmbeddable(
                        @PersistenceProperty(name = "street_line") val streetName: String,
                        val city: String
                    )

                    @PersistenceMapping
                    data class NamedLeafEntity(
                        override val id: Int,
                        @Embedded val address: AddressEmbeddable
                    ) : ReactiveEntityBase<Int, NamedLeafEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NamedLeafEntity(id, address)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("NamedLeafEntity_LirpTableDef.kt")
        // The leaf name should use the snake-case prefix + property snake-case, not @PersistenceProperty name,
        // because buildEmbeddedLeafColumn uses childParamName.toSnakeCase() for the suffix.
        // The @PersistenceProperty name is ignored at leaf level — the prefix + param snake-case wins.
        content shouldContain "name = \"address_street_name\""
        content shouldContain "name = \"address_city\""
    }

    "compiles when the referenced `@Embeddable` is valid (control case)" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "EmptyCtorEmbeddable.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class EmptyEmbeddable(val dummy: Unit = Unit)

                    @Embeddable
                    data class NoPropEmbeddable(val x: String)

                    @PersistenceMapping
                    data class EmptyCtorEntity(
                        override val id: Int,
                        @Embedded val payload: NoPropEmbeddable
                    ) : ReactiveEntityBase<Int, EmptyCtorEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = EmptyCtorEntity(id, payload)
                    }
                    """
                )
            )

        // Entity with a properly-structured @Embeddable should compile fine
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("EmptyCtorEntity_LirpTableDef.kt")
        content shouldContain "name = \"payload_x\""
    }

    "flattens two @Embedded siblings with distinct auto-derived prefixes" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "TwoSiblingsEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class PartyEmbeddable(val name: String, val code: String)

                    @PersistenceMapping
                    data class TwoSiblingsEntity(
                        override val id: Int,
                        @Embedded val buyer: PartyEmbeddable,
                        @Embedded val seller: PartyEmbeddable
                    ) : ReactiveEntityBase<Int, TwoSiblingsEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = TwoSiblingsEntity(id, buyer, seller)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("TwoSiblingsEntity_LirpTableDef.kt")
        content shouldContain "name = \"buyer_name\""
        content shouldContain "name = \"buyer_code\""
        content shouldContain "name = \"seller_name\""
        content shouldContain "name = \"seller_code\""
        // fromRow must reconstruct both value objects via their constructors
        val fromRowBlock = content.substringAfter("override fun fromRow").substringBefore("override fun ")
        fromRowBlock shouldContain "test.PartyEmbeddable("
    }

    "rejects @Embeddable data class with no primary constructor" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NoCtorEmbeddableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    class NoCtorEmbeddable {
                        val x: String = "fixed"
                    }

                    @PersistenceMapping
                    data class NoCtorEmbeddableEntity(
                        override val id: Int,
                        @Embedded val marker: NoCtorEmbeddable
                    ) : ReactiveEntityBase<Int, NoCtorEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NoCtorEmbeddableEntity(id, marker)
                    }
                    """
                )
            )

        // @Embeddable on a non-data class is rejected — enforces concrete data class
        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "@Embeddable must be a concrete data class"
        result.messages shouldContain "test.NoCtorEmbeddable"
    }

    "@PersistenceIgnore property is excluded from persisted columns" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "PersistenceIgnoreEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceIgnore
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    class PersistenceIgnoreEntity(override val id: Int) : ReactiveEntityBase<Int, PersistenceIgnoreEntity>() {
                        var label: String by reactiveProperty("default")
                        @PersistenceIgnore
                        var cached: String by reactiveProperty("")
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = PersistenceIgnoreEntity(id)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PersistenceIgnoreEntity_LirpTableDef.kt")
        content shouldContain "name = \"label\""
        content shouldNotContain "name = \"cached\""
    }

    "non-mutable non-ctor property with private setter is excluded from applyRow" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "PrivateSetterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    class PrivateSetterEntity(override val id: Int) : ReactiveEntityBase<Int, PrivateSetterEntity>() {
                        var tag: String by reactiveProperty("x")
                            private set
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = PrivateSetterEntity(id)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PrivateSetterEntity_LirpTableDef.kt")
        // tag column still appears in columns list (it is not excluded by isExcluded)
        content shouldContain "name = \"tag\""
        // applyRow must skip it since the setter is private (isMutable = false)
        val applyRowBlock = content.substringAfter("override fun applyRow").substringBefore("override fun ")
        applyRowBlock shouldNotContain "entity.tag"
    }
})
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * KSP compilation tests locking the misuse diagnostics for `@Embedded` / `@Embeddable` in
 * [TableDefProcessor]: target strictness on the consuming property, kind constraints
 * on the referenced `@Embeddable`, and column-name collision detection across the
 * full transitive flatten.
 *
 * Each test compiles a deliberately-broken fixture and asserts the build fails with a specific
 * diagnostic substring plus the offending property / type FQN, so refactoring the processor's
 * internals cannot silently regress the message wording consumers depend on.
 */
@OptIn(ExperimentalCompilerApi::class)
class EmbeddableDiagnosticsTest : StringSpec({

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

    "rejects @Embedded on var constructor parameter" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "VarEmbeddedEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class AddrEmbeddable(val street: String, val city: String)

                    @PersistenceMapping
                    class VarEmbeddedEntity(
                        override val id: Int,
                        @Embedded var addr: AddrEmbeddable
                    ) : ReactiveEntityBase<Int, VarEmbeddedEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = VarEmbeddedEntity(id, addr)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "@Embedded must be on a val constructor parameter"
        result.messages shouldContain "test.VarEmbeddedEntity.addr"
    }

    "rejects @Embedded on body-declared property" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "BodyEmbeddedEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class AddrEmbeddable(val street: String, val city: String)

                    @PersistenceMapping
                    class BodyEmbeddedEntity(override val id: Int) : ReactiveEntityBase<Int, BodyEmbeddedEntity>() {
                        @Embedded
                        val addr: AddrEmbeddable = AddrEmbeddable("", "")
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BodyEmbeddedEntity(id)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "primary-constructor parameter"
        result.messages shouldContain "test.BodyEmbeddedEntity.addr"
    }

    // The "custom getter" diagnostic in [TableDefProcessor.validateEmbeddedTargetStrictness]
    // is only reachable for primary-constructor parameters — but a Kotlin primary-constructor
    // parameter cannot syntactically carry a custom getter, and any body-declared property with
    // `@Embedded` (with or without `get()`) is caught earlier by the body-declared diagnostic.
    // We assert the body-declared-with-custom-getter site is rejected (with the body-declared
    // wording) so the structural intent — "@Embedded with a non-default value source is rejected
    // at compile time" — is locked in even though the specific message substring "custom getter"
    // is subsumed by the prior check.
    "rejects @Embedded on body-declared property with custom getter" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "CustomGetterEmbeddedEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class AddrEmbeddable(val street: String, val city: String)

                    @PersistenceMapping
                    class CustomGetterEmbeddedEntity(override val id: Int) : ReactiveEntityBase<Int, CustomGetterEmbeddedEntity>() {
                        @Embedded
                        val addr: AddrEmbeddable get() = AddrEmbeddable("a", "b")
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = CustomGetterEmbeddedEntity(id)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "primary-constructor parameter"
        result.messages shouldContain "test.CustomGetterEmbeddedEntity.addr"
    }

    "rejects @Embedded referencing a non-@Embeddable type" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NonEmbeddableTargetEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    data class PlainDataClass(val a: String, val b: String)

                    @PersistenceMapping
                    data class NonEmbeddableTargetEntity(
                        override val id: Int,
                        @Embedded val addr: PlainDataClass
                    ) : ReactiveEntityBase<Int, NonEmbeddableTargetEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NonEmbeddableTargetEntity(id, addr)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "must reference an @Embeddable type"
        result.messages shouldContain "test.NonEmbeddableTargetEntity.addr"
        result.messages shouldContain "test.PlainDataClass"
    }

    "rejects @Embedded referencing a non-class typealias" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "TypeAliasTargetEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    typealias AddrAlias = String

                    @PersistenceMapping
                    data class TypeAliasTargetEntity(
                        override val id: Int,
                        @Embedded val addr: AddrAlias
                    ) : ReactiveEntityBase<Int, TypeAliasTargetEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = TypeAliasTargetEntity(id, addr)
                    }
                    """
                )
            )

        // A typealias may resolve to its underlying class declaration in KSP. The processor
        // treats it as a non-@Embeddable target (since the underlying class lacks @Embeddable);
        // either ("must reference a class type") or ("must reference an
        // @Embeddable type") is acceptable — the structural error condition (typealias is not
        // a permissible @Embedded target) is what we lock in.
        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "test.TypeAliasTargetEntity.addr"
    }

    "rejects @Embeddable on non-data class" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NonDataClassEmbeddable.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    class NotData(val x: String, val y: String)

                    @PersistenceMapping
                    data class NonDataClassEntity(
                        override val id: Int,
                        @Embedded val addr: NotData
                    ) : ReactiveEntityBase<Int, NonDataClassEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NonDataClassEntity(id, addr)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "@Embeddable must be a concrete data class"
        result.messages shouldContain "test.NotData"
    }

    "rejects @Embeddable on abstract class" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "AbstractEmbeddable.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    abstract class AbsEmbeddable(val x: String)

                    @PersistenceMapping
                    data class AbstractEmbeddableEntity(
                        override val id: Int,
                        @Embedded val addr: AbsEmbeddable
                    ) : ReactiveEntityBase<Int, AbstractEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = AbstractEmbeddableEntity(id, addr)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "@Embeddable must be a concrete data class"
        result.messages shouldContain "test.AbsEmbeddable"
    }

    "rejects @Embeddable on sealed class" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "SealedEmbeddable.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    sealed class SealedEmbeddable(val x: String)

                    @PersistenceMapping
                    data class SealedEmbeddableEntity(
                        override val id: Int,
                        @Embedded val addr: SealedEmbeddable
                    ) : ReactiveEntityBase<Int, SealedEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = SealedEmbeddableEntity(id, addr)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "@Embeddable must be a concrete data class"
        result.messages shouldContain "test.SealedEmbeddable"
    }

    "rejects @Embeddable on object declaration" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ObjectEmbeddable.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    object ObjEmbeddable {
                        val x: String = "fixed"
                    }

                    @PersistenceMapping
                    data class ObjectEmbeddableEntity(
                        override val id: Int,
                        @Embedded val addr: ObjEmbeddable
                    ) : ReactiveEntityBase<Int, ObjectEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ObjectEmbeddableEntity(id, addr)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "@Embeddable must be a concrete data class"
        result.messages shouldContain "test.ObjEmbeddable"
    }

    "rejects column collision across two @Embedded siblings with identical explicit prefix" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "SiblingCollisionEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class PartyEmbeddable(val name: String, val countryCode: String)

                    @PersistenceMapping
                    data class SiblingCollisionEntity(
                        override val id: Int,
                        @Embedded(prefix = "X_") val left: PartyEmbeddable,
                        @Embedded(prefix = "X_") val right: PartyEmbeddable
                    ) : ReactiveEntityBase<Int, SiblingCollisionEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = SiblingCollisionEntity(id, left, right)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "Column name collision"
        result.messages shouldContain "X_name"
        result.messages shouldContain "test.SiblingCollisionEntity.left.name"
        result.messages shouldContain "test.SiblingCollisionEntity.right.name"
    }

    "rejects @Embeddable data class with zero-parameter primary constructor" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "ZeroParamEmbeddable.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class ZeroParamEmbeddable()

                    @PersistenceMapping
                    data class ZeroParamEmbeddableEntity(
                        override val id: Int,
                        @Embedded val payload: ZeroParamEmbeddable
                    ) : ReactiveEntityBase<Int, ZeroParamEmbeddableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = ZeroParamEmbeddableEntity(id, payload)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "@Embeddable must be a concrete data class"
        result.messages shouldContain "test.ZeroParamEmbeddable"
    }

    "rejects nullable @Embedded property with clear diagnostic" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "NullableEmbeddedEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @Embeddable
                    data class AddressEmbeddable(val street: String, val city: String)

                    @PersistenceMapping
                    data class NullableEmbeddedEntity(
                        override val id: Int,
                        @Embedded val address: AddressEmbeddable?
                    ) : ReactiveEntityBase<Int, NullableEmbeddedEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = NullableEmbeddedEntity(id, address)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "`@Embedded` nullable properties are not supported yet"
        result.messages shouldContain "test.NullableEmbeddedEntity.address"
    }

    "rejects @Embeddable with unsupported scalar leaf type causing whole entity to fail" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "InvalidLeafEmbeddableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    data class UnsupportedType(val x: Int)

                    @Embeddable
                    data class BrokenEmbeddable(val label: String, val payload: UnsupportedType)

                    @PersistenceMapping
                    data class InvalidLeafEntity(
                        override val id: Int,
                        @Embedded val data: BrokenEmbeddable
                    ) : ReactiveEntityBase<Int, InvalidLeafEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = InvalidLeafEntity(id, data)
                    }
                    """
                )
            )

        // The unsupported leaf type causes buildEmbeddedSlot to return null, which causes the
        // whole entity to be treated as unmapped rather than emitting partial codegen.
        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "Unsupported column type"
    }

    "rejects column collision between @Embedded grandchild and a sibling scalar with matching name" {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "GrandchildCollisionEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    @Embeddable
                    data class DeepLeaf(val value: String)

                    @Embeddable
                    data class IntermediateEmbeddable(
                        @Embedded(prefix = "deep_") val grandchild: DeepLeaf
                    )

                    @PersistenceMapping
                    data class GrandchildCollisionEntity(
                        override val id: Int,
                        @Embedded val inner: IntermediateEmbeddable,
                        @PersistenceProperty(name = "inner_deep_value") val sibling: String
                    ) : ReactiveEntityBase<Int, GrandchildCollisionEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = GrandchildCollisionEntity(id, inner, sibling)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "Column name collision"
        result.messages shouldContain "inner_deep_value"
        // Both the entity-rooted path through the grandchild AND the sibling scalar path are
        // named, proving the full transitive walk surfaces every contributor.
        result.messages shouldContain "test.GrandchildCollisionEntity.inner.grandchild.value"
        result.messages shouldContain "test.GrandchildCollisionEntity.sibling"
    }
})
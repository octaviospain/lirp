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
import io.kotest.core.spec.style.StringSpec
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * KSP compilation tests locking the misuse diagnostics for `@ElementCollection` in
 * [TableDefProcessor]: collection-shape and context restrictions, missing-converter sentinel,
 * composition with `@PersistenceProperty`, target placement, and element-S type narrowing to the
 * eight-primitive subset.
 *
 * Each test compiles a deliberately-broken fixture and asserts the build fails with a specific
 * diagnostic substring plus the offending property FQN, pinning the message wording so
 * refactoring the processor cannot silently regress the error text consumers depend on.
 */
@OptIn(ExperimentalCompilerApi::class)
class ElementCollectionDiagnosticsTest : StringSpec({

    // Map property type
    "rejects @ElementCollection with Map property type" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "MapTagEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String) = value
                        override fun fromSql(raw: String) = raw
                    }

                    @PersistenceMapping
                    data class MapTagEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = TagConverter::class) val tags: Map<String, String>
                    ) : ReactiveEntityBase<Int, MapTagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldFailWith(
            "@ElementCollection requires `List<E>` or `Set<E>`",
            "test.MapTagEntity.tags"
        )
    }

    // MutableList property type
    "rejects @ElementCollection with MutableList property type" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "MutableListTagEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String) = value
                        override fun fromSql(raw: String) = raw
                    }

                    @PersistenceMapping
                    data class MutableListTagEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = TagConverter::class) val tags: MutableList<String>
                    ) : ReactiveEntityBase<Int, MutableListTagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldFailWith(
            "@ElementCollection requires the immutable interface",
            "test.MutableListTagEntity.tags"
        )
    }

    // MutableSet property type
    "rejects @ElementCollection with MutableSet property type" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "MutableSetTagEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String) = value
                        override fun fromSql(raw: String) = raw
                    }

                    @PersistenceMapping
                    data class MutableSetTagEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = TagConverter::class) val tags: MutableSet<String>
                    ) : ReactiveEntityBase<Int, MutableSetTagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldFailWith(
            "@ElementCollection requires the immutable interface",
            "test.MutableSetTagEntity.tags"
        )
    }

    // nullable element type
    "rejects @ElementCollection with nullable element type" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NullableElementEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String) = value
                        override fun fromSql(raw: String) = raw
                    }

                    @PersistenceMapping
                    data class NullableElementEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = TagConverter::class) val tags: Set<String?>
                    ) : ReactiveEntityBase<Int, NullableElementEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldFailWith(
            "@ElementCollection element type must be non-nullable",
            "test.NullableElementEntity.tags"
        )
    }

    // nullable collection type
    "rejects @ElementCollection with nullable collection type" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "NullableCollectionEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String) = value
                        override fun fromSql(raw: String) = raw
                    }

                    @PersistenceMapping
                    data class NullableCollectionEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = TagConverter::class) val tags: Set<String>?
                    ) : ReactiveEntityBase<Int, NullableCollectionEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldFailWith(
            "@ElementCollection property type must be non-nullable",
            "test.NullableCollectionEntity.tags"
        )
    }

    // @ElementCollection declared inside an @Embeddable
    "rejects @ElementCollection declared inside an @Embeddable" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "EmbeddableWithCollectionEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.Embeddable
                    import net.transgressoft.lirp.persistence.Embedded
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String) = value
                        override fun fromSql(raw: String) = raw
                    }

                    @Embeddable
                    data class Inner(
                        val label: String,
                        @ElementCollection(elementConverter = TagConverter::class) val tags: Set<String>
                    )

                    @PersistenceMapping
                    data class EmbeddableWithCollectionEntity(
                        override val id: Int,
                        @Embedded val inner: Inner
                    ) : ReactiveEntityBase<Int, EmbeddableWithCollectionEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldFailWith(
            "is not supported inside an @Embeddable",
            "test.Inner.tags"
        )
    }

    // missing/sentinel elementConverter
    "rejects @ElementCollection without an explicit elementConverter" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "MissingConverterEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    @PersistenceMapping
                    data class MissingConverterEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = ColumnConverter::class) val tags: Set<String>
                    ) : ReactiveEntityBase<Int, MissingConverterEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldFailWith(
            "requires an explicit `elementConverter`",
            "test.MissingConverterEntity.tags"
        )
    }

    // co-occurrence with @PersistenceProperty
    "rejects @ElementCollection co-occurring with @PersistenceProperty on the same property" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "CompositionConflictEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping
                    import net.transgressoft.lirp.persistence.PersistenceProperty

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String) = value
                        override fun fromSql(raw: String) = raw
                    }

                    @PersistenceMapping
                    data class CompositionConflictEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = TagConverter::class)
                        @PersistenceProperty(name = "tag_list")
                        val tags: Set<String>
                    ) : ReactiveEntityBase<Int, CompositionConflictEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldFailWith(
            "@ElementCollection and @PersistenceProperty cannot be combined",
            "test.CompositionConflictEntity.tags"
        )
    }

    // Positive coverage — both ctor-param and body-declared reactive-property targets compile.
    // `@ElementCollection` accepts any writable target: ctor-param `val`/`var` (populated via the
    // primary constructor) and body-declared `var x by reactiveProperty(...)` (populated via the
    // generated body-setter path). End-to-end round-trip coverage of these declaration forms lives
    // in ElementCollectionH2RoundTripTest and ElementCollectionDialectsIT.
    "accepts @ElementCollection on a body-declared var reactive property" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BodyDeclaredReactiveCollectionEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String) = value
                        override fun fromSql(raw: String) = raw
                    }

                    @PersistenceMapping
                    class BodyDeclaredReactiveCollectionEntity(id: Int) : ReactiveEntityBase<Int, BodyDeclaredReactiveCollectionEntity>() {
                        override val id: Int by reactiveProperty(id)
                        @ElementCollection(elementConverter = TagConverter::class)
                        var tags: List<String> by reactiveProperty(emptyList())
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BodyDeclaredReactiveCollectionEntity(id).also { it.tags = tags }
                    }
                    """
                )
            )

        result.shouldSucceed()
    }

    // body-declared read-only `val` has no setter to populate from a row
    "rejects @ElementCollection on a body-declared read-only val" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "BodyValCollectionEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object TagConverter : ColumnConverter<String, String> {
                        override val sqlType = ColumnType.TextType
                        override fun toSql(value: String) = value
                        override fun fromSql(raw: String) = raw
                    }

                    @PersistenceMapping
                    class BodyValCollectionEntity(id: Int) : ReactiveEntityBase<Int, BodyValCollectionEntity>() {
                        override val id: Int by reactiveProperty(id)
                        @ElementCollection(elementConverter = TagConverter::class)
                        val tags: List<String> = emptyList()
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = BodyValCollectionEntity(id)
                    }
                    """
                )
            )

        result.shouldFailWith(
            "@ElementCollection on a body-declared property requires a mutable `var`",
            "test.BodyValCollectionEntity.tags"
        )
    }

    // element-S type outside the 8 Kotlin primitives
    "rejects @ElementCollection with an element-S type outside the eight Kotlin primitives" {
        val result =
            KspTestSupport.compile(
                TableDefProcessorProvider(),
                SourceFile.kotlin(
                    "UuidConverterCollectionEntity.kt",
                    """
                    package test
                    import java.util.UUID
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.ColumnConverter
                    import net.transgressoft.lirp.persistence.ColumnType
                    import net.transgressoft.lirp.persistence.ElementCollection
                    import net.transgressoft.lirp.persistence.PersistenceMapping

                    object UuidConverter : ColumnConverter<UUID, UUID> {
                        override val sqlType = ColumnType.UuidType
                        override fun toSql(value: UUID) = value
                        override fun fromSql(raw: UUID) = raw
                    }

                    @PersistenceMapping
                    data class UuidConverterCollectionEntity(
                        override val id: Int,
                        @ElementCollection(elementConverter = UuidConverter::class) val ids: Set<UUID>
                    ) : ReactiveEntityBase<Int, UuidConverterCollectionEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.shouldFailWith(
            "@ElementCollection element converter's S type must be one of {String, Int, Long, Short, Byte, Boolean, Double, Float}",
            "test.UuidConverterCollectionEntity.ids"
        )
    }
})
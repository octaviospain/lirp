/******************************************************************************
 *     Copyright (C) 2025  Octavio Calleya Garcia                             *
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.engine.names.WithDataTestName
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for [ReactiveEntityRefProcessor], verifying that the processor generates
 * correct `_LirpRefAccessor` implementations for all supported entity shapes, including single-entity
 * references, collection-typed references, and error cases.
 *
 * Each test compiles a source entity in-process using kctfork and asserts on the generated file content.
 * Test entity classes use [net.transgressoft.lirp.entity.ReactiveEntityBase] as base class to satisfy
 * the [net.transgressoft.lirp.entity.IdentifiableEntity] contract without boilerplate.
 *
 * Collection delegate stubs are defined inline in the `net.transgressoft.lirp.persistence` package
 * so that the processor's FQN-based delegate detection works correctly. The stubs are only provided
 * in tests that need collection delegate types, keeping compilation isolated per test.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("ReactiveEntityRefProcessor")
internal class ReactiveEntityRefProcessorTest : FunSpec({

    // Shared stubs for collection reference delegates used in test source code.
    // These stubs live in net.transgressoft.lirp.persistence so the processor's FQN-based
    // detection works — the class names contain "List"/"Set" for isOrdered determination.
    // Shared stubs for collection reference delegates used in test source code.
    // These stubs live in net.transgressoft.lirp.persistence so the processor's source-text
    // factory call detection works. The stubs shadow the real internal delegate classes
    // (AggregateListRefDelegate, AggregateSetRefDelegate) and provide factory functions whose
    // return types implement ReadOnlyProperty so Kotlin delegation compiles in test source.
    // Mutable factories use the same pattern with distinct stub delegate class names to avoid
    // conflicts with the internal production delegate classes of the same name.
    val collectionDelegateStubs =
        SourceFile.kotlin(
            "CollectionDelegateStubs.kt",
            """
            package net.transgressoft.lirp.persistence

            import net.transgressoft.lirp.entity.IdentifiableEntity
            import kotlin.properties.ReadOnlyProperty
            import kotlin.reflect.KProperty

            class AggregateListRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
                private val initialIds: List<K> = emptyList()
            ) : AggregateCollectionRef<K, E>,
                ReadOnlyProperty<Any?, AggregateCollectionRef<K, E>> {
                override val referenceIds: List<K> get() = initialIds
                override fun resolveAll(): Collection<E> = emptyList()
                override fun getValue(thisRef: Any?, property: KProperty<*>): AggregateCollectionRef<K, E> = this
            }

            class AggregateSetRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
                private val initialIds: Set<K> = emptySet()
            ) : AggregateCollectionRef<K, E>,
                ReadOnlyProperty<Any?, AggregateCollectionRef<K, E>> {
                override val referenceIds: Set<K> get() = initialIds
                override fun resolveAll(): Collection<E> = emptySet()
                override fun getValue(thisRef: Any?, property: KProperty<*>): AggregateCollectionRef<K, E> = this
            }

            class MutableAggregateListStubDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
                private val initialIds: List<K> = emptyList()
            ) : AggregateCollectionRef<K, E>,
                ReadOnlyProperty<Any?, AggregateCollectionRef<K, E>> {
                override val referenceIds: List<K> get() = initialIds
                override fun resolveAll(): Collection<E> = emptyList()
                override fun getValue(thisRef: Any?, property: KProperty<*>): AggregateCollectionRef<K, E> = this
            }

            class MutableAggregateSetStubDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
                private val initialIds: Set<K> = emptySet()
            ) : AggregateCollectionRef<K, E>,
                ReadOnlyProperty<Any?, AggregateCollectionRef<K, E>> {
                override val referenceIds: Set<K> get() = initialIds
                override fun resolveAll(): Collection<E> = emptySet()
                override fun getValue(thisRef: Any?, property: KProperty<*>): AggregateCollectionRef<K, E> = this
            }

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateList(
                initialIds: List<K> = emptyList()
            ): AggregateListRefDelegate<K, E> = AggregateListRefDelegate(initialIds)

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateSet(
                initialIds: Set<K> = emptySet()
            ): AggregateSetRefDelegate<K, E> = AggregateSetRefDelegate(initialIds)

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> mutableAggregateList(
                initialIds: List<K> = emptyList()
            ): MutableAggregateListStubDelegate<K, E> = MutableAggregateListStubDelegate(initialIds)

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> mutableAggregateSet(
                initialIds: Set<K> = emptySet()
            ): MutableAggregateSetStubDelegate<K, E> = MutableAggregateSetStubDelegate(initialIds)
            """
        )

    fun compileWithProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.configureKsp { withCompilation = true }
        compilation.symbolProcessorProviders += ReactiveEntityRefProcessorProvider()
        return compilation.compile()
    }

    fun JvmCompilationResult.generatedFileContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error("Generated file '$name' not found among: ${sourcesGeneratedBySymbolProcessor.map { it.name }.toList()}")
        return file.readText()
    }

    test("generates entries for entity with single aggregate property") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "InvoiceEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    data class OrderEntity(override val id: Int) : ReactiveEntityBase<Int, OrderEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class InvoiceEntity(override val id: Int, var orderId: Int) : ReactiveEntityBase<Int, InvoiceEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val order by aggregate<Int, OrderEntity> { orderId }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("InvoiceEntity_LirpRefAccessor.kt")
        content shouldContain "override val entries: List<RefEntry<*, InvoiceEntity>>"
        content shouldContain "refName = \"order\""
        content shouldContain "idGetter = { it.order.referenceId }"
        content shouldContain "override val collectionEntries: List<CollectionRefEntry<*, InvoiceEntity>> = emptyList()"
    }

    test("generates collectionEntries for entity with aggregateList property") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "PlaylistEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.CascadeAction
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateList

                    data class TestTrack(override val id: Int) : ReactiveEntityBase<Int, TestTrack>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class PlaylistEntity(override val id: Int, val trackIds: List<Int>) : ReactiveEntityBase<Int, PlaylistEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate(onDelete = CascadeAction.CASCADE)
                        val items by aggregateList<Int, TestTrack>(trackIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PlaylistEntity_LirpRefAccessor.kt")
        content shouldContain "override val collectionEntries: List<CollectionRefEntry<*, PlaylistEntity>>"
        content shouldContain "refName = \"items\""
        content shouldContain "idsGetter = { (it.items as AggregateCollectionRef<*, *>).referenceIds }"
        content shouldContain "cascadeAction = CascadeAction.CASCADE"
        content shouldContain "isOrdered = true"
        content shouldContain "override val entries: List<RefEntry<*, PlaylistEntity>> = emptyList()"
    }

    test("generates collectionEntries for entity with aggregateSet property") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "PlaylistGroupEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateSet

                    data class PlaylistRef(override val id: Long) : ReactiveEntityBase<Long, PlaylistRef>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class PlaylistGroupEntity(override val id: Int, val playlistIds: Set<Long>) : ReactiveEntityBase<Int, PlaylistGroupEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val playlists by aggregateSet<Long, PlaylistRef>(playlistIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PlaylistGroupEntity_LirpRefAccessor.kt")
        content shouldContain "override val collectionEntries: List<CollectionRefEntry<*, PlaylistGroupEntity>>"
        content shouldContain "refName = \"playlists\""
        content shouldContain "idsGetter = { (it.playlists as AggregateCollectionRef<*, *>).referenceIds }"
        content shouldContain "isOrdered = false"
        content shouldContain "cascadeAction = CascadeAction.NONE"
    }

    test("generates both entries and collectionEntries for entity with mixed single and collection refs") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "AlbumEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.CascadeAction
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregate
                    import net.transgressoft.lirp.persistence.aggregateList

                    data class ArtistEntity(override val id: Int) : ReactiveEntityBase<Int, ArtistEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class TrackEntity(override val id: Int) : ReactiveEntityBase<Int, TrackEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class AlbumEntity(
                        override val id: Int,
                        var artistId: Int,
                        val trackIds: List<Int>
                    ) : ReactiveEntityBase<Int, AlbumEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate(bubbleUp = true)
                        val artist by aggregate<Int, ArtistEntity> { artistId }

                        @Aggregate(onDelete = CascadeAction.CASCADE)
                        val tracks by aggregateList<Int, TrackEntity>(trackIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("AlbumEntity_LirpRefAccessor.kt")
        // Single ref entry
        content shouldContain "override val entries: List<RefEntry<*, AlbumEntity>>"
        content shouldContain "refName = \"artist\""
        content shouldContain "idGetter = { it.artist.referenceId }"
        content shouldContain "bubbleUp = true"
        // Collection ref entry
        content shouldContain "override val collectionEntries: List<CollectionRefEntry<*, AlbumEntity>>"
        content shouldContain "refName = \"tracks\""
        content shouldContain "idsGetter = { (it.tracks as AggregateCollectionRef<*, *>).referenceIds }"
        content shouldContain "isOrdered = true"
        content shouldContain "cascadeAction = CascadeAction.CASCADE"
    }

    data class BubbleUpCase(
        val delegateFn: String,
        val idsType: String,
        val entityName: String,
        val refEntityName: String
    ) : WithDataTestName {
        override fun dataTestName() = delegateFn
    }

    context("emits compile error when bubbleUp=true on collection property") {
        withTests(
            BubbleUpCase("aggregateList", "List<Int>", "BubbleUpListEntity", "ItemEntity"),
            BubbleUpCase("aggregateSet", "Set<Int>", "BubbleUpSetEntity", "TagEntity")
        ) { case ->
            val result =
                compileWithProcessor(
                    collectionDelegateStubs,
                    SourceFile.kotlin(
                        "${case.entityName}.kt",
                        """
                        package test
                        import net.transgressoft.lirp.entity.ReactiveEntityBase
                        import net.transgressoft.lirp.persistence.Aggregate
                        import net.transgressoft.lirp.persistence.${case.delegateFn}

                        data class ${case.refEntityName}(override val id: Int) : ReactiveEntityBase<Int, ${case.refEntityName}>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                        }

                        data class ${case.entityName}(override val id: Int, val refIds: ${case.idsType}) : ReactiveEntityBase<Int, ${case.entityName}>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()

                            @Aggregate(bubbleUp = true)
                            val refs by ${case.delegateFn}<Int, ${case.refEntityName}> { refIds }
                        }
                        """
                    )
                )

            result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            result.messages shouldContain "bubbleUp"
            result.messages shouldContain "not supported"
        }
    }

    test("generates isOrdered=true for aggregateList and isOrdered=false for aggregateSet") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "MixedCollectionEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateList
                    import net.transgressoft.lirp.persistence.aggregateSet

                    data class RefEntity(override val id: Int) : ReactiveEntityBase<Int, RefEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class MixedCollectionEntity(
                        override val id: Int,
                        val orderedIds: List<Int>,
                        val uniqueIds: Set<Int>
                    ) : ReactiveEntityBase<Int, MixedCollectionEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val orderedRefs by aggregateList<Int, RefEntity>(orderedIds)

                        @Aggregate
                        val uniqueRefs by aggregateSet<Int, RefEntity>(uniqueIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MixedCollectionEntity_LirpRefAccessor.kt")
        // Split by CollectionRefEntry blocks and verify each refName is paired with its isOrdered
        val entryBlocks = content.split("CollectionRefEntry(").drop(1)
        val orderedBlock = entryBlocks.first { it.contains("refName = \"orderedRefs\"") }
        val uniqueBlock = entryBlocks.first { it.contains("refName = \"uniqueRefs\"") }
        orderedBlock shouldContain "isOrdered = true"
        uniqueBlock shouldContain "isOrdered = false"
    }

    test("uses @Aggregate annotation for detection and ignores unannotated properties") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "PartialAnnotationEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateList
                    import net.transgressoft.lirp.persistence.aggregateSet

                    data class SomeEntity(override val id: Int) : ReactiveEntityBase<Int, SomeEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class PartialAnnotationEntity(
                        override val id: Int,
                        val annotatedIds: List<Int>,
                        val ignoredIds: Set<Int>
                    ) : ReactiveEntityBase<Int, PartialAnnotationEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val annotatedRefs by aggregateList<Int, SomeEntity>(annotatedIds)

                        // No @Aggregate annotation — should NOT appear in generated accessor
                        val ignoredRefs by aggregateSet<Int, SomeEntity>(ignoredIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PartialAnnotationEntity_LirpRefAccessor.kt")
        content shouldContain "refName = \"annotatedRefs\""
        content shouldNotContain "ignoredRefs"
    }

    test("generates \$-separated accessor name for 1-level inner class entity") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "Outer.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    class Outer {
                        data class InnerEntity(override val id: Int) : ReactiveEntityBase<Int, InnerEntity>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                        }

                        data class RefEntity(override val id: Int, var innerId: Int) : ReactiveEntityBase<Int, RefEntity>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()

                            @Aggregate
                            val inner by aggregate<Int, InnerEntity> { innerId }
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("Outer\$RefEntity_LirpRefAccessor.kt")
        content shouldContain "`Outer\$RefEntity_LirpRefAccessor`"
        content shouldContain "LirpRefAccessor<Outer.RefEntity>"
        content shouldContain "InnerEntity::class.java"
    }

    test("generates \$-separated accessor name for 3-level nested entity") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "A.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    class A {
                        class B {
                            data class RefEntity(override val id: Int) : ReactiveEntityBase<Int, RefEntity>() {
                                override val uniqueId: String get() = "${'$'}id"
                                override fun clone() = copy()
                            }
                            data class C(override val id: Int, var refId: Int) : ReactiveEntityBase<Int, C>() {
                                override val uniqueId: String get() = "${'$'}id"
                                override fun clone() = copy()

                                @Aggregate
                                val ref by aggregate<Int, RefEntity> { refId }
                            }
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("A\$B\$C_LirpRefAccessor.kt")
        content shouldContain "`A\$B\$C_LirpRefAccessor`"
        content shouldContain "LirpRefAccessor<A.B.C>"
        content shouldContain "RefEntity::class.java"
    }

    test("top-level entity accessor generation unchanged after inner class support") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "TopLevelEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    data class TargetEntity(override val id: Int) : ReactiveEntityBase<Int, TargetEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class TopLevelEntity(override val id: Int, var targetId: Int) : ReactiveEntityBase<Int, TopLevelEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val target by aggregate<Int, TargetEntity> { targetId }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("TopLevelEntity_LirpRefAccessor.kt")
        content shouldContain "`TopLevelEntity_LirpRefAccessor`"
        content shouldContain "LirpRefAccessor<TopLevelEntity>"
        content shouldContain "TargetEntity::class.java"
    }

    test("generates collectionEntries for entity with mutableAggregateList returning MutableList") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "MutablePlaylistEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.CascadeAction
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.mutableAggregateList

                    data class TestTrack(override val id: Int) : ReactiveEntityBase<Int, TestTrack>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class MutablePlaylistEntity(override val id: Int, val trackIds: List<Int>) : ReactiveEntityBase<Int, MutablePlaylistEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate(onDelete = CascadeAction.CASCADE)
                        val items by mutableAggregateList<Int, TestTrack>(trackIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MutablePlaylistEntity_LirpRefAccessor.kt")
        content shouldContain "override val collectionEntries: List<CollectionRefEntry<*, MutablePlaylistEntity>>"
        content shouldContain "refName = \"items\""
        content shouldContain "idsGetter = { (it.items as AggregateCollectionRef<*, *>).referenceIds }"
        content shouldContain "cascadeAction = CascadeAction.CASCADE"
        content shouldContain "isOrdered = true"
        content shouldContain "override val entries: List<RefEntry<*, MutablePlaylistEntity>> = emptyList()"
    }
})
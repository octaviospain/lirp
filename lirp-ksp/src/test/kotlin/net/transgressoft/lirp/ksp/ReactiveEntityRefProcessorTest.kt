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
    val collectionDelegateStubs =
        SourceFile.kotlin(
            "CollectionDelegateStubs.kt",
            """
            package net.transgressoft.lirp.persistence

            import net.transgressoft.lirp.entity.IdentifiableEntity
            import kotlin.properties.ReadOnlyProperty
            import kotlin.reflect.KProperty

            class AggregateListRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
                private val idProvider: () -> List<K>
            ) : ReactiveEntityCollectionReference<K, E>,
                ReadOnlyProperty<Any?, ReactiveEntityCollectionReference<K, E>> {
                override val referenceIds: List<K> get() = idProvider()
                override fun resolveAll(): Collection<E> = emptyList()
                override fun getValue(thisRef: Any?, property: KProperty<*>): ReactiveEntityCollectionReference<K, E> = this
            }

            class AggregateSetRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
                private val idProvider: () -> Set<K>
            ) : ReactiveEntityCollectionReference<K, E>,
                ReadOnlyProperty<Any?, ReactiveEntityCollectionReference<K, E>> {
                override val referenceIds: Set<K> get() = idProvider()
                override fun resolveAll(): Collection<E> = emptySet()
                override fun getValue(thisRef: Any?, property: KProperty<*>): ReactiveEntityCollectionReference<K, E> = this
            }

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateList(
                idProvider: () -> List<K>
            ): AggregateListRefDelegate<K, E> = AggregateListRefDelegate(idProvider)

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateSet(
                idProvider: () -> Set<K>
            ): AggregateSetRefDelegate<K, E> = AggregateSetRefDelegate(idProvider)

            interface MutableReactiveEntityCollectionReference<K : Comparable<K>, E : IdentifiableEntity<K>> :
                ReactiveEntityCollectionReference<K, E>, MutableCollection<E>

            abstract class AbstractMutableAggregateCollectionRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>> :
                MutableReactiveEntityCollectionReference<K, E>,
                ReadOnlyProperty<Any?, MutableReactiveEntityCollectionReference<K, E>> {
                abstract override val referenceIds: Collection<K>
                override fun resolveAll(): Collection<E> = emptyList()
                override val size: Int get() = 0
                override fun contains(element: E): Boolean = false
                override fun containsAll(elements: Collection<E>): Boolean = false
                override fun isEmpty(): Boolean = true
                override fun add(element: E): Boolean = true
                override fun addAll(elements: Collection<E>): Boolean = true
                override fun clear() {}
                override fun iterator(): MutableIterator<E> = mutableListOf<E>().iterator()
                override fun remove(element: E): Boolean = true
                override fun removeAll(elements: Collection<E>): Boolean = true
                override fun retainAll(elements: Collection<E>): Boolean = true
            }

            class MutableAggregateListRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
                private val idProvider: () -> List<K>
            ) : AbstractMutableAggregateCollectionRefDelegate<K, E>() {
                override val referenceIds: List<K> get() = idProvider()
                override fun getValue(thisRef: Any?, property: KProperty<*>): MutableReactiveEntityCollectionReference<K, E> = this
            }

            class MutableAggregateSetRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
                private val idProvider: () -> Set<K>
            ) : AbstractMutableAggregateCollectionRefDelegate<K, E>() {
                override val referenceIds: Set<K> get() = idProvider()
                override fun getValue(thisRef: Any?, property: KProperty<*>): MutableReactiveEntityCollectionReference<K, E> = this
            }

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> mutableAggregateList(
                idProvider: () -> List<K>
            ): MutableAggregateListRefDelegate<K, E> = MutableAggregateListRefDelegate(idProvider)

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> mutableAggregateSet(
                idProvider: () -> Set<K>
            ): MutableAggregateSetRefDelegate<K, E> = MutableAggregateSetRefDelegate(idProvider)
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
                        val items by aggregateList<Int, TestTrack> { trackIds }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PlaylistEntity_LirpRefAccessor.kt")
        content shouldContain "override val collectionEntries: List<CollectionRefEntry<*, PlaylistEntity>>"
        content shouldContain "refName = \"items\""
        content shouldContain "idsGetter = { it.items.referenceIds }"
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
                        val playlists by aggregateSet<Long, PlaylistRef> { playlistIds }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PlaylistGroupEntity_LirpRefAccessor.kt")
        content shouldContain "override val collectionEntries: List<CollectionRefEntry<*, PlaylistGroupEntity>>"
        content shouldContain "refName = \"playlists\""
        content shouldContain "idsGetter = { it.playlists.referenceIds }"
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
                        val tracks by aggregateList<Int, TrackEntity> { trackIds }
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
        content shouldContain "idsGetter = { it.tracks.referenceIds }"
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
            BubbleUpCase("aggregateSet", "Set<Int>", "BubbleUpSetEntity", "TagEntity"),
            BubbleUpCase("mutableAggregateList", "List<Int>", "BubbleUpMutableListEntity", "MutableItemEntity"),
            BubbleUpCase("mutableAggregateSet", "Set<Int>", "BubbleUpMutableSetEntity", "MutableTagEntity")
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
                        val orderedRefs by aggregateList<Int, RefEntity> { orderedIds }

                        @Aggregate
                        val uniqueRefs by aggregateSet<Int, RefEntity> { uniqueIds }
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
                        val annotatedRefs by aggregateList<Int, SomeEntity> { annotatedIds }

                        // No @Aggregate annotation — should NOT appear in generated accessor
                        val ignoredRefs by aggregateSet<Int, SomeEntity> { ignoredIds }
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

    test("generates collectionEntries for entity with mutableAggregateList property") {
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
                        val tracks by mutableAggregateList<Int, TestTrack> { trackIds }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MutablePlaylistEntity_LirpRefAccessor.kt")
        content shouldContain "override val collectionEntries: List<CollectionRefEntry<*, MutablePlaylistEntity>>"
        content shouldContain "refName = \"tracks\""
        content shouldContain "idsGetter = { it.tracks.referenceIds }"
        content shouldContain "isOrdered = true"
        content shouldContain "cascadeAction = CascadeAction.CASCADE"
    }

    test("generates collectionEntries for entity with mutableAggregateSet property") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "MutableTagEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.mutableAggregateSet

                    data class TagRef(override val id: Long) : ReactiveEntityBase<Long, TagRef>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class MutableTagEntity(override val id: Int, val tagIds: Set<Long>) : ReactiveEntityBase<Int, MutableTagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val tags by mutableAggregateSet<Long, TagRef> { tagIds }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MutableTagEntity_LirpRefAccessor.kt")
        content shouldContain "override val collectionEntries: List<CollectionRefEntry<*, MutableTagEntity>>"
        content shouldContain "refName = \"tags\""
        content shouldContain "idsGetter = { it.tags.referenceIds }"
        content shouldContain "isOrdered = false"
        content shouldContain "cascadeAction = CascadeAction.NONE"
    }

    test("generates collectionEntries for entity with mixed immutable and mutable collection refs") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "MixedMutableImmutableEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateList
                    import net.transgressoft.lirp.persistence.mutableAggregateSet

                    data class TrackRef(override val id: Int) : ReactiveEntityBase<Int, TrackRef>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class TagRef(override val id: Int) : ReactiveEntityBase<Int, TagRef>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class MixedMutableImmutableEntity(
                        override val id: Int,
                        val trackIds: List<Int>,
                        val tagIds: Set<Int>
                    ) : ReactiveEntityBase<Int, MixedMutableImmutableEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val immutableTracks by aggregateList<Int, TrackRef> { trackIds }

                        @Aggregate
                        val mutableTags by mutableAggregateSet<Int, TagRef> { tagIds }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MixedMutableImmutableEntity_LirpRefAccessor.kt")
        val entryBlocks = content.split("CollectionRefEntry(").drop(1)
        val immutableTracksBlock = entryBlocks.first { it.contains("refName = \"immutableTracks\"") }
        val mutableTagsBlock = entryBlocks.first { it.contains("refName = \"mutableTags\"") }
        immutableTracksBlock shouldContain "isOrdered = true"
        mutableTagsBlock shouldContain "isOrdered = false"
    }

    data class MutableCascadeCase(
        val delegateFn: String,
        val cascadeAction: String,
        val entityName: String,
        val isOrdered: Boolean
    ) : WithDataTestName {
        override fun dataTestName() = "$delegateFn with $cascadeAction"
    }

    context("generates correct cascadeAction for mutable collection delegates with all cascade modes") {
        withTests(
            MutableCascadeCase("mutableAggregateList", "RESTRICT", "MutableRestrictEntity", true),
            MutableCascadeCase("mutableAggregateSet", "DETACH", "MutableDetachEntity", false),
            MutableCascadeCase("mutableAggregateList", "NONE", "MutableNoneEntity", true)
        ) { case ->
            val idsType = if (case.isOrdered) "List<Int>" else "Set<Int>"
            val result =
                compileWithProcessor(
                    collectionDelegateStubs,
                    SourceFile.kotlin(
                        "${case.entityName}.kt",
                        """
                        package test
                        import net.transgressoft.lirp.entity.CascadeAction
                        import net.transgressoft.lirp.entity.ReactiveEntityBase
                        import net.transgressoft.lirp.persistence.Aggregate
                        import net.transgressoft.lirp.persistence.${case.delegateFn}

                        data class RefEntity(override val id: Int) : ReactiveEntityBase<Int, RefEntity>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()
                        }

                        data class ${case.entityName}(override val id: Int, val refIds: $idsType) : ReactiveEntityBase<Int, ${case.entityName}>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()

                            @Aggregate(onDelete = CascadeAction.${case.cascadeAction})
                            val refs by ${case.delegateFn}<Int, RefEntity> { refIds }
                        }
                        """
                    )
                )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            val content = result.generatedFileContent("${case.entityName}_LirpRefAccessor.kt")
            content shouldContain "cascadeAction = CascadeAction.${case.cascadeAction}"
            content shouldContain "isOrdered = ${case.isOrdered}"
        }
    }

    test("mutable delegate default cascadeAction is NONE when onDelete not specified") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "MutableDefaultCascadeEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.mutableAggregateList

                    data class RefEntity(override val id: Int) : ReactiveEntityBase<Int, RefEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class MutableDefaultCascadeEntity(override val id: Int, val itemIds: List<Int>) : ReactiveEntityBase<Int, MutableDefaultCascadeEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val items by mutableAggregateList<Int, RefEntity> { itemIds }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("MutableDefaultCascadeEntity_LirpRefAccessor.kt")
        content shouldContain "cascadeAction = CascadeAction.NONE"
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
})
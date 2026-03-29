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

    test("ReactiveEntityRefProcessor generates entries for entity with single aggregate property") {
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

    test("ReactiveEntityRefProcessor generates collectionEntries for entity with aggregateList property") {
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

    test("ReactiveEntityRefProcessor generates collectionEntries for entity with aggregateSet property") {
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

    test("ReactiveEntityRefProcessor generates both entries and collectionEntries for entity with mixed single and collection refs") {
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

    test("ReactiveEntityRefProcessor emits compile error when bubbleUp=true on aggregateList property") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "BubbleUpListEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateList

                    data class ItemEntity(override val id: Int) : ReactiveEntityBase<Int, ItemEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class BubbleUpListEntity(override val id: Int, val itemIds: List<Int>) : ReactiveEntityBase<Int, BubbleUpListEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate(bubbleUp = true)
                        val items by aggregateList<Int, ItemEntity> { itemIds }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "bubbleUp"
        result.messages shouldContain "not supported"
    }

    test("ReactiveEntityRefProcessor emits compile error when bubbleUp=true on aggregateSet property") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "BubbleUpSetEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateSet

                    data class TagEntity(override val id: Int) : ReactiveEntityBase<Int, TagEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class BubbleUpSetEntity(override val id: Int, val tagIds: Set<Int>) : ReactiveEntityBase<Int, BubbleUpSetEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate(bubbleUp = true)
                        val tags by aggregateSet<Int, TagEntity> { tagIds }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
        result.messages shouldContain "bubbleUp"
        result.messages shouldContain "not supported"
    }

    test("ReactiveEntityRefProcessor generates isOrdered=true for aggregateList and isOrdered=false for aggregateSet") {
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

    test("ReactiveEntityRefProcessor uses @Aggregate annotation for detection and ignores unannotated properties") {
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
})
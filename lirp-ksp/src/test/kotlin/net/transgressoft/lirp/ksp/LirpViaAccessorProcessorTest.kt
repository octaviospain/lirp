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
 * KSP compilation tests for [LirpViaAccessorProcessor], verifying that the processor generates
 * the cross-aggregate `_LirpViaAccessor` implementations expected by the Wave 4 RegistryBase
 * discovery and the Phase 54 Query DSL planner.
 *
 * Each test compiles a source entity in-process using kctfork and asserts on the generated file
 * content. Collection delegate stubs live in `net.transgressoft.lirp.persistence` so the
 * processor's source-text and FQN-based detection work in the isolated test compilation.
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("LirpViaAccessorProcessor")
internal class LirpViaAccessorProcessorTest : FunSpec({

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

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateList(
                initialIds: List<K> = emptyList()
            ): AggregateListRefDelegate<K, E> = AggregateListRefDelegate(initialIds)

            fun <K : Comparable<K>, E : IdentifiableEntity<K>> aggregateSet(
                initialIds: Set<K> = emptySet()
            ): AggregateSetRefDelegate<K, E> = AggregateSetRefDelegate(initialIds)
            """
        )

    fun compileWithProcessor(vararg sources: SourceFile): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.configureKsp { withCompilation = true }
        compilation.symbolProcessorProviders += LirpViaAccessorProcessorProvider()
        return compilation.compile()
    }

    fun JvmCompilationResult.generatedFileContent(name: String): String {
        val file =
            sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
                ?: error("Generated file '$name' not found among: ${sourcesGeneratedBySymbolProcessor.map { it.name }.toList()}")
        return file.readText()
    }

    test("emits accessor file declaring LirpViaAccessor implementation for single-ref entity") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "OrderEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregate

                    data class CustomerEntity(override val id: Int) : ReactiveEntityBase<Int, CustomerEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class OrderEntity(override val id: Int, var customerId: Int) : ReactiveEntityBase<Int, OrderEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val customer by aggregate<Int, CustomerEntity> { customerId }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("OrderEntity_LirpViaAccessor.kt")
        content shouldContain "`OrderEntity_LirpViaAccessor` : LirpViaAccessor<OrderEntity>"
        content shouldContain "override val singleEntries: List<ViaSingleAccessorEntry<*, OrderEntity>>"
        content shouldContain "refName = \"customer\""
        content shouldContain "CustomerEntity::class.java"
        content shouldContain "override val collectionEntries: List<ViaCollectionAccessorEntry<*, OrderEntity>> = emptyList()"
        content shouldContain "@Suppress(\"UNCHECKED_CAST\")"
        content shouldContain "import kotlin.reflect.KProperty1"
    }

    test("emits ViaCollectionAccessorEntry for collection-typed aggregateList property") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "PlaylistEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateList

                    data class TrackRef(override val id: Int) : ReactiveEntityBase<Int, TrackRef>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class PlaylistEntity(override val id: Int, val trackIds: List<Int>) : ReactiveEntityBase<Int, PlaylistEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val tracks by aggregateList<Int, TrackRef>(trackIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("PlaylistEntity_LirpViaAccessor.kt")
        content shouldContain "override val collectionEntries: List<ViaCollectionAccessorEntry<*, PlaylistEntity>>"
        content shouldContain "ViaCollectionAccessorEntry("
        content shouldContain "refName = \"tracks\""
        content shouldContain "PlaylistEntity::tracks"
        content shouldContain "TrackRef::class.java"
        content shouldContain "override val singleEntries: List<ViaSingleAccessorEntry<*, PlaylistEntity>> = emptyList()"
    }

    test("emits ViaCollectionAccessorEntry for aggregateSet property without distinguishing list/set") {
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
        val content = result.generatedFileContent("PlaylistGroupEntity_LirpViaAccessor.kt")
        content shouldContain "override val collectionEntries: List<ViaCollectionAccessorEntry<*, PlaylistGroupEntity>>"
        content shouldContain "refName = \"playlists\""
        content shouldContain "PlaylistRef::class.java"
        // No isOrdered flag — via does not distinguish list vs set
        content shouldNotContain "isOrdered"
    }

    test("emits both collection and single entries on the same accessor file") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "AlbumEntity.kt",
                    """
                    package test
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

                        @Aggregate
                        val artist by aggregate<Int, ArtistEntity> { artistId }

                        @Aggregate
                        val tracks by aggregateList<Int, TrackEntity>(trackIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("AlbumEntity_LirpViaAccessor.kt")
        // Single ref
        content shouldContain "refName = \"artist\""
        content shouldContain "ArtistEntity::class.java"
        // Collection ref
        content shouldContain "refName = \"tracks\""
        content shouldContain "TrackEntity::class.java"
        // Both lists populated, neither emptyList()
        content shouldNotContain "singleEntries: List<ViaSingleAccessorEntry<*, AlbumEntity>> = emptyList()"
        content shouldNotContain "collectionEntries: List<ViaCollectionAccessorEntry<*, AlbumEntity>> = emptyList()"
    }

    test("produces no accessor file for entity without @Aggregate properties") {
        val result =
            compileWithProcessor(
                SourceFile.kotlin(
                    "PlainEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class PlainEntity(override val id: Int, val name: String) : ReactiveEntityBase<Int, PlainEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val generatedNames = result.sourcesGeneratedBySymbolProcessor.map { it.name }
        generatedNames.none { it == "PlainEntity_LirpViaAccessor.kt" } shouldBe true
    }

    test("falls back to type-based detection when the property has no source-text aggregate factory call") {
        // Exercises isCollectionReferenceByType / isCollectionReferenceFqn when readSourceLines
        // does not find any of the factory call markers ("aggregateList" / "aggregateSet" / etc.):
        // declaring the property's value directly through a constructor invocation triggers the
        // type-walking fallback in isCollectionReference.
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "NoFactoryCall.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.AggregateListRefDelegate

                    data class WidgetRef(override val id: Int) : ReactiveEntityBase<Int, WidgetRef>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class NoFactoryCall(override val id: Int, val widgetIds: List<Int>) :
                        ReactiveEntityBase<Int, NoFactoryCall>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val widgets: AggregateListRefDelegate<Int, WidgetRef> = AggregateListRefDelegate(widgetIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("NoFactoryCall_LirpViaAccessor.kt")
        content shouldContain "refName = \"widgets\""
        content shouldContain "WidgetRef::class.java"
    }

    test("emits one accessor file when entity has two @Aggregate properties") {
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "MultiRefEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateList

                    data class TagRef(override val id: Int) : ReactiveEntityBase<Int, TagRef>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class CategoryRef(override val id: Int) : ReactiveEntityBase<Int, CategoryRef>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class MultiRefEntity(
                        override val id: Int,
                        val tagIds: List<Int>,
                        val categoryIds: List<Int>
                    ) : ReactiveEntityBase<Int, MultiRefEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val tags by aggregateList<Int, TagRef>(tagIds)

                        @Aggregate
                        val categories by aggregateList<Int, CategoryRef>(categoryIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val accessorFiles = result.sourcesGeneratedBySymbolProcessor.filter { it.name == "MultiRefEntity_LirpViaAccessor.kt" }.toList()
        accessorFiles.size shouldBe 1
        val content = accessorFiles.single().readText()
        content shouldContain "refName = \"tags\""
        content shouldContain "refName = \"categories\""
        content shouldContain "TagRef::class.java"
        content shouldContain "CategoryRef::class.java"
    }

    test("nested entity classes produce accessor files whose package matches the enclosing file") {
        // Exercises the kotlinNestedName / jvmBinaryName path for entities declared inside a
        // container object: the accessor file must still land in the same package and reference
        // the binary class name correctly.
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "NestedHost.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateList

                    data class CommentRef(override val id: Int) : ReactiveEntityBase<Int, CommentRef>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    object NestedHost {
                        data class PostEntity(override val id: Int, val commentIds: List<Int>) :
                            ReactiveEntityBase<Int, PostEntity>() {
                            override val uniqueId: String get() = "${'$'}id"
                            override fun clone() = copy()

                            @Aggregate
                            val comments by aggregateList<Int, CommentRef>(commentIds)
                        }
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        // Accessor file naming follows the binary class name (with `${'$'}` separator) but the
        // enclosing package stays `test`. Look up the file by package qualified name.
        val accessor =
            result.sourcesGeneratedBySymbolProcessor.firstOrNull { it.name.endsWith("PostEntity_LirpViaAccessor.kt") }
                ?: error("Accessor for nested PostEntity not generated; got ${result.sourcesGeneratedBySymbolProcessor.map { it.name }.toList()}")
        val content = accessor.readText()
        content shouldContain "refName = \"comments\""
        content shouldContain "CommentRef::class.java"
        content shouldContain "package test"
    }

    test("generic referenced classes resolve to the bound entity in the type argument") {
        // Exercises the entity-class FQN resolution when the entity carries a generic parameter:
        // the processor must read the resolved KSType, not the declaration name, so the bound
        // type (UserNotification) lands in the generated descriptor rather than the unbound generic.
        val result =
            compileWithProcessor(
                collectionDelegateStubs,
                SourceFile.kotlin(
                    "GenericEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Aggregate
                    import net.transgressoft.lirp.persistence.aggregateList

                    data class UserNotification(override val id: Int, val message: String) :
                        ReactiveEntityBase<Int, UserNotification>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                    }

                    data class Inbox(override val id: Int, val notificationIds: List<Int>) :
                        ReactiveEntityBase<Int, Inbox>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()

                        @Aggregate
                        val notifications by aggregateList<Int, UserNotification>(notificationIds)
                    }
                    """
                )
            )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        val content = result.generatedFileContent("Inbox_LirpViaAccessor.kt")
        content shouldContain "refName = \"notifications\""
        content shouldContain "UserNotification::class.java"
        // Resolved class FQN imports must include the referenced entity once, sorted.
        content shouldContain "import test.UserNotification"
    }
})
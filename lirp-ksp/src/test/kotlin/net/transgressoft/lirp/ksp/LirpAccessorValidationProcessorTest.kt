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

import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName

/**
 * KSP compilation tests for [LirpAccessorValidationProcessor], verifying that the processor
 * emits build errors when entity classes have @Indexed or FxScalar delegates but no corresponding
 * generated accessor class exists, and succeeds when both accessors are properly generated.
 *
 * Tests use kctfork in-process compilation with controlled sets of processors to simulate
 * missing-processor scenarios. All tests use the real lirp-core/lirp-api classpath via
 * `inheritClassPath = true`. FxScalar property stubs are provided only for the delegate
 * implementations (not the marker interface, which lives in lirp-core).
 */
@OptIn(ExperimentalCompilerApi::class)
@DisplayName("LirpAccessorValidationProcessor")
internal class LirpAccessorValidationProcessorTest : StringSpec({

    // Stub implementations of FxScalarPropertyDelegate using the real interface from lirp-core.
    // Placed in net.transgressoft.lirp.persistence so the processor's FQN-based detection works.
    val fxPropertyStubs =
        SourceFile.kotlin(
            "FxPropertyStubs.kt",
            """
            package net.transgressoft.lirp.persistence

            import kotlin.reflect.KProperty

            class StubStringProperty(private var value: String? = null) : FxScalarPropertyDelegate {
                override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
                fun get(): String? = value
                fun set(v: String?) { value = v }
                operator fun getValue(thisRef: Any?, property: KProperty<*>): StubStringProperty = this
            }
            """
        )

    "emits build error when entity has @Indexed delegates but no generated LirpIndexAccessor" {
        val result =
            KspTestSupport.compile(
                LirpAccessorValidationProcessorProvider(),
                SourceFile.kotlin(
                    "OrderEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    data class OrderEntity(override val id: Int) : ReactiveEntityBase<Int, OrderEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed val status: String = "NEW"
                    }
                    """
                )
            )

        result.shouldFailWith("has @Indexed delegates but no generated LirpIndexAccessor")
    }

    "emits build error when entity has FxScalar delegates but no generated LirpFxScalarAccessor" {
        val result =
            KspTestSupport.compile(
                LirpAccessorValidationProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "TrackEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.StubStringProperty

                    data class TrackEntity(override val id: Int) : ReactiveEntityBase<Int, TrackEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        val name by StubStringProperty()
                    }
                    """
                )
            )

        result.shouldFailWith("has FxScalar delegates but no generated LirpFxScalarAccessor")
    }

    "compiles successfully when entity with @Indexed delegates has IndexedProcessor registered" {
        // Both the generator and the validator are registered — no false positive expected.
        // RawInitializerProcessor is registered to satisfy the raw-init validation now applied
        // to every persisted entity.
        val result =
            KspTestSupport.compile(
                providers =
                    listOf(
                        IndexedProcessorProvider(),
                        RawInitializerProcessorProvider(),
                        LirpAccessorValidationProcessorProvider()
                    ),
                sources =
                    listOf(
                        SourceFile.kotlin(
                            "ProductEntity.kt",
                            """
                            package test
                            import net.transgressoft.lirp.entity.ReactiveEntityBase
                            import net.transgressoft.lirp.persistence.Indexed

                            data class ProductEntity(override val id: Int) : ReactiveEntityBase<Int, ProductEntity>() {
                                override val uniqueId: String get() = "${'$'}id"
                                override fun clone() = copy()
                                @Indexed val code: String = "SKU-001"
                            }
                            """
                        )
                    )
            )

        result.shouldSucceed()
        result.messages shouldNotContain "has @Indexed delegates but no generated LirpIndexAccessor"
    }

    "compiles successfully when entity with FxScalar delegates has FxScalarAccessorProcessor registered" {
        // Both the generator and the validator are registered — no false positive expected.
        // RawInitializerProcessor is registered to satisfy the raw-init validation now applied
        // to every persisted entity.
        val result =
            KspTestSupport.compile(
                providers =
                    listOf(
                        FxScalarAccessorProcessorProvider(),
                        RawInitializerProcessorProvider(),
                        LirpAccessorValidationProcessorProvider()
                    ),
                sources =
                    listOf(
                        fxPropertyStubs,
                        SourceFile.kotlin(
                            "TrackEntity.kt",
                            """
                            package test
                            import net.transgressoft.lirp.entity.ReactiveEntityBase
                            import net.transgressoft.lirp.persistence.StubStringProperty

                            data class TrackEntity(override val id: Int) : ReactiveEntityBase<Int, TrackEntity>() {
                                override val uniqueId: String get() = "${'$'}id"
                                override fun clone() = copy()
                                val name by StubStringProperty()
                            }
                            """
                        )
                    )
            )

        result.shouldSucceed()
        result.messages shouldNotContain "has FxScalar delegates but no generated LirpFxScalarAccessor"
    }

    "does not emit validation error for non-entity class with FxScalar-typed properties" {
        val result =
            KspTestSupport.compile(
                LirpAccessorValidationProcessorProvider(),
                fxPropertyStubs,
                SourceFile.kotlin(
                    "NonEntityHelper.kt",
                    """
                    package test
                    import net.transgressoft.lirp.persistence.StubStringProperty

                    // This class does NOT extend ReactiveEntityBase or implement IdentifiableEntity
                    class NonEntityHelper {
                        val label by StubStringProperty()
                    }
                    """
                )
            )

        result.shouldSucceed()
        result.messages shouldNotContain "has FxScalar delegates but no generated LirpFxScalarAccessor"
    }

    "fails build when entity has reactive-property fields but no LirpReactivePropertyAccessor" {
        val result =
            KspTestSupport.compile(
                LirpAccessorValidationProcessorProvider(),
                SourceFile.kotlin(
                    "ReactiveEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase

                    data class ReactiveEntity(override val id: Int) : ReactiveEntityBase<Int, ReactiveEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        var x: Int by reactiveProperty(0)
                    }
                    """
                )
            )

        // Lock the failure path explicitly: the entity has reactive-property delegates AND no
        // raw initializer, so generic hint matching alone could pass on the wrong branch.
        // Assert on the reactive-accessor message and the entity name to pin the intent.
        result.shouldFailWith(
            "ReactiveEntity",
            "LirpReactivePropertyAccessor",
            "apply the net.transgressoft.lirp.sql Gradle plugin or add lirp-ksp to your build.gradle dependencies block"
        )
    }

    "error message contains entity name, missing accessor type, and fix hint" {
        val result =
            KspTestSupport.compile(
                LirpAccessorValidationProcessorProvider(),
                SourceFile.kotlin(
                    "CustomerEntity.kt",
                    """
                    package test
                    import net.transgressoft.lirp.entity.ReactiveEntityBase
                    import net.transgressoft.lirp.persistence.Indexed

                    data class CustomerEntity(override val id: Int) : ReactiveEntityBase<Int, CustomerEntity>() {
                        override val uniqueId: String get() = "${'$'}id"
                        override fun clone() = copy()
                        @Indexed val email: String = ""
                    }
                    """
                )
            )

        result.shouldFailWith("CustomerEntity", "LirpIndexAccessor", "Ensure lirp-ksp is applied")
    }

    "LirpAccessorValidationProcessor validates an internal entity without false-positive" {
        // All generators plus the validator registered — internal entity must pass validation
        val result =
            KspTestSupport.compile(
                providers =
                    listOf(
                        IndexedProcessorProvider(),
                        RawInitializerProcessorProvider(),
                        LirpAccessorValidationProcessorProvider()
                    ),
                sources =
                    listOf(
                        SourceFile.kotlin(
                            "InternalValidated.kt",
                            """
                            package test
                            import net.transgressoft.lirp.entity.ReactiveEntityBase
                            import net.transgressoft.lirp.persistence.Indexed

                            internal data class InternalValidated(override val id: Int) : ReactiveEntityBase<Int, InternalValidated>() {
                                override val uniqueId: String get() = "${'$'}id"
                                override fun clone() = copy()
                                @Indexed val code: String = "A"
                            }
                            """
                        )
                    )
            )

        result.shouldSucceed()
        result.messages shouldNotContain "has @Indexed delegates but no generated LirpIndexAccessor"
        result.messages shouldNotContain "has no generated LirpRawInitializer"
    }

    "fails build when entity is missing LirpRawInitializer" {
        // ReactivePropertyAccessor + FxScalar generators present but NOT RawInitializerProcessor —
        // the validator must surface the missing raw initializer with the remediation message.
        val result =
            KspTestSupport.compile(
                providers =
                    listOf(
                        ReactivePropertyAccessorProcessorProvider(),
                        FxScalarAccessorProcessorProvider(),
                        LirpAccessorValidationProcessorProvider()
                    ),
                sources =
                    listOf(
                        fxPropertyStubs,
                        SourceFile.kotlin(
                            "OrderEntity.kt",
                            """
                            package test
                            import net.transgressoft.lirp.entity.ReactiveEntityBase
                            import net.transgressoft.lirp.persistence.StubStringProperty

                            data class OrderEntity(override val id: Int) : ReactiveEntityBase<Int, OrderEntity>() {
                                override val uniqueId: String get() = "${'$'}id"
                                override fun clone() = copy()
                                val name by StubStringProperty()
                            }
                            """
                        )
                    )
            )

        result.shouldFailWith(
            "has no generated LirpRawInitializer",
            "apply the net.transgressoft.lirp.sql Gradle plugin or add lirp-ksp to your build.gradle dependencies block"
        )
    }
})
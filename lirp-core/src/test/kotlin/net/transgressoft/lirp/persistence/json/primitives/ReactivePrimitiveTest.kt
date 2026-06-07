package net.transgressoft.lirp.persistence.json.primitives

import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.LirpEventSubscriberBase
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.persistence.ReactivePrimitive
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.datatest.withData
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi

/**
 * Parameterized test for [ReactiveString], [ReactiveInt], and [ReactiveBoolean], verifying
 * subscription propagation, clone/mutate semantics, equality, null handling,
 * [ReactivePrimitive.uniqueId], and [ReactivePrimitive.toString] via Kotest [withData].
 *
 * Each [PrimitiveCase] encapsulates a typed test body to avoid type-bound violations across
 * the three concrete primitive types.
 */
class ReactivePrimitiveTest : StringSpec({

    data class PrimitiveCase(
        val label: String,
        val runTest: suspend () -> Unit
    ) {
        override fun toString() = label
    }

    @OptIn(ExperimentalUuidApi::class)
    fun <V : Any> primitiveCase(
        label: String,
        create: () -> ReactivePrimitive<V>,
        initialClone: ReactivePrimitive<V>.() -> ReactivePrimitive<V>,
        mutatedValue: V,
        nullClone: () -> ReactivePrimitive<V>,
        expectedUniqueId: String,
        expectedToString: String,
        singleValueFactory: ((V) -> ReactivePrimitive<V>)?
    ): PrimitiveCase =
        PrimitiveCase(label = label) {
            val subject = create()
            val receivedEvents = mutableMapOf<EventType, MutationEvent<String, ReactivePrimitive<V>>>()
            val subscriber =
                object :
                    LirpEventSubscriberBase<
                        ReactivePrimitive<V>,
                        MutationEvent.Type,
                        MutationEvent<String, ReactivePrimitive<V>>
                    >("subscriber") {
                    init {
                        addOnNextEventAction(MutationEvent.Type.MUTATE, UPDATE) { event ->
                            receivedEvents[event.type] = event
                        }
                    }
                }

            subject.subscribe(subscriber)

            val oldClone = initialClone(subject)
            val lastDateModified = subject.lastDateModified

            subject.value = mutatedValue

            eventually(100.milliseconds) {
                subject.lastDateModified shouldBeAfter lastDateModified
                subject.value shouldBe mutatedValue
                subject shouldNotBe oldClone
                subject.hashCode() shouldNotBe oldClone.hashCode()

                assertSoftly(receivedEvents[UPDATE]) {
                    it?.let {
                        this?.newEntity shouldBe subject
                        this?.oldEntity shouldBe oldClone
                    }
                }
            }

            subject.value = null
            val clone = nullClone()
            subject shouldBe clone
            subject.hashCode() shouldBeEqual clone.hashCode()

            subject.value shouldBe null
            subject.uniqueId shouldBe expectedUniqueId
            subject.toString() shouldBe expectedToString

            singleValueFactory?.let { factory ->
                val withRandomId = factory(mutatedValue)
                withRandomId.id.shouldNotBeNull()
            }
        }

    @OptIn(ExperimentalUuidApi::class)
    withData(
        primitiveCase(
            label = "ReactiveString",
            create = { ReactiveString("1", "initialValue") },
            initialClone = { ReactiveString("1", "initialValue") },
            mutatedValue = "new value",
            nullClone = { ReactiveString("1", null) },
            expectedUniqueId = "1-null",
            expectedToString = "ReactiveString(id=1, value=null)",
            singleValueFactory = null
        ),
        primitiveCase(
            label = "ReactiveInt",
            create = { ReactiveInt("id1", 1) },
            initialClone = { clone() as ReactivePrimitive<Int> },
            mutatedValue = 2,
            nullClone = { ReactiveInt("id1", null) },
            expectedUniqueId = "id1-null",
            expectedToString = "ReactiveInt(id=id1, value=null)",
            singleValueFactory = { v -> ReactiveInt(v) }
        ),
        primitiveCase(
            label = "ReactiveBoolean",
            create = { ReactiveBoolean("Boolean-1", true) },
            initialClone = { clone() as ReactivePrimitive<Boolean> },
            mutatedValue = false,
            nullClone = { ReactiveBoolean("Boolean-1", null) },
            expectedUniqueId = "Boolean-1-null",
            expectedToString = "ReactiveBoolean(id=Boolean-1, value=null)",
            singleValueFactory = { v -> ReactiveBoolean(v) }
        )
    ) { case ->
        case.runTest()
    }
})
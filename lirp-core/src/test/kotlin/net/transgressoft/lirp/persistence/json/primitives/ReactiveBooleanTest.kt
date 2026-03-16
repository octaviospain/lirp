package net.transgressoft.lirp.persistence.json.primitives

import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.LirpEventSubscriberBase
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.persistence.ReactivePrimitive
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi

private class ReactiveBooleanSubscriber :
    LirpEventSubscriberBase<ReactivePrimitive<Boolean>, MutationEvent.Type, MutationEvent<String, ReactivePrimitive<Boolean>>>("subscriber") {
    var subscriptionReceived: LirpEventSubscription<ReactivePrimitive<Boolean>, MutationEvent.Type, MutationEvent<String, ReactivePrimitive<Boolean>>>? = null
    val receivedEvents = mutableMapOf<EventType, MutationEvent<String, ReactivePrimitive<Boolean>>>()

    init {
        addOnNextEventAction(CREATE, UPDATE) { event ->
            receivedEvents[event.type] = event
        }

        addOnSubscribeEventAction { subscriptionReceived = it }
    }
}

@OptIn(ExperimentalUuidApi::class)
class ReactiveBooleanTest : StringSpec({

    "Changes on reactive boolean propagate to subscribers" {
        val reactiveBoolean = ReactiveBoolean("Boolean-1", true)
        val subscriber = ReactiveBooleanSubscriber()

        reactiveBoolean.subscribe(subscriber)

        val oldClone = reactiveBoolean.clone()
        val lastDateModified = reactiveBoolean.lastDateModified

        reactiveBoolean.value = false

        eventually(100.milliseconds) {
            reactiveBoolean.lastDateModified shouldBeAfter lastDateModified
            reactiveBoolean.value shouldBe false
            reactiveBoolean shouldNotBe oldClone
            reactiveBoolean.hashCode() shouldNotBe oldClone.hashCode()

            assertSoftly(subscriber.receivedEvents[UPDATE]) {
                it?.let {
                    this?.newEntity shouldBe reactiveBoolean
                    this?.oldEntity shouldBe oldClone
                }
            }
        }

        reactiveBoolean.value = null
        val otherReactiveBoolean = ReactiveBoolean("Boolean-1", null)
        reactiveBoolean shouldBe otherReactiveBoolean
        reactiveBoolean.hashCode() shouldBeEqual otherReactiveBoolean.hashCode()

        reactiveBoolean.value shouldBe null
        reactiveBoolean.uniqueId shouldBe "Boolean-1-null"
        reactiveBoolean.toString() shouldBe "ReactiveBoolean(id=Boolean-1, value=null)"

        val reactiveBooleanWithRandomULID = ReactiveBoolean(true)
        reactiveBooleanWithRandomULID.id.shouldNotBeNull()
    }
})
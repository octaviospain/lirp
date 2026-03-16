package net.transgressoft.lirp.persistence.json.primitives

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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.milliseconds

private class ReactiveStringSubscriber :
    LirpEventSubscriberBase<ReactivePrimitive<String>, MutationEvent.Type, MutationEvent<String, ReactivePrimitive<String>>>("subscriber") {
    var subscriptionReceived: LirpEventSubscription<ReactivePrimitive<String>, MutationEvent.Type, MutationEvent<String, ReactivePrimitive<String>>>? = null
    val receivedEvents = mutableMapOf<EventType, MutationEvent<String, ReactivePrimitive<String>>>()

    init {
        addOnNextEventAction(MutationEvent.Type.MUTATE) { event ->
            receivedEvents[event.type] = event
        }

        addOnSubscribeEventAction { subscriptionReceived = it }
    }
}

class ReactiveStringTest : StringSpec({

    "Changes on reactive string propagate to subscribers" {
        val reactiveString = ReactiveString("1", "initialValue")
        val subscriber = ReactiveStringSubscriber()

        reactiveString.subscribe(subscriber)

        val oldClone = ReactiveString("1", "initialValue")
        val lastDateModified = reactiveString.lastDateModified

        reactiveString.value = "new value"

        eventually(100.milliseconds) {
            reactiveString.lastDateModified shouldBeAfter lastDateModified
            reactiveString.value shouldBe "new value"
            reactiveString shouldNotBe oldClone
            reactiveString.hashCode() shouldNotBe oldClone.hashCode()

            assertSoftly(subscriber.receivedEvents[UPDATE]) {
                it?.let {
                    this?.newEntity shouldBe reactiveString
                    this?.oldEntity shouldBe oldClone
                }
            }
        }

        reactiveString.value = null
        val otherReactiveString = ReactiveString("1", null)
        (reactiveString == otherReactiveString) shouldBe true
        reactiveString.hashCode() shouldBeEqual otherReactiveString.hashCode()

        reactiveString.value shouldBe null
        reactiveString.uniqueId shouldBe "1-null"
        reactiveString.toString() shouldBe "ReactiveString(id=1, value=null)"
    }
})
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

private class ReactiveIntSubscriber :
    LirpEventSubscriberBase<ReactivePrimitive<Int>, MutationEvent.Type, MutationEvent<String, ReactivePrimitive<Int>>>("subscriber") {
    var subscriptionReceived: LirpEventSubscription<ReactivePrimitive<Int>, MutationEvent.Type, MutationEvent<String, ReactivePrimitive<Int>>>? = null
    val receivedEvents = mutableMapOf<EventType, MutationEvent<String, ReactivePrimitive<Int>>>()

    init {
        addOnNextEventAction(CREATE, UPDATE) { event ->
            receivedEvents[event.type] = event
        }

        addOnSubscribeEventAction { subscriptionReceived = it }
    }
}

class ReactiveIntTest : StringSpec({

    "Changes on reactive int propagate to subscribers" {
        val reactiveInt = ReactiveInt("id1", 1)
        val subscriber = ReactiveIntSubscriber()

        reactiveInt.subscribe(subscriber)

        val oldClone = reactiveInt.clone()
        val lastDateModified = reactiveInt.lastDateModified

        reactiveInt.value = 2

        eventually(100.milliseconds) {
            reactiveInt.lastDateModified shouldBeAfter lastDateModified
            reactiveInt.value shouldBe 2
            reactiveInt shouldNotBe oldClone
            reactiveInt.hashCode() shouldNotBe oldClone.hashCode()

            assertSoftly(subscriber.receivedEvents[UPDATE]) {
                it?.let {
                    this?.newEntity shouldBe reactiveInt
                    this?.oldEntity shouldBe oldClone
                }
            }
        }

        reactiveInt.value = null
        val otherReactiveInt = ReactiveInt("id1", null)
        reactiveInt shouldBe otherReactiveInt
        reactiveInt.hashCode() shouldBeEqual otherReactiveInt.hashCode()

        reactiveInt.value shouldBe null
        reactiveInt.uniqueId shouldBe "id1-null"
        reactiveInt.toString() shouldBe "ReactiveInt(id=id1, value=null)"

        val reactiveIntWihRandomULID = ReactiveInt(2)
        reactiveIntWihRandomULID.id.shouldNotBeNull()
    }
})
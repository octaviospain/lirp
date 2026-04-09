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

package net.transgressoft.lirp.persistence.fx

import net.transgressoft.lirp.persistence.FxScalarPropertyDelegate
import net.transgressoft.lirp.persistence.LirpDelegate
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import javafx.beans.value.ChangeListener

/**
 * Tests for the 7 scalar Lirp*Property delegate classes and their factory functions,
 * verifying initial values, ChangeListener behaviour, mutation callback ordering, and
 * interface compliance with [LirpDelegate] and [FxScalarPropertyDelegate].
 */
class FxScalarPropertyTest : StringSpec({

    beforeSpec {
        FxToolkitInit.ensureInitialized()
    }

    // --- LirpStringProperty ---

    "LirpStringProperty stores and returns initial value" {
        LirpStringProperty("hello").get() shouldBe "hello"
    }

    "LirpStringProperty set() updates value and fires ChangeListener" {
        val prop = LirpStringProperty("hello")
        var oldSeen: String? = null
        var newSeen: String? = null
        prop.addListener(
            ChangeListener { _, old, new ->
                oldSeen = old
                newSeen = new
            }
        )

        prop.set("world")

        oldSeen shouldBe "hello"
        newSeen shouldBe "world"
        prop.get() shouldBe "world"
    }

    "LirpStringProperty set() with same value does not fire ChangeListener" {
        val prop = LirpStringProperty("hello")
        var listenerFired = false
        prop.addListener(ChangeListener { _, _, _ -> listenerFired = true })

        prop.set("hello")

        listenerFired.shouldBeFalse()
    }

    "LirpStringProperty set() invokes mutation callback before storing value" {
        val prop = LirpStringProperty("hello")
        var valueSeenInsideCallback: String? = null

        prop.bindMutationCallback { mutationBlock ->
            // Capture old value before super.set() is called
            valueSeenInsideCallback = prop.get()
            mutationBlock()
        }

        prop.set("world")

        // Callback ran before super.set(), so it saw the old value
        valueSeenInsideCallback shouldBe "hello"
        prop.get() shouldBe "world"
    }

    "LirpStringProperty set() without callback falls through to super.set()" {
        val prop = LirpStringProperty("hello")
        // No callback bound — set() must still update the value
        prop.set("world")
        prop.get() shouldBe "world"
    }

    "LirpStringProperty getValue returns this for by-delegation" {
        // Verify by-delegation works: 'by' syntax calls getValue and returns the delegate itself
        val prop = LirpStringProperty("hello")
        val delegated by prop
        delegated shouldBeSameInstanceAs prop
    }

    // --- Primitive type Lirp*Property ---

    "LirpIntegerProperty stores primitive int value" {
        LirpIntegerProperty(42).get() shouldBe 42
    }

    "LirpDoubleProperty stores primitive double value" {
        LirpDoubleProperty(3.14).get() shouldBe 3.14
    }

    "LirpFloatProperty stores primitive float value" {
        LirpFloatProperty(1.5f).get() shouldBe 1.5f
    }

    "LirpLongProperty stores primitive long value" {
        LirpLongProperty(100L).get() shouldBe 100L
    }

    "LirpBooleanProperty stores primitive boolean value" {
        LirpBooleanProperty(true).get().shouldBeTrue()
    }

    // --- LirpObjectProperty ---

    "LirpObjectProperty stores and returns object reference" {
        data class MyObj(val x: Int)
        val obj = MyObj(42)
        LirpObjectProperty(obj).get() shouldBeSameInstanceAs obj
    }

    "LirpObjectProperty supports null values" {
        val prop = LirpObjectProperty<String?>(null)
        prop.get().shouldBeNull()
        prop.set("x")
        prop.get() shouldBe "x"
    }

    // --- Interface compliance ---

    "Lirp*Property classes implement LirpDelegate" {
        LirpStringProperty().shouldBeInstanceOf<LirpDelegate>()
        LirpIntegerProperty().shouldBeInstanceOf<LirpDelegate>()
        LirpDoubleProperty().shouldBeInstanceOf<LirpDelegate>()
        LirpFloatProperty().shouldBeInstanceOf<LirpDelegate>()
        LirpLongProperty().shouldBeInstanceOf<LirpDelegate>()
        LirpBooleanProperty().shouldBeInstanceOf<LirpDelegate>()
        LirpObjectProperty<Any?>().shouldBeInstanceOf<LirpDelegate>()
    }

    "Lirp*Property classes implement FxScalarPropertyDelegate" {
        LirpStringProperty().shouldBeInstanceOf<FxScalarPropertyDelegate>()
        LirpIntegerProperty().shouldBeInstanceOf<FxScalarPropertyDelegate>()
        LirpDoubleProperty().shouldBeInstanceOf<FxScalarPropertyDelegate>()
        LirpFloatProperty().shouldBeInstanceOf<FxScalarPropertyDelegate>()
        LirpLongProperty().shouldBeInstanceOf<FxScalarPropertyDelegate>()
        LirpBooleanProperty().shouldBeInstanceOf<FxScalarPropertyDelegate>()
        LirpObjectProperty<Any?>().shouldBeInstanceOf<FxScalarPropertyDelegate>()
    }

    // --- set() + ChangeListener for all numeric/boolean types ---

    "LirpLongProperty set() updates value and fires ChangeListener" {
        val prop = LirpLongProperty(10L)
        var observedNew: Number? = null
        prop.addListener { _, _, new -> observedNew = new }
        prop.set(99L)
        prop.get() shouldBe 99L
        observedNew shouldBe 99L
    }

    "LirpLongProperty set() with same value does not fire ChangeListener" {
        val prop = LirpLongProperty(10L)
        var fired = false
        prop.addListener { _, _, _ -> fired = true }
        prop.set(10L)
        fired shouldBe false
    }

    "LirpLongProperty set() invokes mutation callback before storing value" {
        val prop = LirpLongProperty(1L)
        var valueSeenInsideCallback: Long = -1L
        prop.bindMutationCallback { mutationBlock ->
            valueSeenInsideCallback = prop.get()
            mutationBlock()
        }
        prop.set(2L)
        valueSeenInsideCallback shouldBe 1L
        prop.get() shouldBe 2L
    }

    "LirpFloatProperty set() updates value and fires ChangeListener" {
        val prop = LirpFloatProperty(1.0f)
        var observedNew: Number? = null
        prop.addListener { _, _, new -> observedNew = new }
        prop.set(2.5f)
        prop.get() shouldBe 2.5f
        observedNew shouldBe 2.5f
    }

    "LirpFloatProperty set() with same value does not fire ChangeListener" {
        val prop = LirpFloatProperty(1.0f)
        var fired = false
        prop.addListener { _, _, _ -> fired = true }
        prop.set(1.0f)
        fired shouldBe false
    }

    "LirpFloatProperty set() invokes mutation callback before storing value" {
        val prop = LirpFloatProperty(1.0f)
        var valueSeenInsideCallback: Float = -1.0f
        prop.bindMutationCallback { mutationBlock ->
            valueSeenInsideCallback = prop.get()
            mutationBlock()
        }
        prop.set(2.0f)
        valueSeenInsideCallback shouldBe 1.0f
        prop.get() shouldBe 2.0f
    }

    "LirpDoubleProperty set() invokes mutation callback before storing value" {
        val prop = LirpDoubleProperty(1.0)
        var valueSeenInsideCallback: Double = -1.0
        prop.bindMutationCallback { mutationBlock ->
            valueSeenInsideCallback = prop.get()
            mutationBlock()
        }
        prop.set(2.0)
        valueSeenInsideCallback shouldBe 1.0
        prop.get() shouldBe 2.0
    }

    "LirpBooleanProperty set() updates value and fires ChangeListener" {
        val prop = LirpBooleanProperty(false)
        var observedNew: Boolean? = null
        prop.addListener { _, _, new -> observedNew = new }
        prop.set(true)
        prop.get().shouldBeTrue()
        observedNew shouldBe true
    }

    "LirpBooleanProperty set() invokes mutation callback before storing value" {
        val prop = LirpBooleanProperty(false)
        var valueSeenInsideCallback: Boolean? = null
        prop.bindMutationCallback { mutationBlock ->
            valueSeenInsideCallback = prop.get()
            mutationBlock()
        }
        prop.set(true)
        valueSeenInsideCallback shouldBe false
        prop.get().shouldBeTrue()
    }

    // --- Factory functions ---

    "fxString factory creates LirpStringProperty with initial value" {
        fxString("init").get() shouldBe "init"
    }

    "fxInteger factory creates LirpIntegerProperty" {
        fxInteger(7).get() shouldBe 7
    }

    "fxDouble factory creates LirpDoubleProperty" {
        fxDouble(3.14).get() shouldBe 3.14
    }

    "fxFloat factory creates LirpFloatProperty" {
        fxFloat(1.5f).get() shouldBe 1.5f
    }

    "fxLong factory creates LirpLongProperty" {
        fxLong(100L).get() shouldBe 100L
    }

    "fxBoolean factory creates LirpBooleanProperty" {
        fxBoolean(true).get().shouldBeTrue()
    }

    "fxObject factory supports nullable type" {
        fxObject<String?>(null).get().shouldBeNull()
    }

    // --- FxProperties static factories ---

    "FxProperties.fxString creates LirpStringProperty" {
        FxProperties.fxString("test").get() shouldBe "test"
    }

    "FxProperties.fxInteger creates LirpIntegerProperty" {
        FxProperties.fxInteger(42).get() shouldBe 42
    }

    "FxProperties.fxDouble creates LirpDoubleProperty" {
        FxProperties.fxDouble(2.72).get() shouldBe 2.72
    }

    "FxProperties.fxFloat creates LirpFloatProperty" {
        FxProperties.fxFloat(0.5f).get() shouldBe 0.5f
    }

    "FxProperties.fxLong creates LirpLongProperty" {
        FxProperties.fxLong(999L).get() shouldBe 999L
    }

    "FxProperties.fxBoolean creates LirpBooleanProperty" {
        FxProperties.fxBoolean(false).get() shouldBe false
    }

    "FxProperties.fxObject creates LirpObjectProperty" {
        val prop = FxProperties.fxObject<String>("hello")
        prop.get() shouldBe "hello"
    }
})
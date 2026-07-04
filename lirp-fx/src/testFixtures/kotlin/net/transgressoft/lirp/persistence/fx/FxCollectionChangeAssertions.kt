/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

import io.kotest.matchers.shouldBe
import javafx.collections.ListChangeListener
import javafx.collections.SetChangeListener

/*
 * Assertions for JavaFX collection-change notifications, collapsing the recurring
 * `change.next() shouldBe true` -> `change.wasX() shouldBe true` -> field-by-field cursor walk into
 * one named claim.
 *
 * These cover the common single-segment change only. Tests that assert an ordered walk across
 * multiple segments (e.g. a multi-remove producing several ranges) must keep driving the cursor
 * explicitly — the ordered progression is the behaviour under test there.
 */

/** Asserts the change is a single contiguous add spanning `[from, to)`. */
fun ListChangeListener.Change<*>.shouldBeSingleAdd(from: Int, to: Int) {
    next() shouldBe true
    wasAdded() shouldBe true
    this.from shouldBe from
    this.to shouldBe to
}

/** Asserts the change is a single removal of [removedCount] elements. */
fun ListChangeListener.Change<*>.shouldBeSingleRemove(removedCount: Int) {
    next() shouldBe true
    wasRemoved() shouldBe true
    removed.size shouldBe removedCount
}

/** Asserts the change is a single replacement (a set on an existing index). */
fun ListChangeListener.Change<*>.shouldBeSingleReplace() {
    next() shouldBe true
    wasReplaced() shouldBe true
}

/** Asserts this set change added exactly [element]. */
fun <E> SetChangeListener.Change<out E>.shouldBeAddOf(element: E) {
    wasAdded() shouldBe true
    elementAdded shouldBe element
}

/** Asserts this set change removed exactly [element]. */
fun <E> SetChangeListener.Change<out E>.shouldBeRemoveOf(element: E) {
    wasRemoved() shouldBe true
    elementRemoved shouldBe element
}
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

import net.transgressoft.lirp.persistence.json.lirpSerializer
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

/**
 * Tests verifying [net.transgressoft.lirp.persistence.json.LirpEntitySerializer] serialization
 * round-trips for [CombinedDelegateEntity], which combines [reactiveProperty], [@Indexed][net.transgressoft.lirp.persistence.Indexed],
 * and fx scalar delegates in a single class. Closes coverage Gap 5 from the CONCERNS.md audit:
 * all three delegate types coexisting in one entity was untested.
 */
class CombinedDelegateSerializationTest : StringSpec({

    reactiveScope()

    val json = Json { prettyPrint = true }
    val serializer = lirpSerializer(CombinedDelegateEntity(0, ""))

    "serializes entity with reactiveProperty, @Indexed, and FxScalar delegates" {
        val entity = CombinedDelegateEntity(1, "Test", "electronics", 5)
        entity.labelProperty.set("urgent")

        val encoded = json.encodeToString(serializer, entity)

        encoded shouldContain "\"id\": 1"
        encoded shouldContain "\"name\": \"Test\""
        encoded shouldContain "\"category\": \"electronics\""
        encoded shouldContain "\"priorityProperty\": 5"
        encoded shouldContain "\"labelProperty\": \"urgent\""
    }

    data class RoundTripCase(
        val name: String,
        val build: () -> CombinedDelegateEntity,
        val verify: (CombinedDelegateEntity) -> Unit
    )

    withData(
        nameFn = RoundTripCase::name,
        RoundTripCase(
            "round-trip preserves all three delegate types",
            { CombinedDelegateEntity(1, "Alpha", "books", 10).apply { labelProperty.set("premium") } }
        ) { decoded ->
            decoded.id shouldBe 1
            decoded.name shouldBe "Alpha"
            decoded.category shouldBe "books"
            decoded.priorityProperty.get() shouldBe 10
            decoded.labelProperty.get() shouldBe "premium"
        },
        RoundTripCase(
            "round-trip preserves mutated values across all delegate types",
            {
                CombinedDelegateEntity(1, "Original", "old-category", 1).apply {
                    labelProperty.set("old-label")
                    name = "Mutated"
                    category = "updated"
                    priorityProperty.set(99)
                    labelProperty.set("changed")
                }
            }
        ) { decoded ->
            decoded.name shouldBe "Mutated"
            decoded.category shouldBe "updated"
            decoded.priorityProperty.get() shouldBe 99
            decoded.labelProperty.get() shouldBe "changed"
        },
        RoundTripCase(
            "round-trip preserves default values when no explicit values set",
            { CombinedDelegateEntity(1, "Defaults") }
        ) { decoded ->
            decoded.id shouldBe 1
            decoded.name shouldBe "Defaults"
            decoded.category shouldBe ""
            decoded.priorityProperty.get() shouldBe 0
            decoded.labelProperty.get() shouldBe ""
        }
    ) { case ->
        val encoded = json.encodeToString(serializer, case.build())
        case.verify(json.decodeFromString(serializer, encoded))
    }
})
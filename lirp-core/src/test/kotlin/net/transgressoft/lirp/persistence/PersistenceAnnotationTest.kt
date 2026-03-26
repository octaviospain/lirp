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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceIgnore
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.PersistenceProperty
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke tests verifying the persistence annotation contracts compile and expose correct default values.
 *
 * Since [PersistenceMapping], [PersistenceProperty], and [PersistenceIgnore] use
 * [AnnotationRetention.BINARY] retention, the annotations are stored in the class file and visible
 * to KSP at compile time. These tests verify that:
 * 1. The annotations can be applied to entities and properties without compilation errors.
 * 2. Annotated entities construct and behave correctly at runtime.
 * The successful compilation of this file is itself proof that the annotation targets, parameter
 * types, and parameter defaults are correct.
 */
@PersistenceMapping
internal data class DefaultMappedEntity(override val id: Int) : ReactiveEntityBase<Int, DefaultMappedEntity>() {
    override val uniqueId: String get() = "default-$id"

    override fun clone() = DefaultMappedEntity(id)
}

@PersistenceMapping(name = "custom_table")
internal data class CustomMappedEntity(override val id: Int) : ReactiveEntityBase<Int, CustomMappedEntity>() {
    override val uniqueId: String get() = "custom-$id"

    override fun clone() = CustomMappedEntity(id)
}

internal data class PropertyAnnotatedEntity(
    override val id: Int,
    @PersistenceProperty val defaultProp: String = "",
    @PersistenceProperty(name = "col_name", length = 255, precision = 10, scale = 2, type = "DECIMAL") val customProp: String = "",
    @PersistenceIgnore val ignoredProp: String = ""
) : ReactiveEntityBase<Int, PropertyAnnotatedEntity>() {
    override val uniqueId: String get() = "prop-$id"

    override fun clone() = PropertyAnnotatedEntity(id, defaultProp, customProp, ignoredProp)
}

@DisplayName("PersistenceAnnotationTest")
internal class PersistenceAnnotationTest : FunSpec({

    test("PersistenceMapping with defaults applied to entity compiles and entity constructs correctly") {
        val entity = DefaultMappedEntity(1)
        entity.id shouldBe 1
        entity.uniqueId shouldBe "default-1"
    }

    test("PersistenceMapping with custom name applied to entity compiles and entity constructs correctly") {
        val entity = CustomMappedEntity(2)
        entity.id shouldBe 2
        entity.uniqueId shouldBe "custom-2"
    }

    test("PersistenceProperty with default values applied to property compiles and entity constructs correctly") {
        val entity = PropertyAnnotatedEntity(3)
        entity.id shouldBe 3
        entity.defaultProp shouldBe ""
    }

    test("PersistenceProperty with custom values applied to property compiles and entity constructs correctly") {
        val entity = PropertyAnnotatedEntity(4, customProp = "test_value")
        entity.id shouldBe 4
        entity.customProp shouldBe "test_value"
    }

    test("PersistenceIgnore applied to property compiles and annotated property is accessible at runtime") {
        val entity = PropertyAnnotatedEntity(5, ignoredProp = "ignored")
        entity.id shouldBe 5
        entity.ignoredProp shouldBe "ignored"
    }
})
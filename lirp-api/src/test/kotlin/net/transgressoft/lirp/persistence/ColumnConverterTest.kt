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

import io.kotest.core.spec.style.StringSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.reflect.full.memberProperties

/**
 * Identity converter used to verify that a Kotlin `object` implementing [ColumnConverter]
 * compiles against the published interface contract and round-trips values unchanged.
 */
object IdentityStringConverter : ColumnConverter<String, String> {
    override val sqlType: ColumnType = ColumnType.TextType

    override fun toSql(value: String): String = value

    override fun fromSql(raw: String): String = raw
}

/**
 * Entity-side fixture exercising the new `converter` annotation parameter. Compilation
 * alone proves the annotation accepts a converter [kotlin.reflect.KClass] argument; the
 * tests below assert presence and signature stability.
 */
@Suppress("unused")
class ConverterAnnotationFixture {
    @PersistenceProperty(converter = IdentityStringConverter::class)
    val withConverter: String = ""

    @PersistenceProperty(name = "plain")
    val withoutConverter: String = ""
}

class ColumnConverterTest : StringSpec({

    "ColumnConverter exposes sqlType toSql and fromSql members" {
        val memberNames = ColumnConverter::class.java.methods.map { it.name }.toSet()
        assertTrue(memberNames.containsAll(listOf("getSqlType", "toSql", "fromSql")))
    }

    "ColumnConverter round-trips identity values through toSql and fromSql" {
        assertEquals(ColumnType.TextType, IdentityStringConverter.sqlType)
        assertEquals("alpha", IdentityStringConverter.toSql("alpha"))
        assertEquals("beta", IdentityStringConverter.fromSql("beta"))
    }

    "@PersistenceProperty accepts a converter KClass argument and defaults to ColumnConverter sentinel" {
        // The annotation has BINARY retention (KSP reads source), so it is not visible to runtime
        // reflection on annotated properties. Signature stability is asserted at the annotation
        // interface level: presence of the `converter` accessor, its JVM-erased return type
        // (`KClass<*>` lowers to `java.lang.Class`), and its default value (the sentinel).
        val converterAccessor = PersistenceProperty::class.java.getMethod("converter")
        assertEquals(Class::class.java, converterAccessor.returnType)
        assertEquals(ColumnConverter::class.java, converterAccessor.defaultValue)

        // The fixture's compilation proves the annotation accepts a converter KClass at the
        // source level — a load-time class verifier would reject mismatched annotation types.
        val propertyNames = ConverterAnnotationFixture::class.memberProperties.map { it.name }.toSet()
        assertTrue(propertyNames.containsAll(setOf("withConverter", "withoutConverter")))
    }
})
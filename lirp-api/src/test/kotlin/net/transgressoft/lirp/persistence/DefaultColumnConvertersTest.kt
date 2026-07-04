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

package net.transgressoft.lirp.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.datatest.withData
import io.kotest.engine.names.WithDataTestName
import io.kotest.matchers.shouldBe
import java.math.BigInteger
import java.net.URI
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Round-trip and `sqlType` assertions for the built-in default [ColumnConverter] singletons. Each
 * case verifies that a domain value survives `toSql` followed by `fromSql` and that the declared
 * persistence scalar matches the documented column shape.
 */
class DefaultColumnConvertersTest : StringSpec({

    // Converters whose contract is the uniform "TextType, symmetric fromSql(toSql(x)) == x" shape.
    // The converters with a distinct contract (asymmetric expected value, exact encoding, or a
    // double round-trip) keep their own named tests below.
    withData(
        SymmetricRoundTrip(
            "InstantColumnConverter round-trips an instant through its ISO-8601 form on a TextType column",
            InstantColumnConverter, Instant.parse("2026-06-23T10:15:30.250Z")
        ),
        SymmetricRoundTrip(
            "OffsetDateTimeColumnConverter preserves the offset across a round-trip on a TextType column",
            OffsetDateTimeColumnConverter, OffsetDateTime.of(2026, 6, 23, 10, 15, 30, 0, ZoneOffset.ofHours(2))
        ),
        SymmetricRoundTrip(
            "UriColumnConverter round-trips a URI through its string form on a TextType column",
            UriColumnConverter, URI("https://example.com/path?query=1#frag")
        ),
        SymmetricRoundTrip(
            "BigIntegerColumnConverter round-trips an arbitrary-precision integer on a TextType column",
            BigIntegerColumnConverter, BigInteger("123456789012345678901234567890")
        )
    ) { case ->
        case.verify()
    }

    "PathColumnConverter round-trips a path through its URI form on a Long-free TextType column" {
        PathColumnConverter.sqlType shouldBe ColumnType.TextType
        val path = Paths.get("/tmp/song.mp3")
        PathColumnConverter.fromSql(PathColumnConverter.toSql(path)) shouldBe Paths.get(path.toUri())
    }

    "DurationColumnConverter round-trips a sub-second duration losslessly through nanos on a LongType column" {
        DurationColumnConverter.sqlType shouldBe ColumnType.LongType
        val duration = Duration.ofSeconds(180).plusNanos(123_456_789)
        DurationColumnConverter.toSql(duration) shouldBe 180_123_456_789
        DurationColumnConverter.fromSql(DurationColumnConverter.toSql(duration)) shouldBe duration
    }

    "UrlColumnConverter round-trips a URL through its string form on a TextType column" {
        UrlColumnConverter.sqlType shouldBe ColumnType.TextType
        val url = URI("https://example.com/path").toURL()
        UrlColumnConverter.toSql(UrlColumnConverter.fromSql(UrlColumnConverter.toSql(url))) shouldBe url.toString()
    }
})

/**
 * A converter whose round-trip is symmetric and text-backed: `sqlType == TextType` and
 * `fromSql(toSql(value)) == value`. Generics are captured at construction so [verify] needs no cast.
 */
private class SymmetricRoundTrip<T : Any>(
    val name: String,
    val converter: ColumnConverter<T, String>,
    val value: T
) : WithDataTestName {
    override fun dataTestName() = name

    fun verify() {
        converter.sqlType shouldBe ColumnType.TextType
        converter.fromSql(converter.toSql(value)) shouldBe value
    }
}
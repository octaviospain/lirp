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

import java.math.BigInteger
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/*
 * Built-in ColumnConverter singletons for common JDK value types that have an unambiguous,
 * domain-agnostic mapping to a SQL column.
 *
 * Each converter here is bound automatically by KSP when an entity property's declared type matches
 * the converter's domain type and no explicit @PersistenceProperty(converter = …) is present. A
 * consumer that needs different storage semantics for one of these types supplies its own converter
 * via @PersistenceProperty(converter = …), which always takes precedence over the built-in default.
 *
 * Only types lirp does not already map natively are covered here. java.time.LocalDate,
 * java.time.LocalDateTime, java.util.UUID, and java.math.BigDecimal are mapped directly by the SQL
 * type-inference path and therefore have no built-in converter.
 *
 * Domain-specific types (a music genre, a country code, a money wrapper) are intentionally out of
 * scope: their mapping is consumer knowledge and belongs in a consumer-supplied converter.
 */

/**
 * Maps [Path] to its URI-form string. URI encoding preserves platform-independent path semantics so
 * absolute paths round-trip across operating systems without dialect-specific escaping.
 *
 * The round-trip canonicalises platform-specific separators: a parsed value equals
 * `Paths.get(original.toUri())` rather than the original instance.
 */
object PathColumnConverter : ColumnConverter<Path, String> {
    override val sqlType: ColumnType = ColumnType.TextType

    override fun toSql(value: Path): String = value.toUri().toString()

    override fun fromSql(raw: String): Path = Paths.get(URI(raw))
}

/**
 * Maps [Duration] to a `Long` count of nanoseconds, the full-fidelity representation that round-trips
 * without loss for any duration whose nanosecond total fits in a `Long` (about ±292 years).
 *
 * The nanosecond backing avoids the floating-point imprecision a fractional-seconds [Double] would
 * introduce and preserves sub-second precision that a whole-seconds mapping would truncate.
 */
object DurationColumnConverter : ColumnConverter<Duration, Long> {
    override val sqlType: ColumnType = ColumnType.LongType

    override fun toSql(value: Duration): Long = value.toNanos()

    override fun fromSql(raw: Long): Duration = Duration.ofNanos(raw)
}

/**
 * Maps [Instant] to its ISO-8601 string (`Instant.toString()`), a textual, timezone-unambiguous
 * representation that round-trips losslessly through [Instant.parse].
 */
object InstantColumnConverter : ColumnConverter<Instant, String> {
    override val sqlType: ColumnType = ColumnType.TextType

    override fun toSql(value: Instant): String = value.toString()

    override fun fromSql(raw: String): Instant = Instant.parse(raw)
}

/**
 * Maps [OffsetDateTime] to its ISO-8601 string (`OffsetDateTime.toString()`), preserving both the
 * local date-time and the UTC offset so the value round-trips losslessly through
 * [OffsetDateTime.parse].
 */
object OffsetDateTimeColumnConverter : ColumnConverter<OffsetDateTime, String> {
    override val sqlType: ColumnType = ColumnType.TextType

    override fun toSql(value: OffsetDateTime): String = value.toString()

    override fun fromSql(raw: String): OffsetDateTime = OffsetDateTime.parse(raw)
}

/**
 * Maps [URI] to its string form. The mapping round-trips losslessly because [URI.toString] preserves
 * the exact components a [URI] was constructed from.
 */
object UriColumnConverter : ColumnConverter<URI, String> {
    override val sqlType: ColumnType = ColumnType.TextType

    override fun toSql(value: URI): String = value.toString()

    override fun fromSql(raw: String): URI = URI(raw)
}

/**
 * Maps [URL] to its string form, parsing back through [URI] to obtain a [URL] without the blocking
 * host-resolution that the deprecated `URL(String)` constructor performs.
 *
 * Note that [URL] equality triggers host resolution; consumers that compare persisted URLs should
 * compare their string forms or use [URI] instead.
 */
object UrlColumnConverter : ColumnConverter<URL, String> {
    override val sqlType: ColumnType = ColumnType.TextType

    override fun toSql(value: URL): String = value.toString()

    override fun fromSql(raw: String): URL = URI(raw).toURL()
}

/**
 * Maps [BigInteger] to its base-10 string form. A textual mapping preserves arbitrary precision,
 * which a fixed-width integer column could not hold; the value round-trips losslessly through the
 * `BigInteger(String)` constructor.
 */
object BigIntegerColumnConverter : ColumnConverter<BigInteger, String> {
    override val sqlType: ColumnType = ColumnType.TextType

    override fun toSql(value: BigInteger): String = value.toString()

    override fun fromSql(raw: String): BigInteger = BigInteger(raw)
}
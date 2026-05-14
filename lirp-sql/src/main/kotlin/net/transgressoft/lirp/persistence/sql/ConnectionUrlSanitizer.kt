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

package net.transgressoft.lirp.persistence.sql

/**
 * Defensive logging utility that masks password values inside JDBC connection URLs so that
 * diagnostic output (logs, metrics labels, HikariCP DEBUG dumps, stack-trace context) does not
 * leak credentials when the URL must be surfaced.
 *
 * The mask token is the literal `****`. Every surface where JDBC URLs are known to embed a
 * password is replaced; everything else (scheme, host, port, database, non-password params,
 * key casing, URL-encoding of non-password values, fragments) is preserved byte-for-byte so the
 * output stays useful for diagnosis.
 *
 * For URL-encoded password values the entire encoded value is replaced by `****` — the
 * sanitizer never decodes the secret, it only redacts it.
 *
 * Threat model: this is defence-in-depth, NOT a substitute for proper credential injection.
 * Consumers should inject credentials via environment variables (`JDBC_URL`, `DB_USER`,
 * `DB_PASSWORD`) and pass them to `SqlRepository.connect(url, username, password)` rather than
 * embedding secrets in URL strings. See `README.md` and the wiki SQL-Persistence page for the
 * canonical guidance.
 */
object ConnectionUrlSanitizer {

    private const val MASK: String = "****"

    // userinfo: matches `<scheme>://<user>:<password>@` and replaces only the password segment.
    // Password is everything between the first `:` after `://` and the next `@`.
    private val userinfoRegex: Regex =
        Regex("""(://[^/:@\s?#]*:)([^@\s/?#]*)(@)""")

    // Query-string `password=value` (case-insensitive on the key). Value is everything until
    // the next `&` or `#` or end-of-string. Anchored to a leading `?` or `&` so it only matches
    // inside the query string.
    private val queryPasswordRegex: Regex =
        Regex("""([?&])([Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd])=([^&#]*)""")

    // Semicolon-property `PASSWORD=value` (H2 idiom; case-insensitive). Value runs until the
    // next `;` or end-of-string. Anchored to a leading `;` so it does not collide with
    // query-string handling.
    private val semicolonPasswordRegex: Regex =
        Regex(""";([Pp][Aa][Ss][Ss][Ww][Oo][Rr][Dd])=([^;]*)""")

    /**
     * Returns [jdbcUrl] with every password value masked to `****`.
     *
     * Three credential surfaces are handled:
     *  1. userinfo segment (`<scheme>://user:password@host`) — only the password between the
     *     first `:` after `://` and the next `@` is replaced.
     *  2. query-string parameters keyed by `password` (case-insensitive), parsed from after the
     *     first `?`. Other query parameters (including `user=`) are preserved verbatim.
     *  3. semicolon-delimited properties keyed by `PASSWORD` (case-insensitive) — the H2 idiom,
     *     appearing after the first `;`. Other properties (including `USER=`) are preserved.
     *
     * Verbatim-fallback contract: if [jdbcUrl] matches none of these surfaces, it is returned
     * unchanged. Malformed or empty input is also returned unchanged — this utility never
     * throws on input parsing, because it is a defensive logging aid, not a parser of any
     * invariant-bearing state.
     */
    fun sanitize(jdbcUrl: String): String {
        var result = jdbcUrl
        result =
            userinfoRegex.replace(result) { match ->
                "${match.groupValues[1]}$MASK${match.groupValues[3]}"
            }
        result =
            queryPasswordRegex.replace(result) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}=$MASK"
            }
        result =
            semicolonPasswordRegex.replace(result) { match ->
                ";${match.groupValues[1]}=$MASK"
            }
        return result
    }
}
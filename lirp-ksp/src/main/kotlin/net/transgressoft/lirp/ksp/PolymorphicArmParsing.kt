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

package net.transgressoft.lirp.ksp

// Source-text parsing helpers shared by TableDefProcessor and ReactiveEntityRefProcessor for
// polymorphicAggregate(arm<K, E>("label") { scalar }, …) declarations. KSP's resolved type model
// does not expose lambda bodies or string-literal labels, so the arm metadata is recovered by
// scanning the property's source text. Both processors must parse arms identically, so the regex
// construction, the scalar-path normalisation, and the bare-arm(-call detector live here rather
// than being duplicated.

/** Matches the simple Kotlin identifier rule used for arm labels and backing-scalar segments. */
private val KOTLIN_IDENTIFIER_REGEX = Regex("""[A-Za-z_][A-Za-z_0-9]*""")

/**
 * Source-level call prefix of a `polymorphicAggregate(` declaration. Both processors detect these
 * properties by scanning for this literal, so it is shared here to keep the detection identical.
 */
internal const val POLYMORPHIC_AGGREGATE_CALL = "polymorphicAggregate("

/**
 * Builds the canonical arm regex. The cascade argument is accepted either as the named
 * `onDelete = CascadeAction.X` or as a bare positional `CascadeAction.X`, since the runtime
 * `arm(label, onDelete, idProvider)` factory declares `onDelete` as an ordinary positional
 * parameter. The lambda body capture tolerates a leading `this.` and a dotted path so that
 * `{ this.audioItemId }` and `{ holder.audioItemId }` capture the full path; [armScalarFromPath]
 * reduces it to the backing scalar identifier.
 */
internal fun buildArmRegex(): Regex =
    Regex(
        """
        \barm\s*<\s*                  # arm<
        ([\w.]+)\s*,\s*               # type K
        ([\w.]+)\s*>\s*               # type E
        \(\s*                         # (
        "([^"]*)"\s*                  # label
        (?:
            ,\s*
            (?:onDelete\s*=\s*)?      # optional 'onDelete ='
            CascadeAction\.(\w+)      # cascade action
        )?
        \s*\)                         # )
        \s*\{\s*                      # {
        ([\w.]+)                      # scalar path
        """.trimIndent(),
        RegexOption.COMMENTS
    )

/**
 * Reduces a captured lambda-body reference path to the backing scalar identifier: strips a leading
 * `this.` and returns the last dotted segment (`this.audioItemId` → `audioItemId`,
 * `holder.itemId` → `itemId`).
 */
internal fun armScalarFromPath(path: String): String =
    path.removePrefix("this.").substringAfterLast('.')

/** True when [label] is a legal Kotlin identifier, which the sealed-union emitter needs for a valid data class name. */
internal fun isValidArmLabel(label: String): Boolean = KOTLIN_IDENTIFIER_REGEX.matches(label)

/**
 * Extracts the source span of a [callKeyword] call (e.g. `polymorphicAggregate(`) from [text] by
 * performing a balanced-parenthesis walk starting at the first `(` of the call. Parentheses inside
 * string literals (`"…"`), line comments (`// …`), and block comments (`/* … */`) are skipped so a
 * label such as `"foo)bar"` or a trailing `// see foo()` comment does not prematurely close or
 * unbalance the span. Returns the substring from the call keyword to the matching `)`, or from the
 * keyword to end of [text] when the closing paren is never reached.
 */
internal fun extractBalancedCallSpan(text: String, callKeyword: String): String {
    val startIndex = text.indexOf(callKeyword)
    if (startIndex < 0) return text
    val parenOpen = text.indexOf('(', startIndex)
    if (parenOpen < 0) return text
    var state = BalancedScanState(index = parenOpen)
    while (state.index < text.length) {
        val c = text[state.index]
        val next = if (state.index + 1 < text.length) text[state.index + 1] else ' '
        state = advanceBalancedScan(state, c, next)
        if (state.closed) return text.substring(startIndex, state.index + 1)
        state = state.copy(index = state.index + 1)
    }
    return text.substring(startIndex)
}

/**
 * Scan position for [extractBalancedCallSpan]: the current [index], paren nesting [depth], and the
 * three "inside a literal/comment" flags. [closed] is set by [advanceBalancedScan] once the matching
 * `)` that returns [depth] to zero has been consumed.
 */
private data class BalancedScanState(
    val index: Int,
    val depth: Int = 0,
    val inString: Boolean = false,
    val inLineComment: Boolean = false,
    val inBlockComment: Boolean = false,
    val closed: Boolean = false
)

/**
 * Computes the next [BalancedScanState] for character [c] (with lookahead [next]), preserving the
 * exact string-literal / line-comment / block-comment skipping of the original walk. Two-character
 * tokens (a block-comment close, a line-comment open, a block-comment open, and an escaped char inside
 * a string) advance [BalancedScanState.index] by one extra so the second character is not re-examined,
 * mirroring the original's inline index bump.
 */
private fun advanceBalancedScan(state: BalancedScanState, c: Char, next: Char): BalancedScanState =
    when {
        state.inLineComment -> if (c == '\n') state.copy(inLineComment = false) else state
        state.inBlockComment ->
            if (c == '*' && next == '/') state.copy(inBlockComment = false, index = state.index + 1) else state
        state.inString ->
            when {
                c == '\\' -> state.copy(index = state.index + 1) // skip escaped char
                c == '"' -> state.copy(inString = false)
                else -> state
            }
        c == '"' -> state.copy(inString = true)
        c == '/' && next == '/' -> state.copy(inLineComment = true, index = state.index + 1)
        c == '/' && next == '*' -> state.copy(inBlockComment = true, index = state.index + 1)
        c == '(' -> state.copy(depth = state.depth + 1)
        c == ')' -> {
            val newDepth = state.depth - 1
            state.copy(depth = newDepth, closed = newDepth == 0)
        }
        else -> state
    }

/** Matches `import a.b.C` and an optional `as Alias` rename. Group 1 = FQN, group 2 = alias (may be empty). */
private val IMPORT_REGEX = Regex("""^\s*import\s+([\w.]+)(?:\s+as\s+([A-Za-z_][A-Za-z_0-9]*))?""")

/**
 * Resolves the fully-qualified name an arm's source-level type name [usedName] refers to, given the
 * declaring file's [importLines]. An aliased import (`import a.b.C as Bar`) is matched on its alias,
 * so the source's `arm<…, Bar>` binds to `a.b.C`; a plain import is matched on its last segment. When
 * several non-aliased imports share the simple name, the one in [propertyPackage] is preferred so an
 * arm in the same package binds to the local type rather than to whichever import happened to appear
 * first. Returns `null` when no import matches.
 */
internal fun resolveImportedFqn(importLines: List<String>, usedName: String, propertyPackage: String): String? {
    val candidates = mutableListOf<String>()
    for (line in importLines) {
        val match = IMPORT_REGEX.find(line) ?: continue
        val importFqn = match.groupValues[1]
        val alias = match.groupValues[2]
        if (alias.isNotEmpty()) {
            // Aliased import: the source must use the alias, never the original simple name.
            if (alias == usedName) return importFqn
        } else if (importFqn.substringAfterLast('.') == usedName) {
            candidates += importFqn
        }
    }
    if (candidates.isEmpty()) return null
    return candidates.firstOrNull { it.substringBeforeLast('.') == propertyPackage } ?: candidates.first()
}

/**
 * Detects `arm(` / `arm<…>(` call sites in [boundedText] that the canonical arm regex did not match,
 * returning the offending raw snippets. A leftover `arm(` after removing every full match signals a
 * declaration the parser cannot understand (e.g. a malformed cascade form or an unsupported lambda
 * body) — emitting these as a hard diagnostic prevents the silent divergence where the runtime
 * delegate keeps an arm that never reaches FK / sealed-union generation.
 */
internal fun unparseableArmCalls(boundedText: String, armRegex: Regex): List<String> {
    val withoutMatches = armRegex.replace(boundedText, "")
    if (!Regex("""\barm\s*<""").containsMatchIn(withoutMatches)) return emptyList()
    // Re-scan the original text for arm< call starts and report the ones whose start offset is not
    // covered by any successful match.
    val matchedRanges = armRegex.findAll(boundedText).map { it.range }.toList()
    val armStarts = Regex("""\barm\s*<""").findAll(boundedText).map { it.range.first }.toList()
    return armStarts
        .filter { start -> matchedRanges.none { start in it } }
        .map { start ->
            val end = (start + 80).coerceAtMost(boundedText.length)
            boundedText.substring(start, end).substringBefore('\n').trim()
        }
}
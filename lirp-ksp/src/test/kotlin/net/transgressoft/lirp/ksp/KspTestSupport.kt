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

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspProcessorOptions
import com.tschuchort.compiletesting.sourcesGeneratedBySymbolProcessor
import com.tschuchort.compiletesting.symbolProcessorProviders
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Shared KSP in-process compilation helper for lirp-ksp tests.
 *
 * Each test passes the [SymbolProcessorProvider]s it needs, so processor isolation is preserved.
 * [generatedFileContent] and [generatedNames] provide uniform access to KSP output.
 */
@OptIn(ExperimentalCompilerApi::class)
internal object KspTestSupport {

    /**
     * Compiles [sources] with [provider] registered as the sole KSP processor.
     *
     * @param options KSP processor options forwarded via [KotlinCompilation.kspProcessorOptions].
     * @param jvmTarget Optional JVM target override (e.g. `"21"` for tests that inline reified helpers).
     * @param withCompilation Whether to also run full Kotlin compilation after KSP (default `true`).
     */
    fun compile(
        provider: SymbolProcessorProvider,
        vararg sources: SourceFile,
        options: Map<String, String> = emptyMap(),
        jvmTarget: String? = null,
        withCompilation: Boolean = true
    ): JvmCompilationResult =
        compile(listOf(provider), sources.toList(), options, jvmTarget, withCompilation)

    /**
     * Compiles [sources] with every provider in [providers] registered, preserving per-processor
     * isolation while supporting the few tests that legitimately need more than one processor.
     *
     * @param options KSP processor options forwarded via [KotlinCompilation.kspProcessorOptions].
     * @param jvmTarget Optional JVM target override (e.g. `"21"` for tests that inline reified helpers).
     * @param withCompilation Whether to also run full Kotlin compilation after KSP (default `true`).
     */
    fun compile(
        providers: List<SymbolProcessorProvider>,
        sources: List<SourceFile>,
        options: Map<String, String> = emptyMap(),
        jvmTarget: String? = null,
        withCompilation: Boolean = true
    ): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources
                inheritClassPath = true
                jvmTarget?.let { this.jvmTarget = it }
            }
        compilation.configureKsp { this.withCompilation = withCompilation }
        if (options.isNotEmpty()) {
            compilation.kspProcessorOptions.putAll(options)
        }
        providers.forEach { compilation.symbolProcessorProviders += it }
        return compilation.compile()
    }
}

/**
 * Asserts the compilation succeeded, returning the result so callers can chain generated-file
 * assertions. Reads as the intent ("this must compile") instead of a bare exit-code equality.
 */
@OptIn(ExperimentalCompilerApi::class)
internal fun JvmCompilationResult.shouldSucceed(): JvmCompilationResult {
    exitCode shouldBe KotlinCompilation.ExitCode.OK
    return this
}

/**
 * Asserts the compilation failed and every substring in [expectedMessages] appears in the compiler
 * output. Soft-asserts the substrings so a missing one is reported alongside the others rather than
 * short-circuiting at the first — replacing the `exitCode shouldBe ERROR` + N× `messages shouldContain`
 * roulette that recurs across the diagnostic tests.
 */
@OptIn(ExperimentalCompilerApi::class)
internal fun JvmCompilationResult.shouldFailWith(vararg expectedMessages: String) {
    exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
    assertSoftly { expectedMessages.forEach { messages shouldContain it } }
}

/**
 * Asserts this generated-source text contains every substring in [substrings], soft-asserting so a
 * failure names all missing fragments at once instead of stopping at the first.
 */
internal fun String.shouldContainEach(vararg substrings: String) {
    assertSoftly { substrings.forEach { this@shouldContainEach shouldContain it } }
}

/**
 * Asserts this generated-source text contains every substring in [present] and none of those in
 * [absent], reporting all violations together.
 */
internal fun String.shouldContainEachAndNone(present: List<String>, absent: List<String>) {
    assertSoftly {
        present.forEach { this@shouldContainEachAndNone shouldContain it }
        absent.forEach { this@shouldContainEachAndNone shouldNotContain it }
    }
}

/**
 * Returns the text content of the KSP-generated source file named [name].
 *
 * Throws if no file with that name was produced, listing available names to aid diagnosis.
 */
@OptIn(ExperimentalCompilerApi::class)
internal fun JvmCompilationResult.generatedFileContent(name: String): String {
    val file =
        sourcesGeneratedBySymbolProcessor.firstOrNull { it.name == name }
            ?: error(
                "Generated file '$name' not found among: " +
                    sourcesGeneratedBySymbolProcessor.map { it.name }.toList()
            )
    return file.readText()
}

/** Returns the names of all KSP-generated source files. */
@OptIn(ExperimentalCompilerApi::class)
internal fun JvmCompilationResult.generatedNames(): List<String> =
    sourcesGeneratedBySymbolProcessor.map { it.name }.toList()
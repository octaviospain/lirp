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
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi

/**
 * Shared KSP in-process compilation helper for lirp-ksp tests.
 *
 * Each test file passes its own [SymbolProcessorProvider] so processor isolation is preserved.
 * [generatedFileContent] and [generatedNames] provide uniform access to KSP output.
 */
@OptIn(ExperimentalCompilerApi::class)
internal object KspTestSupport {

    /**
     * Compiles [sources] with [provider] registered as the sole KSP processor.
     *
     * @param options KSP processor options forwarded via [KotlinCompilation.kspProcessorOptions].
     * @param withCompilation Whether to also run full Kotlin compilation after KSP (default `true`).
     */
    fun compile(
        provider: SymbolProcessorProvider,
        vararg sources: SourceFile,
        options: Map<String, String> = emptyMap(),
        withCompilation: Boolean = true
    ): JvmCompilationResult {
        val compilation =
            KotlinCompilation().apply {
                this.sources = sources.toList()
                inheritClassPath = true
            }
        compilation.configureKsp { this.withCompilation = withCompilation }
        if (options.isNotEmpty()) {
            compilation.kspProcessorOptions.putAll(options)
        }
        compilation.symbolProcessorProviders += provider
        return compilation.compile()
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
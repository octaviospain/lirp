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

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * Base class for single-processor [SymbolProcessorProvider] implementations in LIRP.
 *
 * Each concrete subclass provides exactly one [SymbolProcessor] factory via [createProcessor],
 * keeping KSP failure isolation intact — a crash in one processor cannot prevent another from
 * running because they are registered as separate services in `META-INF/services`.
 *
 * The [create] override is `final` so subclasses cannot bypass the environment-extraction
 * convention; all customisation goes through [createProcessor].
 */
abstract class LirpProcessorProvider : SymbolProcessorProvider {

    /**
     * Factory method called once per KSP processing run to instantiate the concrete processor.
     *
     * @param codeGenerator the KSP code generator used by the processor to emit source files
     * @param logger the KSP logger for diagnostic messages
     * @return the [SymbolProcessor] that will process annotated symbols in this round
     */
    abstract fun createProcessor(codeGenerator: CodeGenerator, logger: KSPLogger): SymbolProcessor

    final override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        createProcessor(environment.codeGenerator, environment.logger)
}
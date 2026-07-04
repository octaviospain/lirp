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

/**
 * Producer-side single source of truth for the generated-name suffixes used by every KSP emitter
 * in this module.
 *
 * The runtime consumer ([net.transgressoft.lirp.persistence.KspAccessorLoader] in lirp-core)
 * intentionally maintains a parallel copy of the suffixes it needs, because lirp-core must not
 * carry a runtime dependency on the compile-only lirp-ksp module. Any suffix change here that
 * affects a runtime-loaded accessor must therefore also be mirrored in `KspAccessorLoader`.
 */
internal object LirpGenNames {
    const val INDEX_ACCESSOR_SUFFIX = "_LirpIndexAccessor"
    const val REACTIVE_PROPERTY_ACCESSOR_SUFFIX = "_LirpReactivePropertyAccessor"
    const val REF_ACCESSOR_SUFFIX = "_LirpRefAccessor"
    const val VIA_ACCESSOR_SUFFIX = "_LirpViaAccessor"
    const val RAW_INITIALIZER_SUFFIX = "_LirpRawInitializer"
    const val REGISTRY_INFO_SUFFIX = "_LirpRegistryInfo"
    const val TABLE_DEF_SUFFIX = "_LirpTableDef"
    const val JUNCTION_TABLE_DEF_SUFFIX = "_LirpJunctionTableDef"
    const val FX_SCALAR_ACCESSOR_SUFFIX = "_LirpFxScalarAccessor"
    const val TO_ONE_EXT_ACCESSOR_SUFFIX = "_LirpToOneExtAccessor"
}
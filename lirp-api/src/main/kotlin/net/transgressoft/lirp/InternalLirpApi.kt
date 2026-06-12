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

package net.transgressoft.lirp

/**
 * Marks an API element as part of the internal LIRP adapter SPI.
 *
 * Elements annotated with `@InternalLirpApi` are cross-module seams required by LIRP's own
 * persistence and UI adapter layers (such as `lirp-fx`). They are **not** part of the public API
 * and carry no semantic-versioning guarantees: signatures may change or be removed in any release
 * without a deprecation cycle.
 *
 * **Usage policy:** Opt in only when building a first-party LIRP adapter that must bridge across
 * module boundaries (e.g. a JavaFX layer wiring into the core projection engine). Application
 * code and third-party libraries should never depend on these members directly; the contract they
 * implement can change without notice.
 *
 * To opt in for a single call site, annotate with `@OptIn(InternalLirpApi::class)`. To opt in
 * for an entire module (appropriate for adapter modules that own the SPI), add the compiler
 * argument `-opt-in=net.transgressoft.lirp.InternalLirpApi` to the Kotlin compilation task.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "Internal LIRP adapter SPI — not part of the public API and exempt from semantic-versioning guarantees. " +
            "Opt in only if you are building a LIRP persistence/UI adapter."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class InternalLirpApi
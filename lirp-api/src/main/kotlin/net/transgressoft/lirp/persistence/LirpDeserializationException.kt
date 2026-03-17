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

/**
 * Thrown when a JSON file cannot be deserialized into the expected entity format during
 * repository construction or loading.
 *
 * This exception is defined in the `lirp-api` module so consumers can catch it
 * without depending on the `lirp-core` implementation module.
 *
 * The [message] includes the absolute path of the file that failed deserialization,
 * providing actionable context for debugging. The original serialization exception
 * is available via [cause].
 *
 * Per the v1.1 design decision: fail-fast with no configurable fallback. If a repository
 * is constructed with a corrupted or malformed JSON file, this exception propagates to
 * the caller immediately.
 *
 * @param message Description of the failure, including the file path for debugging context
 * @param cause The underlying serialization exception that triggered this failure
 */
class LirpDeserializationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
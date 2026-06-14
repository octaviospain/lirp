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

package net.transgressoft.lirp.event

/**
 * Discriminator for the async operation type at the point where a failure occurred.
 *
 * Mirrors the `lirp.operation` MDC key so that a log line and an [LirpErrorHandler]
 * callback describe the same failure with the same vocabulary.
 *
 * Members map to the distinct async failure sites in the framework:
 * - Entity-mutation operations: [CREATE], [UPDATE], [DELETE], [CLEAR]
 * - Persistence pipeline: [FLUSH]
 * - Event emission: [EMIT]
 * - Optimistic-lock recovery: [RECOVER]
 *
 * Adding new members is binary-compatible; existing values are stable.
 */
enum class LirpOperation {
    CREATE,
    UPDATE,
    DELETE,
    CLEAR,
    FLUSH,
    EMIT,
    RECOVER
}
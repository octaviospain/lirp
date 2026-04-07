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

import io.kotest.core.annotation.DisplayName

/**
 * Integration tests for [AudioItem] and [MutableAudioPlaylist] backed by [VolatileRepository].
 *
 * Inherits all shared test scenarios from [MusicCommonsIntegrationTestBase]; provides volatile
 * repository factories via [DefaultAudioLibrary] and [DefaultPlaylistHierarchy].
 */
@DisplayName("Music-commons integration (Volatile)")
class MusicCommonsVolatileIntegrationTest : MusicCommonsIntegrationTestBase() {

    override fun createAudioItemRepo(ctx: LirpContext) = DefaultAudioLibrary(ctx)

    override fun createPlaylistRepo(ctx: LirpContext) = DefaultPlaylistHierarchy(ctx)
}
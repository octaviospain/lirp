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

package net.transgressoft.lirp.persistence.fx

import javafx.application.Platform

/**
 * Headless JavaFX toolkit initializer for test environments.
 * Uses Monocle as the glass platform to avoid requiring a display.
 */
object FxToolkitInit {

    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized)
            return
        synchronized(this) {
            if (initialized)
                return
            System.setProperty("java.awt.headless", "true")
            System.setProperty("glass.platform", "Monocle")
            System.setProperty("monocle.platform", "Headless")
            System.setProperty("prism.order", "sw")
            try {
                Platform.startup {}
            } catch (_: IllegalStateException) {
                // Toolkit already initialized
            }
            Platform.setImplicitExit(false)
            initialized = true
        }
    }
}
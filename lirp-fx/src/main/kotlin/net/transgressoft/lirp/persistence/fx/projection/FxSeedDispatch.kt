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

package net.transgressoft.lirp.persistence.fx.projection

import javafx.application.Platform
import java.util.concurrent.CountDownLatch

/**
 * Runs [block] on the JavaFX Application Thread, blocking the caller until it completes, when
 * [dispatchToFxThread] is set and the current thread is not already the FX Application Thread.
 * Otherwise runs [block] inline on the current thread.
 *
 * The value-transform projections invoke their `fxFactory` through this during the initial seed so
 * the two-phase contract — `fxFactory` constructs FX values on the FX Application Thread — holds even
 * when the first map access happens off that thread. The [Platform.isFxApplicationThread] guard is
 * required: an unconditional dispatch-and-wait would deadlock when the first access is itself on the
 * FX thread (the latch would never be counted down).
 */
internal fun runSeedOnFxThread(dispatchToFxThread: Boolean, block: () -> Unit) {
    if (dispatchToFxThread && !Platform.isFxApplicationThread()) {
        val latch = CountDownLatch(1)
        Platform.runLater {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    } else {
        block()
    }
}
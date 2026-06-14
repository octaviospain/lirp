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

import net.transgressoft.lirp.event.LirpOperation
import net.transgressoft.lirp.testing.LogCapture
import net.transgressoft.lirp.testing.ReactiveScopeSerialization
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.slf4j.MDC
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that MDC keys are propagated across the async thread hop in the debounced flush
 * pipeline. Keys set before `launch(MDCContext())` must appear on the async thread's log
 * lines; after the launch returns, those keys must be absent from the caller thread's MDC.
 */
class MdcPropagationSpec : StringSpec() {

    init {
        extension(ReactiveScopeSerialization)

        "PersistentRepositoryBase flush log lines carry lirp.repository and lirp.operation=FLUSH in MDC" {
            val repoName = "mdc-test-audio-repo"
            val capture = LogCapture()

            // The logger in PersistentRepositoryBase uses javaClass.name; InMemoryAudioRepo
            // is defined below, so the logger name is the concrete class's binary name.
            capture.attach(InMemoryAudioRepo::class.java.name)

            val repo = InMemoryAudioRepo(repoName)
            try {
                // Trigger a flush by adding an entity (debounce window is 50 ms)
                repo.add(MutableAudioItem(1, "test-track"))

                eventually(5.seconds) {
                    val flushLogs =
                        capture.logs.filter {
                            it.mdc["lirp.operation"] == LirpOperation.FLUSH.name
                        }
                    flushLogs shouldNotBe emptyList<Any>()

                    val flushLog = flushLogs.first()
                    flushLog.mdc["lirp.repository"] shouldBe repoName
                    flushLog.mdc["lirp.operation"] shouldBe LirpOperation.FLUSH.name
                }
            } finally {
                repo.close()
                capture.detach()
            }
        }

        "PersistentRepositoryBase flush log lines do not carry lirp.entityId in MDC" {
            val capture = LogCapture()
            capture.attach(InMemoryAudioRepo::class.java.name)

            val repo = InMemoryAudioRepo("mdc-no-entity-id-repo")
            try {
                repo.add(MutableAudioItem(2, "track-without-entity-id"))

                eventually(5.seconds) {
                    val flushLogs =
                        capture.logs.filter {
                            it.mdc["lirp.operation"] == LirpOperation.FLUSH.name
                        }
                    flushLogs shouldNotBe emptyList<Any>()

                    // The batch flush launch does not populate lirp.entityId
                    flushLogs.forEach { log ->
                        log.mdc.shouldNotContainKey("lirp.entityId")
                    }
                }
            } finally {
                repo.close()
                capture.detach()
            }
        }

        "PersistentRepositoryBase caller thread MDC is clean after scheduleFlush launches" {
            val repo = InMemoryAudioRepo("mdc-cleanup-repo")
            try {
                // Trigger scheduleFlush via add()
                repo.add(MutableAudioItem(3, "cleanup-test"))

                // Immediately after add() returns, the caller thread's MDC must not carry lirp keys
                val callerMdc = MDC.getCopyOfContextMap() ?: emptyMap<String, String>()
                callerMdc.shouldNotContainKey("lirp.repository")
                callerMdc.shouldNotContainKey("lirp.operation")
            } finally {
                repo.close()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Minimal in-process PersistentRepositoryBase subclass for MDC propagation tests.
// Uses the canonical MutableAudioItem fixture entity.
// ---------------------------------------------------------------------------

/**
 * In-memory [PersistentRepositoryBase] subclass for MDC tests. Stores [AudioItem]
 * entities, logging in [writePending] so MDC keys are observable on async flush log lines.
 */
private class InMemoryAudioRepo(
    name: String
) : PersistentRepositoryBase<Int, AudioItem>(name = name, loadOnInit = false) {

    private val log = io.github.oshai.kotlinlogging.KotlinLogging.logger(javaClass.name)

    init {
        load()
    }

    override fun loadFromStore(): Map<Int, AudioItem> = emptyMap()

    override fun writePending(
        inserts: List<AudioItem>,
        updates: List<PendingUpdate<Int, AudioItem>>,
        deletes: List<Pair<Int, Long?>>,
        hadClear: Boolean
    ) {
        // Log at TRACE so MDC keys are observable in tests
        log.trace { "writePending: inserts=${inserts.size}, updates=${updates.size}, deletes=${deletes.size}" }
    }
}
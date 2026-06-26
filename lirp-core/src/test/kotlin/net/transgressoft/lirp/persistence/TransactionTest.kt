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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.event.LirpErrorContext
import net.transgressoft.lirp.event.LirpErrorHandler
import net.transgressoft.lirp.event.LirpOperation
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.PropertyChanged
import net.transgressoft.lirp.testing.ReactiveScopeSerialization
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests for the [transaction] free function covering the Volatile-like repository path:
 * commit, rollback, deferred-event collapse, pre-flush failure, nesting, and
 * [LirpErrorHandler] notify-only semantics.
 */
@DisplayName("transaction() core repository contract")
internal class TransactionTest : StringSpec() {

    init {
        extension(ReactiveScopeSerialization)

        "transaction commits in-memory mutations and fires collapsed events to subscribers" {
            val repo = InMemoryAudioItemRepo()
            val item = repo.create(1, "Bohemian Rhapsody", "A Night at the Opera")
            val events = mutableListOf<MutationEvent<Int, AudioItem>>()
            item.subscribe { events.add(it) }

            try {
                transaction(repo) { r ->
                    (r.findById(1).get() as MutableAudioItem).title = "Killer Queen"
                }

                (item as MutableAudioItem).title shouldBe "Killer Queen"
                events shouldHaveSize 1
                events.single().shouldBeInstanceOf<PropertyChanged<*, *, *>>()
            } finally {
                repo.close()
            }
        }

        "transaction rollback on block throw restores in-memory state and discards events" {
            val repo = InMemoryAudioItemRepo()
            val item = repo.create(2, "Don't Stop Me Now", "Jazz")
            val events = mutableListOf<MutationEvent<Int, AudioItem>>()
            item.subscribe { events.add(it) }

            try {
                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        (r.findById(2).get() as MutableAudioItem).title = "mutated-inside"
                        throw RuntimeException("block failure")
                    }
                }

                (item as MutableAudioItem).title shouldBe "Don't Stop Me Now"
                events.shouldBeEmpty()
            } finally {
                repo.close()
            }
        }

        "deferred events collapse empty-to-Rock-to-Jazz into a single empty-to-Jazz PropertyChanged" {
            val repo = InMemoryAudioItemRepo()
            val item = repo.create(3, "", "")
            val events = mutableListOf<MutationEvent<Int, AudioItem>>()
            item.subscribe { events.add(it) }

            try {
                transaction(repo) { r ->
                    val e = r.findById(3).get() as MutableAudioItem
                    e.title = "Rock"
                    e.title = "Jazz"
                }

                events shouldHaveSize 1
                @Suppress("UNCHECKED_CAST")
                val propertyChanged = events.single() as PropertyChanged<Int, AudioItem, String>
                propertyChanged.oldValue shouldBe ""
                propertyChanged.newValue shouldBe "Jazz"
            } finally {
                repo.close()
            }
        }

        "pre-flush failure aborts before the block runs and throws LirpTransactionException" {
            var blockExecuted = false
            val repo = FailingFlushAudioItemRepo()
            // Stage a pending op so the pre-flush actually attempts to drain.
            repo.add(MutableAudioItem(1, "pending-item", "") as AudioItem)

            try {
                shouldThrow<LirpTransactionException> {
                    transaction(repo) { _ ->
                        blockExecuted = true
                    }
                }

                blockExecuted shouldBe false
            } finally {
                try {
                    repo.close()
                } catch (_: Exception) {
                }
            }
        }

        "nested transaction on the same repo joins the outer and results in one collapsed commit" {
            val repo = InMemoryAudioItemRepo()
            val item = repo.create(4, "We Will Rock You", "News of the World")
            val events = mutableListOf<MutationEvent<Int, AudioItem>>()
            item.subscribe { events.add(it) }

            try {
                transaction(repo) { r ->
                    (r.findById(4).get() as MutableAudioItem).title = "outer"
                    transaction(r) { inner ->
                        (inner.findById(4).get() as MutableAudioItem).title = "inner"
                    }
                }

                (item as MutableAudioItem).title shouldBe "inner"
                // Both mutations collapse to one event (outer title → inner title).
                events shouldHaveSize 1
            } finally {
                repo.close()
            }
        }

        "nested transaction on a different repo throws LirpTransactionException immediately" {
            val repo1 = InMemoryAudioItemRepo()
            val repo2 = InMemoryAudioItemRepo("InMemoryAudioItems2")
            repo1.create(5, "Radio Ga Ga", "The Works")

            try {
                shouldThrow<LirpTransactionException> {
                    transaction(repo1) { _ ->
                        transaction(repo2) { _ ->
                            // unreachable
                        }
                    }
                }
            } finally {
                repo1.close()
                repo2.close()
            }
        }

        "LirpErrorHandler fires with operation=TRANSACTION and entity ids but no field values on failure" {
            val handlerInvocations = CopyOnWriteArrayList<Pair<Throwable, LirpErrorContext>>()
            val handler = LirpErrorHandler { t, ctx -> handlerInvocations.add(t to ctx) }

            val repo = InMemoryAudioItemRepo("transaction-error-notify-repo", onError = handler)
            repo.add(MutableAudioItem(6, "Another One Bites the Dust", "The Game") as AudioItem)

            try {
                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        (r.findById(6).get() as MutableAudioItem).title = "mutated"
                        throw RuntimeException("intentional failure")
                    }
                }

                handlerInvocations shouldHaveSize 1
                val (_, ctx) = handlerInvocations.single()
                ctx.operation shouldBe LirpOperation.TRANSACTION
                // Carries entity identity only — not field values.
                ctx.entityIds shouldHaveSize 1
                ctx.entityIds.first() shouldBe 6
            } finally {
                repo.close()
            }
        }

        "onError handler suppresses the exception and receives the failure throwable" {
            var capturedThrowable: Throwable? = null
            val repo = InMemoryAudioItemRepo()
            repo.add(MutableAudioItem(7, "Somebody to Love", "A Day at the Races") as AudioItem)

            try {
                transaction(repo, onError = { capturedThrowable = throwable }) { r ->
                    (r.findById(7).get() as MutableAudioItem).title = "changed"
                    throw RuntimeException("handled failure")
                }

                capturedThrowable?.message shouldBe "handled failure"
                (repo.findById(7).get() as MutableAudioItem).title shouldBe "Somebody to Love"
            } finally {
                repo.close()
            }
        }
    }
}

/**
 * Minimal [PersistentRepositoryBase] backed entirely by in-memory state. This is distinct
 * from [VolatileRepository] — which does NOT extend [PersistentRepositoryBase] — so the
 * [transaction] free function (which requires a [PersistentRepositoryBase]) can be used.
 */
internal class InMemoryAudioItemRepo(
    name: String = "InMemoryAudioItems",
    onError: LirpErrorHandler? = null
) : PersistentRepositoryBase<Int, AudioItem>(name = name, loadOnInit = false, onError = onError) {

    init {
        load()
    }

    fun create(id: Int, title: String, albumName: String): AudioItem =
        MutableAudioItem(id, title, albumName).also { add(it as AudioItem) }

    override fun loadFromStore(): Map<Int, AudioItem> = emptyMap()

    override fun writePending(
        inserts: List<AudioItem>,
        updates: List<PendingUpdate<Int, AudioItem>>,
        deletes: List<Pair<Int, Long?>>,
        hadClear: Boolean
    ) = Unit
}

/**
 * [PersistentRepositoryBase] subclass that always fails during [writePending].
 * Used to simulate a pre-flush failure so the transaction block is never reached.
 */
internal class FailingFlushAudioItemRepo :
    PersistentRepositoryBase<Int, AudioItem>(name = "failing-flush-repo", loadOnInit = false) {

    init {
        load()
    }

    override fun loadFromStore(): Map<Int, AudioItem> = emptyMap()

    override fun writePending(
        inserts: List<AudioItem>,
        updates: List<PendingUpdate<Int, AudioItem>>,
        deletes: List<Pair<Int, Long?>>,
        hadClear: Boolean
    ) = throw RuntimeException("simulated pre-flush failure")
}
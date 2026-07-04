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
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Tests for the [transaction] free function covering the Volatile-like repository path:
 * commit, rollback, deferred-event collapse, pre-flush failure, nesting, and
 * [LirpErrorHandler] notify-only semantics.
 */
@DisplayName("transaction() core repository contract")
internal class TransactionTest : StringSpec() {

    /**
     * Runs [block] against a freshly-created [InMemoryAudioItemRepo], always closing the repo
     * afterwards. Collapses the create-try-finally-close scaffolding shared by nearly every
     * transaction test so each body reads as its own arrange/act/assert.
     */
    suspend fun withRepo(
        name: String = "InMemoryAudioItems",
        onError: LirpErrorHandler? = null,
        block: suspend (InMemoryAudioItemRepo) -> Unit
    ) {
        val repo = InMemoryAudioItemRepo(name, onError)
        try {
            block(repo)
        } finally {
            repo.close()
        }
    }

    init {
        extension(ReactiveScopeSerialization)

        "transaction commits in-memory mutations and fires collapsed events to subscribers" {
            withRepo { repo ->
                val item = repo.create(1, "Bohemian Rhapsody", "A Night at the Opera")
                val events = mutableListOf<MutationEvent<Int, AudioItem>>()
                item.subscribe { events.add(it) }

                transaction(repo) { r ->
                    (r.findById(1).get() as MutableAudioItem).title = "Killer Queen"
                }

                (item as MutableAudioItem).title shouldBe "Killer Queen"
                events shouldHaveSize 1
                events.single().shouldBeInstanceOf<PropertyChanged<*, *, *>>()
            }
        }

        "transaction rollback on block throw restores in-memory state and discards events" {
            withRepo { repo ->
                val item = repo.create(2, "Don't Stop Me Now", "Jazz")
                val events = mutableListOf<MutationEvent<Int, AudioItem>>()
                item.subscribe { events.add(it) }

                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        (r.findById(2).get() as MutableAudioItem).title = "mutated-inside"
                        throw RuntimeException("block failure")
                    }
                }

                (item as MutableAudioItem).title shouldBe "Don't Stop Me Now"
                events.shouldBeEmpty()
            }
        }

        "deferred events collapse empty-to-Rock-to-Jazz into a single empty-to-Jazz PropertyChanged" {
            withRepo { repo ->
                val item = repo.create(3, "", "")
                val events = mutableListOf<MutationEvent<Int, AudioItem>>()
                item.subscribe { events.add(it) }

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
            withRepo { repo ->
                val item = repo.create(4, "We Will Rock You", "News of the World")
                val events = mutableListOf<MutationEvent<Int, AudioItem>>()
                item.subscribe { events.add(it) }

                transaction(repo) { r ->
                    (r.findById(4).get() as MutableAudioItem).title = "outer"
                    transaction(r) { inner ->
                        (inner.findById(4).get() as MutableAudioItem).title = "inner"
                    }
                }

                (item as MutableAudioItem).title shouldBe "inner"
                // Both mutations collapse to one event (outer title → inner title).
                events shouldHaveSize 1
            }
        }

        "nested transaction on a different repo throws LirpTransactionException immediately" {
            withRepo { repo1 ->
                withRepo("InMemoryAudioItems2") { repo2 ->
                    repo1.create(5, "Radio Ga Ga", "The Works")

                    shouldThrow<LirpTransactionException> {
                        transaction(repo1) { _ ->
                            transaction(repo2) { _ ->
                                // unreachable
                            }
                        }
                    }
                }
            }
        }

        "LirpErrorHandler fires with operation=TRANSACTION and entity ids but no field values on failure" {
            val handlerInvocations = CopyOnWriteArrayList<Pair<Throwable, LirpErrorContext>>()
            val handler = LirpErrorHandler { t, ctx -> handlerInvocations.add(t to ctx) }

            withRepo("transaction-error-notify-repo", onError = handler) { repo ->
                repo.add(MutableAudioItem(6, "Another One Bites the Dust", "The Game") as AudioItem)

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
            }
        }

        "onError handler suppresses the exception and receives the failure throwable" {
            var capturedThrowable: Throwable? = null
            withRepo { repo ->
                repo.add(MutableAudioItem(7, "Somebody to Love", "A Day at the Races") as AudioItem)

                transaction(repo, onError = { capturedThrowable = throwable }) { r ->
                    (r.findById(7).get() as MutableAudioItem).title = "changed"
                    throw RuntimeException("handled failure")
                }

                capturedThrowable?.message shouldBe "handled failure"
                (repo.findById(7).get() as MutableAudioItem).title shouldBe "Somebody to Love"
            }
        }

        "entity added inside a failing block is absent from the repo after rollback" {
            withRepo { repo ->
                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        r.add(MutableAudioItem(8, "Flash", "The Game") as AudioItem)
                        throw RuntimeException("block failure")
                    }
                }

                // The insert was rolled back — entity must not be present.
                repo.findById(8).isPresent shouldBe false
                repo.size() shouldBe 0
            }
        }

        "entity removed inside a failing block is re-added to the repo after rollback" {
            withRepo { repo ->
                repo.add(MutableAudioItem(9, "Innuendo", "Innuendo") as AudioItem)

                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        r.remove(r.findById(9).get())
                        throw RuntimeException("block failure")
                    }
                }

                // The delete was rolled back — entity must still be present.
                repo shouldHaveItemWithTitle (9 to "Innuendo")
            }
        }

        "lastDateModified is restored to its pre-block value after a failing block" {
            withRepo { repo ->
                val item = repo.create(10, "The Show Must Go On", "Innuendo")
                val preDateModified = item.lastDateModified

                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        (r.findById(10).get() as MutableAudioItem).title = "mutated-title"
                        throw RuntimeException("block failure")
                    }
                }

                // lastDateModified must revert to the pre-block value after rollback.
                item.lastDateModified shouldBe preDateModified
            }
        }

        "transaction block that suspends onto a different thread still captures scalar mutations" {
            // Verifies that _txEventBuffer is propagated across coroutine suspension via
            // asContextElement: a scalar mutation after a thread switch must still be buffered
            // (not published directly) and committed in the same transaction.
            withRepo { repo ->
                val item = repo.create(11, "Bicycle Race", "Jazz")
                val events = mutableListOf<MutationEvent<Int, AudioItem>>()
                item.subscribe { events.add(it) }

                transaction(repo) { r ->
                    // Real suspension that resumes on a worker thread.
                    withContext(Dispatchers.IO) { yield() }
                    // Mutation after thread switch — must be buffered, not published.
                    (r.findById(11).get() as MutableAudioItem).title = "Fat Bottomed Girls"
                }

                // Exactly one collapsed event fired after commit — none during the block.
                events shouldHaveSize 1
                (item as MutableAudioItem).title shouldBe "Fat Bottomed Girls"
            }
        }

        "transaction remove-then-add same pre-existing id results in committed UPDATE" {
            withRepo { repo ->
                repo.create(20, "Killer Queen", "Sheer Heart Attack")

                transaction(repo) { r ->
                    val existing = r.findById(20).get()
                    r.remove(existing)
                    r.add(MutableAudioItem(20, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem)
                }

                // After commit: entity present with re-added value (no duplicate-key failure).
                repo shouldHaveItemWithTitle (20 to "Bohemian Rhapsody")
                repo.size() shouldBe 1
            }
        }

        "transaction add-then-remove same new id is a no-op after commit" {
            withRepo { repo ->
                transaction(repo) { r ->
                    r.add(MutableAudioItem(21, "Radio Ga Ga", "The Works") as AudioItem)
                    r.remove(r.findById(21).get())
                }

                // After commit: entity is absent because insert+delete cancel out.
                repo.findById(21).isPresent shouldBe false
                repo.size() shouldBe 0
            }
        }

        "cross-thread transaction releases the flush lock so later transactions and close do not deadlock" {
            // Regression guard: the transaction holds a thread-owned flush lock across the suspending
            // block. When the block switches dispatchers, the lock must be released on its owning
            // thread; otherwise it leaks and the next flush-lock acquisition (a later transaction or
            // close()) blocks forever. withTimeout turns a regression into a fast failure instead of a
            // hung suite.
            withTimeout(10_000) {
                withRepo { repo ->
                    repo.create(30, "Don't Stop Me Now", "Jazz")
                    transaction(repo) { r ->
                        withContext(Dispatchers.IO) { yield() }
                        (r.findById(30).get() as MutableAudioItem).title = "Somebody to Love"
                    }
                    // Would hang here if the first cross-thread transaction leaked the flush lock.
                    transaction(repo) { r ->
                        withContext(Dispatchers.Default) { yield() }
                        (r.findById(30).get() as MutableAudioItem).title = "Under Pressure"
                    }
                    repo shouldHaveItemWithTitle (30 to "Under Pressure")
                }
            }
        }

        "transaction fails fast when the flush lock cannot be acquired instead of hanging forever" {
            // A flush that never releases the lock (a stuck backing-store write, or a lock leaked by an
            // earlier transaction) must surface as a bounded, diagnosable failure rather than an
            // indefinite hang. Holding the lock on a foreign thread simulates that stuck state; the
            // per-call FlushLockTimeoutOverride shrinks the acquisition window so the test does not wait
            // the full production timeout, and is coroutine-scoped so it cannot leak into other specs.
            // withTimeout is the safety net that turns a regression (unbounded wait) into a fast failure.
            val repo = InMemoryAudioItemRepo()
            repo.create(40, "Fat Bottomed Girls", "Jazz")
            repo.lockFlush()
            try {
                withTimeout(5_000) {
                    val failure =
                        shouldThrow<LirpTransactionException> {
                            withContext(FlushLockTimeoutOverride(200.milliseconds)) {
                                transaction(repo) { r ->
                                    (r.findById(40).get() as MutableAudioItem).title = "Bicycle Race"
                                }
                            }
                        }
                    failure.message shouldContain "Could not acquire the transaction flush lock"
                }
                // The block never ran: the lock was never acquired, so in-memory state is untouched.
                repo shouldHaveItemWithTitle (40 to "Fat Bottomed Girls")
            } finally {
                repo.unlockFlush()
                repo.close()
            }
        }
    }
}

/**
 * Asserts the repository holds an entity under `idAndTitle.first` whose title equals
 * `idAndTitle.second`, keeping the recurring "present with expected title" claim to one line.
 */
private infix fun InMemoryAudioItemRepo.shouldHaveItemWithTitle(idAndTitle: Pair<Int, String>) {
    findById(idAndTitle.first).shouldBePresent { it.title shouldBe idAndTitle.second }
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
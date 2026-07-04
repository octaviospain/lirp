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

import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.ReactiveScope
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/**
 * Tracks the number of active transaction blocks running on the current thread.
 *
 * When this counter is greater than zero when entering a new `transaction` call, and the
 * incoming repo has `transactionDepth == 0`, the call is a cross-repo nesting that is not
 * supported in the single-participant form.
 */
private val activeTransactionCount = ThreadLocal.withInitial { 0 }

/**
 * Upper bound on how long the transaction commit path waits for the repository's flush lock.
 *
 * Sized far above any legitimate flush (a debounced write completes in ~1s, a few seconds at worst
 * under load) so it never trips on healthy contention, yet finite so a stuck flush or a lock leaked
 * by an earlier transaction fails fast with a diagnostic instead of hanging indefinitely.
 *
 * Exposed as a mutable seam so tests can shrink it to assert the fail-fast path without waiting the
 * full production window; production code never reassigns it.
 */
internal var flushLockAcquireTimeout = 60.seconds

/**
 * Executes [block] as an atomic unit of work against [repo].
 *
 * All mutations to entities belonging to [repo] that occur inside [block] are captured
 * and committed synchronously as a single transaction. On success, buffered mutation events
 * are released (collapsed) to subscribers. On failure, in-memory state is restored to the
 * values captured before the block ran and buffered events are discarded silently.
 *
 * **Commit sequence:**
 * 1. Pending debounce jobs are cancelled.
 * 2. The flush lock is acquired for the full pre-flush + block + commit window.
 * 3. All pending ops are drained to the store (pre-flush) so the block starts from a clean baseline.
 * 4. Snapshots are captured and event buffering is installed on all currently loaded entities.
 * 5. [block] executes; property-change events are buffered instead of published.
 * 6. On success: [PersistentRepositoryBase.commitTransactionBuffer] writes the buffer atomically;
 *    collapsed events are released to subscribers.
 * 7. On failure: [ReactiveEntityBase.restoreSnapshot] reverts in-memory state before the caller
 *    observes the error; buffered events are discarded.
 *
 * **`onError` vs typed exception:**
 * When [onError] is present it is invoked with the failure details and no exception propagates.
 * When absent, a [LirpTransactionException] (or [TransactionConflictException] for `@Version`
 * conflicts) is thrown after in-memory rollback completes.
 *
 * **Nesting:**
 * - A nested call on the same [repo] flattens into the outer transaction — the outer block owns
 *   commit and rollback; the inner block runs directly in the existing buffer.
 * - A nested call on a different repository throws [LirpTransactionException] immediately.
 *
 * **Block contract:**
 * The block should be short-lived. The flush lock is held for its entire duration, stalling the
 * debounce flush pipeline for the same repository. Long-running or blocking operations inside
 * the block risk pipeline starvation.
 *
 * @param repo the repository that owns the transaction
 * @param onError optional call-scoped failure handler; see [TransactionErrorContext]
 * @param block the mutation block; receives the repository as its argument
 * @throws LirpTransactionException when [onError] is absent and a non-conflict failure occurs,
 *         or when the call is a nested transaction on a different repository
 * @throws TransactionConflictException when [onError] is absent and an `@Version` conflict is detected
 */
suspend fun <K : Comparable<K>, R : ReactiveEntity<K, R>> transaction(
    repo: PersistentRepositoryBase<K, R>,
    onError: (TransactionErrorContext<K, R>.() -> Unit)? = null,
    block: suspend (PersistentRepositoryBase<K, R>) -> Unit
) {
    // Only treat this call as nesting when THIS execution already owns a transaction
    // (activeTransactionCount is propagated across suspension via asContextElement). Checking
    // repo.transactionDepth first would let a separate concurrent transaction(repo) call observe
    // depth > 0, skip the flush lock, and corrupt the active buffer — so ownership is proven first.
    if (activeTransactionCount.get() > 0) {
        // NESTING (same repo): join the outer transaction without starting a new commit cycle.
        if (repo.transactionDepth > 0) {
            repo.transactionDepth++
            try {
                block(repo)
            } finally {
                repo.transactionDepth--
            }
            return
        }

        // NESTING (different repo): single-participant transactions do not support cross-repo nesting.
        throw LirpTransactionException(
            "Nested transactions on different repositories are not supported. " +
                "Only single-participant transactions or nested calls on the same repository are allowed."
        )
    }

    // Cancel the debounce timer before acquiring the flush lock to prevent the debounce
    // coroutine from contending for the lock while the transaction holds it.
    repo.cancelDebounce()

    // Run the entire transaction on the dedicated, thread-pinned transaction dispatcher.
    // Because the block is suspend and Kotlin forbids suspending inside withLock { }, the
    // flush lock is managed with explicit lock/unlock under a try/finally. [flushLock] is a
    // thread-owned ReentrantLock, so its acquire (lockFlush) and release (unlockFlush) MUST run
    // on the same thread even though the user block may suspend and switch dispatchers. ioScope's
    // limitedParallelism(1) dispatcher serializes coroutines but can resume them on a different
    // pool thread, which would release the lock from a non-owner thread and leak it; the pinned
    // [ReactiveScope.transactionDispatcher] guarantees same-thread lock ownership. ioScope's Job and
    // exception-handler are retained (only the dispatcher is swapped). The single-thread guarantee
    // also keeps the thread-local activeTransactionCount and per-entity _txEventBuffer checks reliable.
    withContext(
        ReactiveScope.ioScope.coroutineContext.minusKey(kotlin.coroutines.ContinuationInterceptor) +
            ReactiveScope.transactionDispatcher +
            activeTransactionCount.asContextElement(activeTransactionCount.get())
    ) {
        // Bounded acquisition: a flush that never releases the lock (a stuck backing-store write or a
        // lock leaked by an earlier transaction) must fail fast and loud instead of hanging the caller
        // — and, in the test suite, the whole JVM. The ceiling is far larger than any legitimate flush
        // (a debounced write is ~1s, seconds at worst under load), so it never trips on healthy
        // contention. Acquired outside the try so a failed acquisition does not reach unlockFlush.
        val acquired =
            try {
                repo.tryLockFlush(flushLockAcquireTimeout)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LirpTransactionException(
                    "Interrupted while acquiring the transaction flush lock on '${repo.repoName}'",
                    interrupted
                )
            }
        if (!acquired) {
            throw LirpTransactionException(
                "Could not acquire the transaction flush lock on '${repo.repoName}' within " +
                    "$flushLockAcquireTimeout — a prior flush or transaction likely did not release it. " +
                    "Aborting to avoid an indefinite hang."
            )
        }
        // Everything that can throw — including snapshot capture and event-buffer installation — runs
        // inside the try so the finally always releases the lock and restores per-repo transaction state.
        var loadedEntities: List<ReactiveEntityBase<K, R>> = emptyList()
        try {
            activeTransactionCount.set(activeTransactionCount.get() + 1)
            val buffer = TransactionBuffer(repo)
            repo.transactionDepth = 1
            repo.activeTransactionBuffer = buffer

            // Capture snapshots and install event buffering on all currently loaded entities.
            // The element cast is a safe runtime `is ReactiveEntityBase` check (mapNotNull drops
            // non-matches); only the erased K/R type arguments are unchecked, which is sound here
            // because every entity resident in this repository is a ReactiveEntityBase<K, R>.
            @Suppress("UNCHECKED_CAST")
            val resident: List<ReactiveEntityBase<K, R>> = repo.mapNotNull { it as? ReactiveEntityBase<K, R> }
            loadedEntities = resident
            loadedEntities.forEach { entity ->
                buffer.entitySnapshots[entity.id] = entity.captureSnapshot()
                entity._txEventBuffer.set(buffer.deferredEvents)
            }

            try {
                // Pre-flush: drain pending ops before the block runs so the backing store starts
                // from a consistent baseline. A failure here aborts before the block executes.
                repo.drainPendingNoLock()

                // Propagate each entity's _txEventBuffer across coroutine suspension points.
                // This inner withContext also re-pins activeTransactionCount so nested transaction
                // checks remain accurate even when the block suspends onto a different thread.
                val entityBufferContext =
                    loadedEntities.fold(
                        activeTransactionCount.asContextElement(activeTransactionCount.get())
                            as kotlin.coroutines.CoroutineContext
                    ) { ctx, entity ->
                        ctx + entity._txEventBuffer.asContextElement(buffer.deferredEvents)
                    }
                withContext(entityBufferContext) { block(repo) }

                // COMMIT: write the buffer contents atomically to the backing store.
                // This hook is a no-op for VolatileRepository; JSON and SQL override it.
                try {
                    // Coalesce contradictory insert+delete intents for the same id before committing.
                    // Must run before captureDeferredUpdates so derived updates don't double-count.
                    repo.normalizeTransactionBuffer(buffer)
                    // Derive buffer.updates from deferred events: scalar mutations that were buffered
                    // (instead of published via the reactive subscription) need to be captured here
                    // so durable stores (SQL, JSON) know which rows to UPDATE.
                    repo.captureDeferredUpdates(buffer)
                    repo.commitTransactionBuffer(buffer)
                } catch (commitFailure: Throwable) {
                    handleTransactionFailure(repo, buffer, loadedEntities, commitFailure, onError)
                    return@withContext
                }

                // Release collapsed deferred events to subscribers now that the commit succeeded.
                buffer.collapseDeferredEvents().forEach { event ->
                    @Suppress("UNCHECKED_CAST")
                    (event.entity as? ReactiveEntityBase<K, R>)?.emitCollapsedEvent(event)
                }
            } catch (blockOrPreFlushFailure: Throwable) {
                // Block or pre-flush threw — roll back before surfacing the failure.
                handleTransactionFailure(repo, buffer, loadedEntities, blockOrPreFlushFailure, onError)
            }
        } finally {
            // Clear deferred event buffer from all entities regardless of outcome.
            loadedEntities.forEach { entity -> entity._txEventBuffer.remove() }
            repo.transactionDepth = 0
            repo.activeTransactionBuffer = null
            activeTransactionCount.set(activeTransactionCount.get() - 1)
            repo.unlockFlush()
            repo.rescheduleFlushIfPending()
        }
    }
}

/**
 * Rolls back in-memory state for all snapshotted entities, fires the repository's notify-only
 * error handler, discards deferred events, then either invokes the call-scoped [onError]
 * handler or rethrows as a typed exception.
 *
 * Rollback runs before the handler or exception is observed so the caller sees the pre-block state.
 * [ConflictInfo.entity] carries the values attempted inside the block, captured before rollback
 * by the commit machinery, not the restored pre-block values.
 */
@Suppress("UNCHECKED_CAST")
private fun <K : Comparable<K>, R : ReactiveEntity<K, R>> handleTransactionFailure(
    repo: PersistentRepositoryBase<K, R>,
    buffer: TransactionBuffer<K, R>,
    loadedEntities: List<ReactiveEntityBase<K, R>>,
    failure: Throwable,
    onError: (TransactionErrorContext<K, R>.() -> Unit)?
) {
    if (failure is kotlinx.coroutines.CancellationException) throw failure

    // Rollback first: restore every snapshotted entity to its pre-block values.
    // Events are suppressed during restore so subscribers do not observe intermediate states.
    loadedEntities.forEach { entity ->
        val snapshot = buffer.entitySnapshots[entity.id] ?: return@forEach
        entity.restoreSnapshot(snapshot)
    }

    // Undo inserts: entities added inside the block must be removed from in-memory state.
    buffer.inserts.forEach { entity ->
        entity.withEventsDisabled { repo.removeFromMemoryOnlyInternal(entity) }
    }

    // Undo deletes: entities removed inside the block must be re-added to in-memory state.
    buffer.deletes.forEach { pendingDelete ->
        pendingDelete.entity.withEventsDisabled { repo.addToMemoryOnlyInternal(pendingDelete.entity) }
    }

    // Notify the repository's observability handler with identity-only context (never field values).
    val entityIds = buffer.entitySnapshots.keys.map { it as Any }
    repo.notifyTransactionError(failure, entityIds)

    // Extract conflicts from the failure for the TransactionConflictException path.
    val conflicts: List<ConflictInfo<K, R>> =
        when (failure) {
            is TransactionConflictException ->
                failure.conflicts as List<ConflictInfo<K, R>>
            else -> emptyList()
        }

    // Discard the deferred event buffer — rolled-back mutations must not reach subscribers.
    buffer.deferredEvents.clear()

    if (onError != null) {
        // Call-scoped handler present: invoke it and suppress the exception.
        TransactionErrorContext(failure, conflicts).onError()
    } else {
        // No handler: propagate a typed exception.
        when {
            conflicts.isNotEmpty() ->
                throw TransactionConflictException(
                    "Transaction conflict on '${repo.repoName}': ${conflicts.size} version mismatch(es)",
                    conflicts,
                    failure
                )
            failure is LirpTransactionException -> throw failure
            else ->
                throw LirpTransactionException(
                    "Transaction failed on '${repo.repoName}': ${failure.message}",
                    failure
                )
        }
    }
}
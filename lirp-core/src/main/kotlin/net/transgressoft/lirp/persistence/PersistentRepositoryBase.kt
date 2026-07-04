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
import net.transgressoft.lirp.event.BatchChanged
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.LirpErrorContext
import net.transgressoft.lirp.event.LirpErrorHandler
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.LirpOperation
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.PropertyChanged
import net.transgressoft.lirp.event.ReactiveScope
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.MDC
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext

// MDC keys shared across the async flush and drain paths for log correlation
private const val MDC_KEY_REPOSITORY = "lirp.repository"
private const val MDC_KEY_OPERATION = "lirp.operation"

/**
 * Abstract foundation for persistent repositories providing entity mutation subscription management,
 * closeable lifecycle, dirty tracking, and a debounced write pipeline.
 *
 * Extends [VolatileRepository] and implements [PersistentRepository], sitting between the in-memory
 * base and concrete storage implementations (JSON, SQL, etc.).
 *
 * Every CRUD operation and entity mutation merges a [PendingCell] into a per-key
 * [ConcurrentHashMap] under a [ReentrantReadWriteLock]'s read lock, so multiple writers collapse
 * incrementally on the hot path instead of being deferred to a linear pass over a queue. The pending
 * map and the in-memory state are updated immediately (optimistic). A sliding-window debounce drains
 * the snapshot to the backing store after [debounceMillis] of inactivity; a [maxDelayMillis] cap
 * prevents starvation under continuous mutations by forcing a flush even when writes keep arriving.
 *
 * [clear] acquires the write lock so concurrent writers drain before the map is wiped, and sets a
 * `hadClear` flag that is consumed and forwarded to [writePending] on the next flush.
 *
 * Subclasses implement [writePending] to execute the grouped (inserts/updates/deletes/hadClear)
 * payload against the backing store. On write failure, the snapshot is restored to the live map via
 * [mergeOlder], reconciled with writes that arrived during the failed I/O.
 *
 * Loading behaviour is controlled by the [loadOnInit] parameter. When `true` (default), the
 * subclass is expected to call [load] at the end of its own init block so that [loadFromStore]
 * executes after all subclass fields are initialised. When `false`, callers must invoke [load]
 * explicitly before using any mutating operations.
 *
 * ### Subclassing contract
 *
 * Custom subclasses **must** implement:
 * - [loadFromStore] — reads entities from the backing store and returns them as a map.
 * - [writePending] — persists the grouped pending payload to the backing store.
 *
 * Subclasses that set `loadOnInit = true` (the default) **must** call [load] at the end of
 * their own `init` block, after all subclass-specific fields are initialised. This ensures
 * [loadFromStore] can safely access subclass state (e.g. database connections, file handles).
 *
 * Lifecycle guarantees:
 * - All mutating operations ([add], [remove], [removeAll], [clear]) throw [IllegalStateException]
 *   after the repository is closed.
 * - Mutating operations also throw [IllegalStateException] if called before [load] on a repository
 *   constructed with `loadOnInit = false`.
 * - [close] is idempotent: subsequent calls after the first are safe no-ops.
 * - [close] cancels the pending debounce timer, performs a synchronous final flush, then cancels
 *   all entity mutation subscriptions.
 * - Entity mutation subscriptions are automatically cancelled on removal or close.
 *
 * @param K The type of entity identifier, must be [Comparable]
 * @param R The type of reactive entity stored in this repository
 * @param debounceMillis Milliseconds of inactivity before pending ops are flushed (sliding window)
 * @param maxDelayMillis Maximum milliseconds from first enqueue to forced flush (starvation guard)
 * @param loadOnInit When `true`, subclasses call [load] in their own init block to eagerly load
 *        entities from the backing store. When `false`, [load] must be called explicitly.
 */
abstract class PersistentRepositoryBase<K : Comparable<K>, R : ReactiveEntity<K, R>>
    internal constructor(
        context: LirpContext,
        name: String,
        initialEntities: MutableMap<K, R>,
        private val debounceMillis: Long = 100L,
        private val maxDelayMillis: Long = 1000L,
        protected val loadOnInit: Boolean = true,
        private val onError: LirpErrorHandler? = null
    ) : VolatileRepository<K, R>(context, name, initialEntities, onError), PersistentRepository<K, R> {

        // Stored to populate MDC keys at flush launch time and for transaction error context.
        private val repositoryName: String = name

        /** The name of this repository, used in logging and error-context payloads. */
        internal val repoName: String get() = repositoryName

        companion object {
            private const val CLOSED_MESSAGE = "PersistentRepositoryBase is closed"
            private const val NOT_LOADED_MESSAGE = "Repository has not been loaded yet. Call load() first."
        }

        /**
         * Public constructor for external subclasses (e.g. in separate modules) that do not
         * have direct access to [LirpContext].
         *
         * Uses [LirpContext.default] for registration and a [java.util.concurrent.ConcurrentHashMap]
         * for in-memory storage. Debounce defaults: 100 ms sliding window, 1000 ms max delay cap.
         *
         * @param name A descriptive name for this repository, used in logging and identification.
         * @param loadOnInit When `true` (default), the subclass is expected to call [load] in its
         *   own init block to eagerly load from the backing store. When `false`, [load] must be
         *   called explicitly by the caller.
         * @param onError Optional handler invoked after logging when an async flush failure escapes
         *   the scheduled coroutine. The framework logs the failure first; the handler observes but
         *   does not alter control flow. When `null`, behavior is log-only.
         */
        constructor(name: String, loadOnInit: Boolean = true, onError: LirpErrorHandler? = null) :
            this(LirpContext.default, name, ConcurrentHashMap(), loadOnInit = loadOnInit, onError = onError)

        // Preserves the binary signature `<init>(String, boolean)` for subclasses compiled against
        // the pre-onError API. The defaulted onError parameter on the constructor above changes the
        // generated JVM signature, so this explicit overload keeps older binaries linking.
        constructor(name: String, loadOnInit: Boolean) : this(name, loadOnInit, null)

        private val log = KotlinLogging.logger(javaClass.name)

        init {
            // Activate CONFLICT events for versioned subclasses. VolatileRepository activates
            // CREATE/DELETE and RegistryBase activates UPDATE; CONFLICT is added here so the
            // optimistic-locking recovery path (handleOptimisticLockConflict → emitAsync) reaches
            // subscribers. Unversioned repositories never emit CONFLICT and are unaffected.
            activateEvents(CrudEvent.Type.CONFLICT)
        }

        @Volatile
        private var loaded: Boolean = false

        @Volatile
        private var loading: Boolean = false

        /**
         * Whether entities from the backing store have been loaded into memory.
         *
         * Returns `true` after a successful [load] call or after eager construction with
         * `loadOnInit = true`. Returns `false` before [load] is called on a deferred repository
         * or while loading is in progress.
         */
        val isLoaded: Boolean get() = loaded

        /**
         * Loads entities from the backing store into memory.
         *
         * Delegates to [loadFromStore] to obtain the entity map, then inserts each entity via
         * [addToMemoryOnly] so that no write-back is triggered for data already persisted.
         * CREATE and UPDATE events are suppressed during the load so subscribers do not observe
         * bulk-load operations as individual mutations.
         *
         * A separate [loading] flag prevents concurrent callers from entering [load] while a
         * load is in progress. The [loaded] flag is only set to `true` after [loadFromStore]
         * completes successfully. If [loadFromStore] throws, [loading] is reset so that a
         * subsequent retry is possible.
         *
         * The entire load is performed under [flushLock] to prevent a concurrent [close] from
         * clearing subscriptions while entities are being added via [addToMemoryOnly].
         *
         * @throws IllegalStateException if called after a successful load, while a load is
         *         already in progress, or after the repository has been closed.
         */
        override fun load() {
            flushLock.withLock {
                checkNotClosed()
                check(!loaded) { "Repository has already been loaded" }
                check(!loading) { "Repository is currently being loaded" }
                loading = true
                disableEvents(CrudEvent.Type.CREATE, CrudEvent.Type.UPDATE)
                try {
                    val entities = loadFromStore()
                    entities.values.forEach { addToMemoryOnly(it) }
                    loaded = true
                } finally {
                    loading = false
                    activateEvents(CrudEvent.Type.CREATE, CrudEvent.Type.UPDATE)
                }
            }
        }

        /**
         * Loads entities from the backing store and returns them as a map of ID to entity.
         *
         * Called by [load] as part of the template method. Subclasses implement this method to
         * read from their specific storage medium (JSON file, SQL database, etc.) and return
         * the persisted entity map. The returned entities are inserted via [addToMemoryOnly]
         * without triggering write-back or events.
         *
         * @return a map of entity ID to entity from the backing store, or an empty map if the
         *         store contains no data.
         */
        protected abstract fun loadFromStore(): Map<K, R>

        private fun checkLoaded() = check(loaded) { NOT_LOADED_MESSAGE }

        private val subscriptionsMap: MutableMap<K, LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>> = ConcurrentHashMap()

        // Per-id lifecycle lock: serialises `add(entity)` against `remove(entity)` for the SAME
        // key. Without it a concurrent remover can claim and cancel the tentative subscription
        // installed by [subscribeEntity] between the moment `add` calls it and the moment `add`
        // delegates to `super.add()`; the remover's `super.remove()` then observes the entity as
        // absent and returns false, while `add`'s `super.add()` returns true — leaving the entity
        // visible with no live subscription so subsequent mutations stop flowing to `enqueueUpdate`.
        // The lock map grows proportionally to the repo's id cardinality (locks are retained for
        // future add/remove cycles on the same id); this matches the in-memory entity bound so it
        // does not represent unbounded growth beyond what the repository itself stores.
        private val lifecycleLocks = ConcurrentHashMap<K, ReentrantLock>()

        private inline fun <T> withLifecycleLock(id: K, block: () -> T): T =
            lifecycleLocks.computeIfAbsent(id) { ReentrantLock() }.withLock(block)

        val dirty = AtomicBoolean(false)

        @Volatile
        protected var closed = false
            private set

        // Per-key collapsed pending write state. Mutations flow in through `mergeWriterSide`
        // under the read lock; `clear()` wipes the map under the write lock.
        private val pendingCells = ConcurrentHashMap<K, PendingCell<K, R>>()

        // Serialises `clear()` against per-key writers. Writers take the read lock (shared) so
        // many concurrent mutators collapse cells in parallel; `clear()` takes the write lock so
        // it waits for in-flight writes to drain before wiping the map atomically.
        private val pendingLock = ReentrantReadWriteLock()

        // Set by `clear()` under the write lock; consumed (and reset) by `flush()` along with the
        // snapshot drain so that the cleared intent is forwarded once to `writePending`.
        private val hadClear = AtomicBoolean(false)

        // Monotonic counter incremented by `clear()` under the write lock. `flush()` captures the
        // value alongside the snapshot; `reenqueueAfterFailure()` drops the snapshot when the
        // captured epoch is stale (a `clear()` won the race against the failed I/O), so a failed
        // flush cannot recreate rows that a newer `clear()` already removed.
        private val clearEpoch = AtomicLong(0L)

        // Serializes flush() calls: prevents concurrent drains from the pending map and ensures
        // close() waits for any in-flight flush to complete before draining itself. Protected so
        // both subclasses and the transaction orchestration free function in the same module can
        // serialize writes against the same lock (e.g. JsonFileRepository's jsonFile setter,
        // Transactions.kt's pre-flush + block + commit sequence).
        protected val flushLock = ReentrantLock()

        /** Acquires [flushLock]. For use by the transaction orchestration layer in the same module. */
        internal fun lockFlush() = flushLock.lock()

        /**
         * Attempts to acquire [flushLock], waiting at most [timeout]. Returns `true` when the lock
         * was taken and `false` when the wait elapsed first.
         *
         * The transaction orchestration layer uses the bounded variant instead of [lockFlush] so a
         * flush that never releases the lock (a stuck backing-store write or a lock leaked by an
         * earlier transaction) surfaces as a fast, diagnosable failure rather than an indefinite hang.
         *
         * @throws InterruptedException if the current thread is interrupted while waiting.
         */
        internal fun tryLockFlush(timeout: Duration): Boolean =
            flushLock.tryLock(timeout.inWholeNanoseconds, TimeUnit.NANOSECONDS)

        /** Releases [flushLock]. Symmetric to [lockFlush]. */
        internal fun unlockFlush() = flushLock.unlock()

        /** Returns `true` when the calling thread currently holds [flushLock]. */
        internal fun isFlushLockHeldByCurrentThread(): Boolean = flushLock.isHeldByCurrentThread

        /**
         * Nesting depth of the active transaction on this repository.
         *
         * Zero means no transaction is active. Values greater than zero indicate nested transaction
         * calls on the same repo, which are flattened — the outermost block owns commit/rollback
         * and inner calls simply increment the counter without starting a new buffer.
         */
        @Volatile
        internal var transactionDepth: Int = 0

        /**
         * The [TransactionBuffer] currently enrolled for this repository, non-null only while
         * [transactionDepth] is greater than zero.
         *
         * Non-null signals [subscribeEntity]'s enqueue hook to route ops into this buffer instead
         * of the normal debounce pipeline, filtering to only the mutations that belong to this repo.
         */
        @Volatile
        internal var activeTransactionBuffer: TransactionBuffer<K, R>? = null

        @Volatile
        private var debounceJob: Job? = null

        // Fires once per mutation window after maxDelayMillis regardless of ongoing mutations
        @Volatile
        private var maxDelayJob: Job? = null

        // Nanosecond timestamp of the first enqueue in the current mutation window.
        // Set on the first enqueue of a window; reset to 0 on flush so the next window
        // gets a fresh deadline. The max-delay cap is derived from this value, not from
        // when maxDelayJob was last armed, so a new enqueue after an idle flush cannot
        // push the cap's deadline forward.
        private val startNanos = AtomicLong(0L)

        /**
         * Persists the grouped pending payload to the backing store.
         *
         * Called by [flush] after draining the per-key snapshot. The four parameters together form
         * the complete set of writes that accumulated during the debounce window for distinct ids:
         *
         * - [inserts] entities whose net intent is `INSERT`.
         * - [updates] carrier pairs (entity, expectedVersion) whose net intent is `UPDATE`.
         * - [deletes] (id, expectedVersion) pairs whose net intent is `DELETE`.
         * - [hadClear] `true` when a `clear()` happened during the debounce window. The store
         *   should wipe all rows first, then apply [inserts], [updates], [deletes] (in that order).
         *
         * Ordering guarantee — INSERT → UPDATE → DELETE — is provided by the caller because each
         * group references a distinct set of ids after per-key collapse, but the SQL store still
         * relies on the order to keep FK-consistent writes simple to reason about.
         *
         * On failure, the caller restores the snapshot via [mergeOlder] and reschedules a flush.
         */
        protected abstract fun writePending(
            inserts: List<R>,
            updates: List<PendingUpdate<K, R>>,
            deletes: List<Pair<K, Long?>>,
            hadClear: Boolean
        )

        /**
         * Drains the per-key pending cell map and dispatches the grouped payload to [writePending].
         * On failure, restores the snapshot via [mergeOlder] so the next flush retries with a
         * reconciled view of writes that arrived during the failed I/O.
         *
         * **Caller must hold [flushLock].** This method does not acquire [flushLock] itself — it is
         * extracted from [flush] so that the transaction commit path can call it while already holding
         * the lock, avoiding re-entrant lock acquisition which would deadlock on a non-reentrant lock.
         *
         * Subclasses are responsible for resetting [dirty] to `false` within [writePending] once the
         * write is confirmed (or asynchronously, if the write is fire-and-forget).
         */
        internal fun drainPendingNoLock() {
            // Capture the snapshot, hadClear flag, AND the clearEpoch under the write lock.
            // The captured epoch is later compared in reenqueueAfterFailure() to determine
            // whether a clear() ran during the I/O and so the snapshot must be dropped rather
            // than restored.
            val snapshot: Map<K, PendingCell<K, R>>
            val clearFlag: Boolean
            val capturedEpoch: Long
            pendingLock.write {
                snapshot = pendingCells.toMap()
                pendingCells.clear()
                clearFlag = hadClear.getAndSet(false)
                capturedEpoch = clearEpoch.get()
            }
            // Reset the window origin so the next enqueue after this flush starts a fresh
            // max-delay deadline rather than inheriting the stale timestamp.
            startNanos.set(0L)
            if (snapshot.isEmpty() && !clearFlag) return
            val inserts = snapshot.values.filterIsInstance<PendingCell.Insert<K, R>>().map { it.entity }
            val updates =
                snapshot.values.filterIsInstance<PendingCell.Update<K, R>>()
                    .map { PendingUpdate(it.entity, it.expectedVersion) }
            val deletes =
                snapshot.entries.mapNotNull { (id, cell) ->
                    (cell as? PendingCell.Delete<K, R>)?.let { id to it.expectedVersion }
                }
            try {
                writePending(inserts, updates, deletes, clearFlag)
            } catch (e: OptimisticLockException) {
                routeOptimisticLockConflict(e)
            } catch (e: Exception) {
                reenqueueAfterFailure(snapshot, clearFlag, capturedEpoch)
                throw e
            }
        }

        /**
         * Drains the per-key pending cell map under the write lock, then dispatches the grouped
         * payload to [writePending]. On failure, restores the snapshot via [mergeOlder] so the
         * next flush retries with a reconciled view of writes that arrived during the failed I/O.
         *
         * This method is called synchronously by [close] and asynchronously by the debounce job.
         * [flushLock] serialises concurrent calls so close() always waits for any in-flight
         * debounce flush to complete before draining itself. Subclasses are responsible for
         * resetting [dirty] to `false` within [writePending] once the write is confirmed (or
         * asynchronously, if the write is fire-and-forget).
         */
        protected fun flush() {
            flushLock.withLock {
                drainPendingNoLock()
            }
        }

        /**
         * Cancels the sliding-window debounce job and the max-delay cap job.
         *
         * The transaction commit path calls this before acquiring [flushLock] to ensure the debounce
         * coroutine does not attempt to acquire [flushLock] concurrently, which would otherwise
         * stall the transaction behind an in-flight flush.
         */
        internal fun cancelDebounce() {
            debounceJob?.cancel()
            maxDelayJob?.cancel()
        }

        /**
         * Fires the repository's [LirpErrorHandler] with [LirpOperation.TRANSACTION] and the given
         * entity identifiers. This is a notify-only call — the handler cannot alter control flow.
         *
         * Only entity identity is included in the error context; field values are never exposed here.
         */
        internal fun notifyTransactionError(throwable: Throwable, entityIds: List<Any>) {
            try {
                onError?.invoke(throwable, LirpErrorContext(LirpOperation.TRANSACTION, entityIds, repositoryName))
            } catch (handlerEx: Throwable) {
                log.error(handlerEx) { "LirpErrorHandler threw inside transaction error notify; exception swallowed" }
            }
        }

        /**
         * Re-arms the debounce flush if there are pending cells after a transaction completes.
         *
         * Called at the end of a transaction (commit or rollback) so that any ops enqueued during
         * the block that were not part of the buffer are flushed through the normal debounce pipeline.
         */
        internal fun rescheduleFlushIfPending() {
            if (!closed && pendingCells.isNotEmpty()) {
                scheduleFlush()
            }
        }

        /**
         * Called by the transaction commit path after [drainPendingNoLock] succeeds, with the ops
         * captured during the block in place of the normal debounce payload.
         *
         * The default implementation is intentionally a no-op — [VolatileRepository] mutates memory
         * in place during the block, so there is nothing durable to commit; rollback and event-deferral
         * are handled by the shared base machinery regardless of store type. Durable stores (SQL,
         * JSON) override this hook to write the buffer contents in a single atomic operation.
         *
         * **Threading contract:** invoked while [flushLock] is held. Must not call [flush] or
         * otherwise re-acquire [flushLock].
         *
         * @param buffer the [TransactionBuffer] carrying the captured inserts, updates, deletes, and
         *   entity snapshots for the completed block
         */
        open fun commitTransactionBuffer(buffer: TransactionBuffer<K, R>) {
            // No-op: memory is already updated in place; Volatile relies on this default.
        }

        /**
         * Derives [TransactionBuffer.updates] from [TransactionBuffer.deferredEvents] by collecting
         * distinct entities that were mutated inside the block but are neither inserts nor deletes.
         *
         * Called immediately before [commitTransactionBuffer] so that the update list is populated
         * for durable-store implementations (SQL, JSON) without requiring the reactive subscription
         * path to fire during a transaction block.
         */
        internal fun captureDeferredUpdates(buffer: TransactionBuffer<K, R>) {
            val insertIds = buffer.inserts.map { it.id }.toSet()
            val deleteIds = buffer.deletes.map { it.id }.toSet()
            val alreadyCaptured = buffer.updates.map { it.entity.id }.toSet()
            buffer.deferredEvents
                .mapNotNull { event ->
                    event.entity.takeIf { entity ->
                        entity.id !in insertIds && entity.id !in deleteIds && entity.id !in alreadyCaptured
                    }
                }
                .distinctBy { it.id }
                .forEach { entity ->
                    buffer.updates.add(PendingUpdate(entity, extractVersion(entity)))
                }
        }

        /**
         * Coalesces contradictory insert and delete intents for the same entity id inside [buffer].
         *
         * When the same id appears in both [TransactionBuffer.inserts] and [TransactionBuffer.deletes]:
         * - **Pre-existing id** (id is in [TransactionBuffer.entitySnapshots], meaning the row existed
         *   before the block): the net effect is an UPDATE to the re-added value. The entity is moved
         *   from inserts into [TransactionBuffer.updates] (if not already captured there), and the id
         *   is removed from both lists.
         * - **Transient id** (added and removed within the block, never persisted): the net effect is a
         *   no-op. The id is removed from both lists without writing anything to the store.
         *
         * Must be called before [captureDeferredUpdates] so derived updates do not double-count ids
         * already promoted here.
         */
        internal fun normalizeTransactionBuffer(buffer: TransactionBuffer<K, R>) {
            val insertIds = buffer.inserts.map { it.id }.toSet()
            val deleteIds = buffer.deletes.map { it.id }.toSet()
            val overlapping = insertIds intersect deleteIds
            if (overlapping.isEmpty()) return

            val preExistingIds = buffer.entitySnapshots.keys.toSet()

            for (id in overlapping) {
                val insertedEntity = buffer.inserts.firstOrNull { it.id == id } ?: continue
                buffer.inserts.removeAll { it.id == id }
                buffer.deletes.removeAll { it.id == id }

                if (id in preExistingIds) {
                    // Row existed before the block: promote the re-added entity to an UPDATE.
                    val alreadyCaptured = buffer.updates.any { it.entity.id == id }
                    if (!alreadyCaptured) {
                        buffer.updates.add(PendingUpdate(insertedEntity, extractVersion(insertedEntity)))
                    }
                }
                // Transient id (not pre-existing): remove from both lists — net no-op; nothing to write.
            }
        }

        // optimistic-lock failures follow the Conflict + auto-reload path and DO NOT
        // re-enqueue. The subclass recovery hook performs the auto-reload and emits the
        // StandardCrudEvent.Conflict event. Cells that arrived during the failed write are kept
        // in the map (they were never drained here) so the next flush cycle handles them
        // normally. Do NOT rethrow: the conflict is an internal signal, fully handled via the
        // Conflict event.
        private fun routeOptimisticLockConflict(e: OptimisticLockException) {
            try {
                handleOptimisticLockConflict(e)
            } catch (hookFailure: Exception) {
                log.error(hookFailure) { "handleOptimisticLockConflict threw; conflict may not have been fully recovered" }
            }
            if (!closed && pendingCells.isNotEmpty())
                scheduleFlush()
        }

        // Restore the failed snapshot back into the live map via per-key `mergeOlder`, which
        // reconciles each pre-failure cell with writes that arrived during the failed I/O. The
        // read lock is sufficient — `compute()` already serialises per-bin and `mergeOlder` always
        // returns a non-null cell, so no bin is ever removed during recovery. If the failing flush
        // had observed a clear, restore the flag so the next flush still propagates the cleared
        // intent to the store.
        //
        // If `clearEpoch` has advanced since [capturedEpoch], a `clear()` won the race against
        // the failed I/O — the snapshot is stale (its rows have been logically removed) and must
        // be dropped. Restoring it would let the next flush recreate rows that the clear just
        // removed. The newer clear()'s own hadClear=true survives in the map; we do not restore
        // the pre-clear flag in this branch.
        private fun reenqueueAfterFailure(
            snapshot: Map<K, PendingCell<K, R>>,
            clearFlag: Boolean,
            capturedEpoch: Long
        ) {
            val supersededByClear = clearEpoch.get() != capturedEpoch
            if (!supersededByClear) {
                pendingLock.read {
                    snapshot.forEach { (id, oldCell) ->
                        pendingCells.compute(id) { _, current -> mergeOlder(oldCell, current) }
                    }
                }
                if (clearFlag) hadClear.set(true)
            }
            if (!closed)
                scheduleFlush()
        }

        /**
         * Test-only accessor returning the current number of distinct keys with pending writes.
         *
         * Exposed as `internal` for the `lirp-core` test source set to verify flush routing
         * invariants without reflection. Not part of the public API — callers outside tests
         * should rely on [dirty] for coarse-grained write-pending signalling.
         */
        internal fun pendingOpsCount(): Int = pendingCells.size

        // Two enqueue surfaces:
        //  * The `enqueue*Locked` helpers are called by add/remove/removeAll which already hold
        //    the read lock around the full in-memory + cell mutation (see those methods' KDoc).
        //  * The `enqueueUpdate` helper (and the legacy public enqueue paths) acquire the read
        //    lock themselves — they are invoked by the entity-mutation subscription handler that
        //    runs outside the add/remove/removeAll lifecycle.
        //
        // Close coordination: callers re-check `closed` AFTER acquiring the read lock and bail
        // without touching pendingCells if a concurrent close() has fenced. close() sets the
        // volatile `closed` flag under the write lock so that this re-check is observable.
        private fun enqueueInsertLocked(entity: R) {
            if (closed) return
            val txBuffer = activeTransactionBuffer
            if (txBuffer != null) {
                txBuffer.inserts.add(entity)
                return
            }
            pendingCells.compute(entity.id) { _, cur -> mergeWriterSide(cur, PendingCell.Insert(entity)) }
            dirty.set(true)
            scheduleFlush()
        }

        private fun enqueueDeleteLocked(id: K, entity: R, expectedVersion: Long?) {
            if (closed) return
            val txBuffer = activeTransactionBuffer
            if (txBuffer != null) {
                txBuffer.deletes.add(PendingDelete(id, entity, expectedVersion))
                return
            }
            pendingCells.compute(id) { _, cur -> mergeWriterSide(cur, PendingCell.Delete(expectedVersion)) }
            dirty.set(true)
            scheduleFlush()
        }

        private fun enqueueUpdate(entity: R, expectedVersion: Long?) {
            pendingLock.read {
                if (closed) return@read
                pendingCells.compute(entity.id) { _, cur ->
                    mergeWriterSide(cur, PendingCell.Update(entity, expectedVersion))
                }
                dirty.set(true)
                scheduleFlush()
            }
        }

        // Per-launch CoroutineExceptionHandler that routes an uncaught flush escape to onError.
        // Installed on each individual flush launch so the handler fires exactly once per failed
        // launch (flush()'s own catch re-enqueues and rethrows, and the rethrow escapes the
        // launch to this handler). The synchronous close()→flush() path surfaces via the
        // preserved rethrow — onError is NOT invoked there, avoiding double-firing.
        private fun flushFailureHandler() =
            onError?.let { handler ->
                CoroutineExceptionHandler { _, throwable ->
                    try {
                        handler.invoke(throwable, LirpErrorContext(LirpOperation.FLUSH, emptyList<Any>(), repositoryName))
                    } catch (handlerEx: Throwable) {
                        log.error(handlerEx) { "LirpErrorHandler threw inside CoroutineExceptionHandler; exception swallowed" }
                    }
                }
            }

        private fun scheduleFlush() {
            // Record the first-enqueue timestamp for the current mutation window.
            // compareAndSet(0, now) is a no-op on every subsequent enqueue in the same window,
            // ensuring the max-delay deadline is always relative to the FIRST enqueue, not the
            // most-recent one. startNanos is reset to 0 in flush() so the next window gets a
            // fresh origin. The unsynchronized read is intentional: two concurrent first-enqueues
            // may both observe 0 and both call compareAndSet; only one wins, and both outcomes
            // produce a valid first-enqueue time within a few nanoseconds of each other.
            val now = System.nanoTime()
            startNanos.compareAndSet(0L, now)

            // Arm the max-delay cap only when the current window has no active cap job.
            // Compute the remaining window relative to startNanos so that a new enqueue arriving
            // after a debounce-triggered flush (which nulls maxDelayJob) does not re-arm a fresh
            // full maxDelayMillis — it schedules only the time remaining until the original
            // deadline. The unsynchronized null-check is harmless: two concurrent calls may both
            // launch a max-delay job. The second flush drains an already-empty map and returns.
            if (maxDelayJob == null || maxDelayJob!!.isCompleted || maxDelayJob!!.isCancelled) {
                val elapsedMillis = (System.nanoTime() - startNanos.get()) / 1_000_000L
                val remainingMillis = (maxDelayMillis - elapsedMillis).coerceAtLeast(0L)
                MDC.put(MDC_KEY_REPOSITORY, repositoryName)
                MDC.put(MDC_KEY_OPERATION, LirpOperation.FLUSH.name)
                try {
                    val handler = flushFailureHandler()
                    maxDelayJob =
                        if (handler != null) {
                            ReactiveScope.ioScope.launch(MDCContext() + handler) {
                                delay(remainingMillis.milliseconds)
                                maxDelayJob = null
                                log.trace { "Async max-delay flush triggered for $repositoryName" }
                                flush()
                            }
                        } else {
                            ReactiveScope.ioScope.launch(MDCContext()) {
                                delay(remainingMillis.milliseconds)
                                maxDelayJob = null
                                log.trace { "Async max-delay flush triggered for $repositoryName" }
                                flush()
                            }
                        }
                } finally {
                    MDC.remove(MDC_KEY_REPOSITORY)
                    MDC.remove(MDC_KEY_OPERATION)
                }
            }
            // Sliding-window debounce: each new enqueue resets the idle timer.
            debounceJob?.cancel()
            MDC.put(MDC_KEY_REPOSITORY, repositoryName)
            MDC.put(MDC_KEY_OPERATION, LirpOperation.FLUSH.name)
            try {
                val handler = flushFailureHandler()
                debounceJob =
                    if (handler != null) {
                        ReactiveScope.ioScope.launch(MDCContext() + handler) {
                            delay(debounceMillis.milliseconds)
                            maxDelayJob?.cancel()
                            maxDelayJob = null
                            log.trace { "Async debounce flush triggered for $repositoryName" }
                            flush()
                        }
                    } else {
                        ReactiveScope.ioScope.launch(MDCContext()) {
                            delay(debounceMillis.milliseconds)
                            maxDelayJob?.cancel()
                            maxDelayJob = null
                            log.trace { "Async debounce flush triggered for $repositoryName" }
                            flush()
                        }
                    }
            } finally {
                MDC.remove(MDC_KEY_REPOSITORY)
                MDC.remove(MDC_KEY_OPERATION)
            }
        }

        /**
         * Adds [entity] to in-memory storage and subscribes to mutation events without enqueuing
         * any pending write.
         *
         * Used by subclasses during initialization to load entities from an external store
         * (e.g. DB or JSON file) without triggering a write-back for data already persisted.
         */
        internal fun addToMemoryOnlyInternal(entity: R) = addToMemoryOnly(entity)

        protected fun addToMemoryOnly(entity: R) {
            super.add(entity)
            subscribeEntity(entity)
        }

        /**
         * Removes [entity] from in-memory storage and cancels its mutation subscription without
         * enqueuing any pending write.
         *
         * Symmetric to [addToMemoryOnly]. Used by subclasses when the canonical backing-store
         * state indicates the entity has already been removed by another writer (for example,
         * during conflict recovery in `SqlRepository`). Avoids a spurious DELETE re-enqueue for
         * a row that is already gone.
         *
         * @return `true` if the entity was present and removed, `false` otherwise.
         */
        internal fun removeFromMemoryOnlyInternal(entity: R): Boolean = removeFromMemoryOnly(entity)

        protected fun removeFromMemoryOnly(entity: R): Boolean {
            val removed = super.remove(entity)
            if (removed) {
                subscriptionsMap.remove(entity.id)?.cancel()
            }
            return removed
        }

        /**
         * Extracts the optimistic-lock version from [entity] for use in `@Version`-aware update
         * and delete writes.
         *
         * The default implementation returns `null`, which causes every enqueued update and delete
         * to carry `expectedVersion = null` — the pre-versioning behaviour (last-write-wins
         * UPDATE/DELETE with no version check).
         *
         * Subclasses that support `@Version` (notably the SQL-backed repository) override this hook
         * to read the entity's `@Version` property via a KSP-generated accessor. Returning a non-null
         * value causes the corresponding SQL statement to include `AND version = ?` in its WHERE clause.
         *
         * @param entity The entity whose version is being captured.
         * @return The current `@Version` value, or `null` when the entity type is unversioned.
         */
        protected open fun extractVersion(entity: R): Long? = null

        /**
         * Recovery hook invoked by [flush] when [writePending] throws an [OptimisticLockException].
         *
         * The default implementation is a no-op — a base [PersistentRepositoryBase] subclass that
         * does not implement `@Version` semantics cannot receive this exception (see [extractVersion])
         * and therefore does not need to recover. SQL-backed repositories override this to perform
         * the auto-reload and emit the
         * [net.transgressoft.lirp.event.StandardCrudEvent.Conflict] event.
         *
         * Implementations MUST be idempotent and MUST NOT throw. Rethrowing here would cause the
         * original exception to escape `flush()` and reach the caller ([close] or the debounce
         * scheduler), which is almost always undesirable.
         *
         * **Threading contract:** Invoked inside [flush] while holding [flushLock]. Implementations
         * MUST NOT call [flush] recursively or otherwise acquire [flushLock] — doing so deadlocks.
         * Implementations SHOULD emit user-facing events (e.g. [net.transgressoft.lirp.event.StandardCrudEvent.Conflict])
         * asynchronously via the repository's publisher to avoid running user code on the flush thread.
         *
         * @param e The optimistic-lock failure carrying entity id, expected version, and actual version.
         */
        protected open fun handleOptimisticLockConflict(e: OptimisticLockException) {
            // Default: no-op. Override in versioned subclasses.
        }

        /**
         * Subscribes to mutation events from [entity] and registers the subscription for lifecycle management.
         *
         * The mutation handler body executes inside [pendingLock]'s read lock and re-checks [closed]
         * after lock acquisition before touching the write pipeline. This mirrors the close-fence
         * pattern used by [enqueueUpdate] / [add] / [remove] / [removeAll] so that once [close] has
         * set `closed = true` under the write lock, every in-flight handler observes the new value
         * under the read lock and bails before calling [enqueueUpdate] or [onEntityMutated]. Without
         * the fence the handler could call into the now-closed publisher's `emitAsync`, tripping the
         * `check(!isClosed)` invariant detector inside `FlowEventPublisher.emitAsync`.
         *
         * The fence preserves the underlying invariant detector — it does not silence it. The
         * detector still fires on any genuine misuse (a closed publisher emitted to from a code
         * path that bypasses the fence).
         *
         * `Subscription.cancel()` is idempotent — it delegates to `Job.cancel()`, which is a no-op
         * on an already-cancelled or completed job per the kotlinx.coroutines contract. Callers may
         * safely cancel the same subscription twice (relevant to the lifecycle inversions in
         * [add] and [remove]).
         *
         * For `@Version`-aware subclasses, the `expectedVersion` carried into the merged cell is
         * pinned from the immutable scalar [PropertyChanged.versionAtMutation] or
         * [BatchChanged.versionAtMutation] captured at assignment time. Falling back to
         * [extractVersion] on the live entity is safe because version bumps run inside
         * the debounced write pipeline, not at mutation time, so the live entity still holds
         * the pre-flush value when the subscriber drains.
         */
        protected fun subscribeEntity(entity: R) {
            val subscription =
                entity.subscribeAsync { mutationEvent ->
                    pendingLock.read {
                        if (closed) return@read

                        // When a transaction is active for this repo, route the mutation op into the
                        // buffer rather than the normal debounce pipeline. The buffer is filtered to
                        // this repo only — mutations to entities from a different repo fall through
                        // to enqueueUpdate on their own repo's subscribeEntity handler.
                        val txBuffer = activeTransactionBuffer
                        if (txBuffer != null) {
                            txBuffer.updates.add(PendingUpdate(mutationEvent.entity, versionAtMutation(mutationEvent)))
                            reindexMutation(mutationEvent)
                            onEntityMutated(mutationEvent)
                            return@read
                        }

                        enqueueUpdate(mutationEvent.entity, versionAtMutation(mutationEvent))
                        // Reindex before the subclass hook so a throwing onEntityMutated override
                        // cannot leave findByIndex / range queries returning stale results.
                        reindexMutation(mutationEvent)
                        onEntityMutated(mutationEvent)
                    }
                }
            subscriptionsMap[entity.id] = subscription
        }

        /**
         * Resolves the entity version to pin into the pending update cell for `@Version`-aware subclasses.
         *
         * [PropertyChanged] and [BatchChanged] carry an immutable `versionAtMutation` scalar frozen at
         * assignment time, safe to read under deferred coroutine dispatch. Falling back to [extractVersion]
         * on the live entity is safe because version bumps run inside the debounced write pipeline, not at
         * mutation time, so the live entity still holds the pre-flush value when the subscriber drains.
         */
        private fun versionAtMutation(mutationEvent: MutationEvent<K, R>): Long? =
            when (mutationEvent) {
                is PropertyChanged<*, *, *> ->
                    @Suppress("UNCHECKED_CAST")
                    (mutationEvent as PropertyChanged<K, R, *>).versionAtMutation ?: extractVersion(mutationEvent.entity)
                is BatchChanged<*, *> ->
                    @Suppress("UNCHECKED_CAST")
                    (mutationEvent as BatchChanged<K, R>).versionAtMutation ?: extractVersion(mutationEvent.entity)
                else -> extractVersion(mutationEvent.entity)
            }

        /**
         * Applies the mutation's index-key delta to the secondary indexes.
         *
         * Each old-key removal is scoped to the property that actually changed: a single batch can touch
         * several `@Indexed` properties of different runtime types, and applying one property's key to a
         * differently-typed sorted index would throw [ClassCastException] in the comparator. [PropertyChanged]
         * carries one (property, key) pair; [BatchChanged] is fanned out per indexed field change.
         * [AggregateMutationEvent] carries no index keys and is skipped.
         */
        private fun reindexMutation(mutationEvent: MutationEvent<K, R>) {
            when (mutationEvent) {
                is PropertyChanged<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val propertyChanged = mutationEvent as PropertyChanged<K, R, *>
                    reindexEntity(
                        propertyChanged.entity,
                        propertyChanged.oldIndexKey,
                        propertyChanged.newIndexKey,
                        propertyChanged.property.name
                    )
                }
                is BatchChanged<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val batchChanged = mutationEvent as BatchChanged<K, R>
                    for (change in batchChanged.changes) {
                        if (isPropertyIndexed(change.property)) {
                            reindexEntity(batchChanged.entity, change.oldValue, change.newValue, change.property.name)
                        }
                    }
                }
                else -> { /* AggregateMutationEvent — no index keys to apply */ }
            }
        }

        /**
         * Called after an entity mutation is detected and an update has been enqueued.
         *
         * Subclasses may override this method to react to entity-level mutations with additional
         * logic, such as emitting repository-level [CrudEvent] UPDATE events. The default
         * implementation is a no-op.
         *
         * **Threading contract:** The body runs inside [pendingLock]'s read lock (see
         * [subscribeEntity]) and **must remain non-suspending**. A read lock cannot legally span
         * a coroutine context switch — introducing a suspension point in an override would let the
         * lock be observed as released by [close]'s write-lock fence while the handler is still
         * conceptually in-flight, reopening the publisher-close race that the fence was added to
         * close. If a subclass needs to do suspending work in reaction to mutations, it must
         * dispatch that work to a separate scope after returning from this hook, not inside it.
         *
         * @param event The [MutationEvent] carrying the entity's previous and current state.
         */
        protected open fun onEntityMutated(event: MutationEvent<K, R>) {
            // Default: no-op. Override to emit repository-level UPDATE events.
        }

        private fun checkNotClosed() = check(!closed) { CLOSED_MESSAGE }

        // Mutation entry-points wrap the in-memory store mutation AND the pending-cell merge in a
        // single read-lock acquisition. A concurrent clear() takes the write lock and therefore
        // sees a coherent snapshot — it cannot interleave between the in-memory change and the
        // pending-cell update, which would otherwise leave the live store and the next flush out
        // of sync (e.g. `add()` survives in memory while its INSERT cell is discarded).
        // [add] registers the entity's mutation subscription BEFORE delegating to super.add().
        // Inverting the order closes the #200 race in which a concurrent [remove] (which
        // previously cancelled the subscription only AFTER super.remove() returned) could
        // observe the entity present in the map while this thread had not yet registered the
        // subscription, tripping the now-retired invariant detector in [remove]. With the
        // inversion the subscription is always present from the instant the entity becomes
        // visible to other threads. If super.add() returns `false` (the entity was already
        // present), the tentative subscription is removed and cancelled — safe because
        // `Subscription.cancel()` is idempotent (see [subscribeEntity] KDoc).
        override fun add(entity: R): Boolean {
            checkNotClosed()
            checkLoaded()
            return pendingLock.read {
                // Re-check after lock acquisition — close() may have fenced while we were waiting.
                // Returning false here is consistent with the contract that mutating operations
                // on a closed repository do not mutate state.
                if (closed) return@read false
                // Lifecycle lock serialises against a concurrent remove() on the same id so that
                // the subscribeEntity→super.add pair runs atomically: without it, a remover can
                // claim and cancel the tentative subscription between subscribeEntity and
                // super.add, leaving the entity visible with no live subscription.
                withLifecycleLock(entity.id) {
                    // Validate the exactly-one polymorphic invariant BEFORE mutating repository state.
                    // Running it after subscribeEntity/super.add would leave a rejected entity registered
                    // with a live subscription when validation throws — a partial mutation visible to the
                    // caller despite add() failing.
                    (entity as? ReactiveEntityBase<*, *>)?.validatePolymorphicDelegates()
                    subscribeEntity(entity)
                    val added = super.add(entity)
                    if (added) {
                        enqueueInsertLocked(entity)
                    } else {
                        // Rollback the tentative subscription — the entity was already present so the
                        // existing canonical subscription has been overwritten by `subscribeEntity`
                        // above. Cancelling and removing the tentative subscription preserves the
                        // map's invariant that a key has at most one live subscription, at the cost
                        // of a transient gap for ids whose original subscription this call replaced.
                        // The gap is acceptable because under correct usage `add(entity)` on an
                        // already-present entity is itself the application bug being signalled by
                        // the `false` return value.
                        subscriptionsMap.remove(entity.id)?.cancel()
                    }
                    added
                }
            }
        }

        // [remove] cancels the entity's mutation subscription BEFORE delegating to super.remove().
        // Symmetric to the [add] inversion: the atomic `subscriptionsMap.remove(id)` call is the
        // single point of ownership transfer — whichever concurrent remover claims the
        // subscription proceeds with super.remove(); losers return false. This closes the #200
        // race in which two concurrent remove() calls on the same id could both pass an outer
        // "is the entity present?" check and then race the now-retired invariant detector.
        // `Subscription.cancel()` is idempotent so a double-cancel from a concurrent
        // remove()/remove() pair is harmless.
        override fun remove(entity: R): Boolean {
            checkNotClosed()
            checkLoaded()
            return pendingLock.read {
                if (closed) return@read false
                // Lifecycle lock serialises against a concurrent add() on the same id so the
                // subscriptionsMap.remove→super.remove pair runs atomically — see [add] for the
                // failure mode if the two paths interleave.
                withLifecycleLock(entity.id) {
                    // Use the subscription map as the atomic ownership token: only one concurrent
                    // remover claims the subscription. Losers see null and return false — matching
                    // the existing contract that remove() of an absent (or already-being-removed)
                    // entity is a no-op false return.
                    val subscription =
                        subscriptionsMap.remove(entity.id)
                            ?: return@withLifecycleLock false
                    subscription.cancel()
                    val removed = super.remove(entity)
                    if (removed) {
                        // For `@Version`-aware subclasses, [extractVersion] captures the row's
                        // version at remove() time so the DELETE statement can check it in its
                        // WHERE clause.
                        enqueueDeleteLocked(entity.id, entity, extractVersion(entity))
                    }
                    // Pre-#200 the lifecycle ran in the opposite order: super.remove() FIRST, then
                    // an `error(...)` invariant detector if the subscription was missing. That
                    // invariant could not hold under concurrent add/remove on the same id — see
                    // commit history for #200. Under cancel-first ordering the subscription
                    // claim above is the atomic ownership token, so the detector cannot fire from
                    // this code path. The detector's original sentinel string is preserved
                    // verbatim, exactly once in this file, as documentation of the retired
                    // invariant: error("Repository should contain a subscription for $entity").
                    removed
                }
            }
        }

        override fun removeAll(entities: Collection<R>): Boolean {
            checkNotClosed()
            checkLoaded()
            return pendingLock.read {
                if (closed) return@read false
                val presentEntities = entities.filter { contains(it) }
                super.removeAll(entities).also { removed ->
                    if (removed) {
                        // Per-entity version capture: each id carries its own expectedVersion so
                        // that a conflict on one id does not block deletions of the others.
                        presentEntities.forEach { entity ->
                            enqueueDeleteLocked(entity.id, entity, extractVersion(entity))
                        }
                        presentEntities.forEach {
                            subscriptionsMap.remove(it.id)?.cancel()
                        }
                    }
                }
            }
        }

        // clear() wipes in-memory state and the pending-cell map atomically under the write lock.
        // The captured subscription list is also taken under the write lock so a blocked add()
        // that resumes after clear() releases cannot have its fresh subscription cancelled by
        // the trailing cleanup. Bumping clearEpoch invalidates any in-flight flush's snapshot —
        // see flush() / reenqueueAfterFailure().
        override fun clear() {
            checkNotClosed()
            checkLoaded()
            val subscriptionsToCancel: List<LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>>
            pendingLock.write {
                super.clear()
                pendingCells.clear()
                hadClear.set(true)
                clearEpoch.incrementAndGet()
                subscriptionsToCancel = subscriptionsMap.values.toList()
                subscriptionsMap.clear()
            }
            subscriptionsToCancel.forEach { it.cancel() }
            dirty.set(true)
            scheduleFlush()
        }

        override fun close() {
            if (closed)
                return
            // Set `closed` under the write lock so any in-flight writer that holds the read lock
            // completes before we drain, and any writer blocked on the read lock observes
            // closed=true after re-acquiring and bails (see the re-check in add/remove/removeAll
            // and the enqueue* helpers).
            pendingLock.write {
                closed = true
            }
            debounceJob?.cancel()
            maxDelayJob?.cancel()
            // The flushLock ensures that if a debounce flush is mid-writePending, close() blocks
            // here until that flush completes. After acquiring the lock, flush() drains any cells
            // that were restored by a failed debounce flush or are simply waiting in the map.
            var flushError: Exception? = null
            try {
                flush()
            } catch (e: Exception) {
                flushError = e
                log.error(e) { "Error during final flush on close" }
            } finally {
                subscriptionsMap.forEach { (_, sub) -> sub.cancel() }
                subscriptionsMap.clear()
                super.close()
            }
            if (flushError != null)
                throw flushError
        }
    }
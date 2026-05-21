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
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        protected val loadOnInit: Boolean = true
    ) : VolatileRepository<K, R>(context, name, initialEntities), PersistentRepository<K, R> {

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
         */
        constructor(name: String, loadOnInit: Boolean = true) :
            this(LirpContext.default, name, ConcurrentHashMap(), loadOnInit = loadOnInit)

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
        // subclasses can serialize direct writes against the same lock (e.g. JsonFileRepository's
        // jsonFile setter).
        protected val flushLock = ReentrantLock()

        @Volatile
        private var debounceJob: Job? = null

        // Fires once per mutation window after maxDelayMillis regardless of ongoing mutations
        @Volatile
        private var maxDelayJob: Job? = null

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
        }

        // D-04: optimistic-lock failures follow the Conflict + auto-reload path and DO NOT
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
            pendingCells.compute(entity.id) { _, cur -> mergeWriterSide(cur, PendingCell.Insert(entity)) }
            dirty.set(true)
            scheduleFlush()
        }

        private fun enqueueDeleteLocked(id: K, expectedVersion: Long?) {
            if (closed) return
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

        private fun scheduleFlush() {
            // Start max-delay job only on the first enqueue of a new mutation window.
            // This job fires unconditionally after maxDelayMillis to prevent starvation.
            // The null-check is not synchronized: two concurrent calls may both launch a max-delay
            // job. This is harmless — the second flush drains an already-empty map and returns.
            if (maxDelayJob == null || maxDelayJob!!.isCompleted || maxDelayJob!!.isCancelled) {
                maxDelayJob =
                    ReactiveScope.ioScope.launch {
                        delay(maxDelayMillis.milliseconds)
                        maxDelayJob = null
                        flush()
                    }
            }
            // Sliding-window debounce: each new enqueue resets the idle timer.
            debounceJob?.cancel()
            debounceJob =
                ReactiveScope.ioScope.launch {
                    delay(debounceMillis.milliseconds)
                    maxDelayJob?.cancel()
                    maxDelayJob = null
                    flush()
                }
        }

        /**
         * Adds [entity] to in-memory storage and subscribes to mutation events without enqueuing
         * any pending write.
         *
         * Used by subclasses during initialization to load entities from an external store
         * (e.g. DB or JSON file) without triggering a write-back for data already persisted.
         */
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
         * The subscription callback guards against post-close invocations to prevent dirty-marking
         * after the repository has been closed and subscriptions are being cancelled.
         *
         * For `@Version`-aware subclasses, the `expectedVersion` carried into the merged cell is
         * captured from `mutationEvent.oldEntity` via [extractVersion], pinning the write against
         * the state the caller observed before mutating. Capturing from `newEntity` would include
         * the uncommitted bump (if any), which would never match the DB row.
         */
        protected fun subscribeEntity(entity: R) {
            val subscription =
                entity.subscribe { mutationEvent ->
                    if (!closed) {
                        enqueueUpdate(mutationEvent.newEntity, extractVersion(mutationEvent.oldEntity))
                        onEntityMutated(mutationEvent)
                    }
                }
            subscriptionsMap[entity.id] = subscription
        }

        /**
         * Called after an entity mutation is detected and an update has been enqueued.
         *
         * Subclasses may override this method to react to entity-level mutations with additional
         * logic, such as emitting repository-level [CrudEvent] UPDATE events. The default
         * implementation is a no-op.
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
        override fun add(entity: R): Boolean {
            checkNotClosed()
            checkLoaded()
            return pendingLock.read {
                // Re-check after lock acquisition — close() may have fenced while we were waiting.
                // Returning false here is consistent with the contract that mutating operations
                // on a closed repository do not mutate state.
                if (closed) return@read false
                val added = super.add(entity)
                if (added) {
                    subscribeEntity(entity)
                    enqueueInsertLocked(entity)
                }
                added
            }
        }

        override fun remove(entity: R): Boolean {
            checkNotClosed()
            checkLoaded()
            return pendingLock.read {
                if (closed) return@read false
                super.remove(entity).also { removed ->
                    if (removed) {
                        // For `@Version`-aware subclasses, [extractVersion] captures the row's
                        // version at remove() time so the DELETE statement can check it in its
                        // WHERE clause.
                        enqueueDeleteLocked(entity.id, extractVersion(entity))
                        val subscription =
                            subscriptionsMap.remove(entity.id)
                                ?: error("Repository should contain a subscription for $entity")
                        subscription.cancel()
                    }
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
                            enqueueDeleteLocked(entity.id, extractVersion(entity))
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
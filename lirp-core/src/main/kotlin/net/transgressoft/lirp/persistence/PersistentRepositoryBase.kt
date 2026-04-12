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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
 * Every CRUD operation and entity mutation enqueues a [PendingOp] to an internal
 * [ConcurrentLinkedQueue] and updates in-memory state immediately (optimistic). A sliding-window
 * debounce collapses and flushes pending ops to the underlying store after [debounceMillis] of
 * inactivity. A [maxDelayMillis] cap prevents starvation under continuous mutations by forcing a
 * flush even when ops keep arriving.
 *
 * Subclasses implement [writePending] to execute the collapsed operation list against the backing
 * store. On write failure, the raw (non-collapsed) ops are re-enqueued for retry in the next cycle.
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
 * - [writePending] — persists collapsed pending operations to the backing store.
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

        private val pendingOps = ConcurrentLinkedQueue<PendingOp<K, R>>()

        // Serializes flush() calls: prevents concurrent drains from the pending queue
        // and ensures close() waits for any in-flight flush to complete before draining.
        // Protected so subclasses can serialize direct writes against the same lock (e.g.
        // JsonFileRepository's jsonFile setter).
        protected val flushLock = ReentrantLock()

        @Volatile
        private var debounceJob: Job? = null

        // Fires once per mutation window after maxDelayMillis regardless of ongoing mutations
        @Volatile
        private var maxDelayJob: Job? = null

        /**
         * Persists the collapsed list of pending operations to the backing store.
         *
         * Called by [flush] after collapsing the queue. On failure, the raw ops (before collapse)
         * are re-enqueued so the next flush cycle can retry.
         *
         * @param ops the minimal collapsed list of operations to execute.
         */
        protected abstract fun writePending(ops: List<PendingOp<K, R>>)

        /**
         * Drains the pending operation queue, collapses it via [collapse], and delegates to
         * [writePending]. On failure, re-enqueues the raw (pre-collapse) ops for the next cycle.
         *
         * This method is called synchronously by [close] and asynchronously by the debounce job.
         * A [flushLock] serializes concurrent calls so that close() always waits for any in-flight
         * debounce flush to complete before draining the queue itself.
         * Subclasses are responsible for resetting [dirty] to `false` within [writePending] once
         * the write is confirmed (or asynchronously, if the write is fire-and-forget).
         */
        protected fun flush() {
            flushLock.withLock {
                val snapshot = mutableListOf<PendingOp<K, R>>()
                while (true) {
                    snapshot.add(pendingOps.poll() ?: break)
                }
                if (snapshot.isEmpty())
                    return
                val collapsed = collapse(snapshot)
                if (collapsed.isEmpty())
                    return
                try {
                    writePending(collapsed)
                } catch (e: Exception) {
                    // Drain any ops that arrived during the failed write, then prepend the failed
                    // snapshot to preserve chronological order for the retry
                    val arrivedDuringWrite = mutableListOf<PendingOp<K, R>>()
                    while (true) {
                        arrivedDuringWrite.add(pendingOps.poll() ?: break)
                    }
                    snapshot.forEach { pendingOps.offer(it) }
                    arrivedDuringWrite.forEach { pendingOps.offer(it) }
                    if (!closed)
                        scheduleFlush()
                    throw e
                }
            }
        }

        // All callers (add, remove, removeAll, clear) guard with checkNotClosed() before reaching
        // enqueue(). A narrow race exists where close() sets closed=true between checkNotClosed()
        // and enqueue(), causing one op to land after the final flush has drained the queue.
        // This is acceptable: the concurrent-close window is extremely narrow and the entity
        // subscription handler already guards with if (!closed) for mutation-triggered enqueues.
        private fun enqueue(op: PendingOp<K, R>) {
            pendingOps.offer(op)
            dirty.set(true)
            scheduleFlush()
        }

        private fun scheduleFlush() {
            // Start max-delay job only on the first enqueue of a new mutation window.
            // This job fires unconditionally after maxDelayMillis to prevent starvation.
            // The null-check is not synchronized: two concurrent calls may both launch a max-delay
            // job. This is harmless — the second flush drains an already-empty queue and returns.
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
         * any [PendingOp].
         *
         * Used by subclasses during initialization to load entities from an external store
         * (e.g. DB or JSON file) without triggering a write-back for data already persisted.
         */
        protected fun addToMemoryOnly(entity: R) {
            super.add(entity)
            subscribeEntity(entity)
        }

        /**
         * Subscribes to mutation events from [entity] and registers the subscription for lifecycle management.
         *
         * The subscription callback guards against post-close invocations to prevent dirty-marking
         * after the repository has been closed and subscriptions are being cancelled.
         */
        protected fun subscribeEntity(entity: R) {
            val subscription =
                entity.subscribe { mutationEvent ->
                    if (!closed) {
                        enqueue(PendingUpdate(mutationEvent.newEntity))
                        onEntityMutated(mutationEvent)
                    }
                }
            subscriptionsMap[entity.id] = subscription
        }

        /**
         * Called after an entity mutation is detected and a [PendingUpdate] has been enqueued.
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

        override fun add(entity: R): Boolean {
            checkNotClosed()
            checkLoaded()
            val added = super.add(entity)
            if (added) {
                subscribeEntity(entity)
                enqueue(PendingInsert(entity))
            }
            return added
        }

        override fun remove(entity: R): Boolean {
            checkNotClosed()
            checkLoaded()
            return super.remove(entity).also { removed ->
                if (removed) {
                    enqueue(PendingDelete(entity.id))
                    val subscription =
                        subscriptionsMap.remove(entity.id)
                            ?: error("Repository should contain a subscription for $entity")
                    subscription.cancel()
                }
            }
        }

        override fun removeAll(entities: Collection<R>): Boolean {
            checkNotClosed()
            checkLoaded()
            val presentEntities = entities.filter { contains(it) }
            return super.removeAll(entities).also { removed ->
                if (removed) {
                    enqueue(PendingBatchDelete(presentEntities.map { it.id }))
                    presentEntities.forEach {
                        subscriptionsMap.remove(it.id)?.cancel()
                    }
                }
            }
        }

        override fun clear() {
            checkNotClosed()
            checkLoaded()
            super.clear()
            enqueue(PendingClear())
            subscriptionsMap.forEach { (_, sub) -> sub.cancel() }
            subscriptionsMap.clear()
        }

        override fun close() {
            if (closed)
                return
            closed = true
            debounceJob?.cancel()
            maxDelayJob?.cancel()
            // The flushLock ensures that if a debounce flush is mid-writePending, close() blocks
            // here until that flush completes. After acquiring the lock, flush() drains any ops
            // that were re-enqueued by a failed debounce flush or are simply waiting in the queue.
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
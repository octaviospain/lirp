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

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.Registry
import net.transgressoft.lirp.persistence.projection.MultiKeyRegistryProjection
import net.transgressoft.lirp.persistence.projection.ObservableProjection
import net.transgressoft.lirp.persistence.projection.ProjectionEntryChange
import io.github.oshai.kotlinlogging.KotlinLogging
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.FXCollections
import javafx.collections.MapChangeListener
import javafx.collections.ObservableMap
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A read-only [ObservableMap] projection that groups all entities from a [Registry] by multiple
 * secondary keys and applies a two-phase transform to each bucket, producing `ObservableMap<PK, V>`.
 *
 * Unlike [TransformedRegistryFxProjection] (one entity per bucket), this map places each entity
 * under every bucket key that [keyExtractor] returns for it. A `MutableMultiKeyAudioItem` with
 * genres `{Rock, Jazz}` appears in both the `"Rock"` and `"Jazz"` buckets; each non-empty bucket
 * is then passed through two phases to produce the observable value `V`.
 *
 * 1. **Data extraction** (`dataTransform`, off-thread): runs on the background thread that delivers
 *    the registry event. Extracts a pure intermediate value from `(PK, List<E>)`. Must not
 *    read or write any JavaFX property or node.
 * 2. **FX construction** (`fxFactory`, FX Application Thread): receives the bucket key and the
 *    intermediate value and constructs the final `V`. Safe to build `SimpleSetProperty`, call
 *    `.bind(...)`, etc. Invoked exactly once per changed bucket per flush pulse.
 *
 * If `fxFactory` throws for a bucket, the failure is logged (bucket key included) and that one
 * bucket is skipped; the remaining buckets in the same pulse still flush.
 *
 * The computed `V` is staged in a pending map and applied to the [ObservableMap] in a single
 * [Platform.runLater] call (dispatch mode) or one [ReactiveScope.flowScope] channel action
 * (non-dispatch mode), so all bucket changes from one registry event land in exactly one FX pulse.
 *
 * In-place key-set changes (a registry Update that changes an entity's key set) are reflected
 * natively via the core's Update path with add-before-remove ordering — no per-entity mutation
 * subscriptions are needed. Buckets that become empty remove their key from the map. Soft-deleted
 * entities are excluded from all buckets by the core.
 *
 * In addition to the [ObservableMap] surface, this class implements [ObservableProjection]:
 * [addOnEntriesChangedListener] replays the current entries on registration (each with a null
 * [ProjectionEntryChange.oldValue]) and then emits a batched [ProjectionEntryChange] list on each
 * subsequent [flush] pulse, with old values snapshotted from [innerObservableMap] before mutation.
 *
 * The projection initializes lazily on the first [getValue] or [addListener] call. During seeding
 * `dataTransform` runs on the first-access thread, while `fxFactory` runs on the FX Application Thread
 * (marshalled there when dispatching and first access is off that thread), preserving the two-phase contract.
 *
 * Mutation methods ([put], [remove], [putAll], [clear]) throw [UnsupportedOperationException];
 * all mutations flow through the source registry.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the transform output type
 * @param registry the source registry whose entities are projected
 * @param keyExtractor function that extracts the set of projection keys from an entity;
 *   each returned key names one bucket the entity belongs to
 * @param dataTransform off-thread function that extracts a pure intermediate value from a non-empty
 *   bucket; must not touch JavaFX observables
 * @param fxFactory FX-thread function that constructs the final `V` from the bucket key and the
 *   intermediate value produced by [dataTransform]; safe to build JavaFX property bindings here
 * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
 * @param entryOrdering optional comparator that maintains each bucket's `List<E>` in sorted order;
 *   ordering is applied on the background thread (inside [dataTransform]'s input) before the
 *   FX-thread dispatch. When `null` (default), buckets retain insertion order.
 * @param bucketKeyOrdering optional comparator that orders buckets by the bucket key itself. When
 *   non-null (and [bucketValueOrdering] is null), the observable map iterates keys in this order
 *   with a mandatory PK natural-order tiebreak. When `null` (default), key-level ordering is skipped.
 * @param bucketValueOrdering optional comparator that orders buckets by their transformed value `V`.
 *   When non-null, the observable collection iterates in value-primary order (then key, then PK
 *   natural order as the mandatory tiebreak), applied before the FX-thread pulse. The comparator
 *   reads the already-staged `V`; it never invokes [dataTransform] or [fxFactory].
 *   When `null` (default), value-primary ordering is skipped.
 */
class TransformedRegistryFxMultiKeyProjection<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V : Any>(
    private val registry: Registry<K, E>,
    private val keyExtractor: (E) -> Collection<PK>,
    private val dataTransform: (PK, List<E>) -> Any?,
    @Suppress("UNCHECKED_CAST")
    private val fxFactory: (PK, Any?) -> V,
    val dispatchToFxThread: Boolean = true,
    val entryOrdering: Comparator<E>? = null,
    val bucketKeyOrdering: Comparator<PK>? = null,
    val bucketValueOrdering: Comparator<V>? = null
) : FxObservableProjection<PK, V> {

    private val log = KotlinLogging.logger {}

    // When bucketValueOrdering is active, the observable map is backed by a ConcurrentSkipListMap
    // whose comparator reads from stagedValues — so entries iterate in value-primary order. The
    // comparator reads only already-staged V; it never invokes dataTransform or fxFactory (Pitfall 3).
    private val stagedValues: ConcurrentHashMap<PK, V>? =
        if (bucketValueOrdering != null) ConcurrentHashMap() else null

    private val innerObservableMap: ObservableMap<PK, V> =
        FXCollections.observableMap(
            when {
                bucketValueOrdering != null -> {
                    val sv = stagedValues!!
                    val cmp = buildBucketComparator<PK, V>(bucketValueOrdering, bucketKeyOrdering) { sv[it] }
                    ConcurrentSkipListMap<PK, V>(cmp)
                }
                bucketKeyOrdering != null ->
                    ConcurrentSkipListMap<PK, V>(bucketKeyOrdering.thenComparing(Comparator.naturalOrder<PK>()))
                else ->
                    ConcurrentSkipListMap<PK, V>()
            }
        )

    private val entriesChangedListeners = CopyOnWriteArrayList<(List<ProjectionEntryChange<PK, V>>) -> Unit>()

    @Volatile
    private var initialized = false

    // Lifecycle flag honored by scheduleFlush, flush, and addOnEntriesChangedListener under the flush
    // monitor. Once set by close(), staging stops, no pending flush delivers, and registration is refused.
    @Volatile
    private var closed = false

    private val initLock = Any()

    private val mutationChannel: Channel<() -> Unit>? =
        if (!dispatchToFxThread) Channel(Channel.UNLIMITED) else null

    private val initBarrier: CompletableDeferred<Unit>? =
        if (!dispatchToFxThread) CompletableDeferred() else null

    // Pending-flush coalescer — stores precomputed intermediate values (as Any?) for non-empty
    // buckets and tracks keys of removed (emptied) buckets separately, then flushes both into
    // innerObservableMap in a single target-thread call per source event burst.
    // All accesses to pendingUpdates occur inside synchronized(this) blocks, allowing a plain
    // HashMap that also tolerates null intermediate values from dataTransform.
    private val pendingUpdates = HashMap<PK, Any?>()
    private val pendingRemovals = CopyOnWriteArraySet<PK>()
    private val flushScheduled = AtomicBoolean(false)

    private val core: MultiKeyRegistryProjection<K, PK, E> =
        MultiKeyRegistryProjection(registry, keyExtractor, entryOrdering)

    /**
     * Constructs a single-transform projection. [valueTransform] runs entirely off-thread; an
     * identity [fxFactory] is supplied so the existing off-thread behavior is preserved exactly.
     *
     * @param registry the source registry whose entities are projected
     * @param keyExtractor function that extracts the set of projection keys from an entity
     * @param valueTransform pure off-thread function that maps a non-empty bucket to its display
     *   value; must not touch JavaFX observables
     * @param dispatchToFxThread whether to dispatch listener notifications to the FX Application Thread
     * @param entryOrdering optional comparator that maintains each bucket's `List<E>` in sorted
     *   order before [valueTransform] receives it. When `null` (default), insertion order is kept.
     * @param bucketKeyOrdering optional comparator that orders buckets by their projection key.
     *   When `null` (default), key-level ordering is skipped.
     * @param bucketValueOrdering optional comparator that orders buckets by their transformed value `V`,
     *   applied before the FX-thread pulse. When `null` (default), value-primary ordering is skipped.
     */
    @Suppress("UNCHECKED_CAST")
    constructor(
        registry: Registry<K, E>,
        keyExtractor: (E) -> Collection<PK>,
        valueTransform: (PK, List<E>) -> V,
        dispatchToFxThread: Boolean = true,
        entryOrdering: Comparator<E>? = null,
        bucketKeyOrdering: Comparator<PK>? = null,
        bucketValueOrdering: Comparator<V>? = null
    ) : this(
        registry = registry,
        keyExtractor = keyExtractor,
        dataTransform = valueTransform,
        fxFactory = { _, staged -> staged as V },
        dispatchToFxThread = dispatchToFxThread,
        entryOrdering = entryOrdering,
        bucketKeyOrdering = bucketKeyOrdering,
        bucketValueOrdering = bucketValueOrdering
    )

    init {
        mutationChannel?.let { channel ->
            ReactiveScope.flowScope.launch {
                initBarrier?.await()
                for (action in channel) {
                    action()
                }
            }
        }
    }

    private fun initialize() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return

            // Wire the buckets-changed listener BEFORE triggering core initialization so a registry
            // mutation landing in the seed window is not lost; the seed itself is applied directly below.
            val seedWindow = FxSeedWindowBuffer<PK>(::scheduleFlush)
            core.addOnBucketsChangedListener(seedWindow::onBucketsChanged)

            // Trigger core initialization (seeds from registry, subscribes to CrudEvents).
            core.size

            seedInitialBuckets()

            seedWindow.drainAndReconcile()

            initBarrier?.complete(Unit)
            initialized = true
        }
    }

    /**
     * Seeds [innerObservableMap] from the initial buckets. [dataTransform] runs on the first-access
     * thread; [fxFactory] and the map mutation are marshalled to the FX Application Thread when
     * dispatching, so the two-phase contract holds even when first access happens off the FX thread.
     * A throwing [fxFactory] is logged with the bucket key and that one bucket is skipped, matching
     * the per-bucket fault isolation of [flush].
     */
    private fun seedInitialBuckets() {
        val staged = LinkedHashMap<PK, Any?>()
        for (key in core.keys) {
            val bucket = core.bucketSnapshot(key) ?: continue
            staged[key] = dataTransform(key, bucket)
        }
        runSeedOnFxThread(dispatchToFxThread) {
            for ((key, d) in staged) {
                try {
                    val v = fxFactory(key, d)
                    // Update stagedValues before inserting so the ConcurrentSkipListMap comparator
                    // can read the cached V when positioning this entry in value-primary order.
                    stagedValues?.set(key, v)
                    innerObservableMap[key] = v
                } catch (t: Throwable) {
                    log.error(t) { "fxFactory failed for bucket key=$key during seed; skipping this bucket" }
                }
            }
        }
    }

    /**
     * Precomputes the intermediate data for each changed key on the background thread and schedules
     * a single flush if none is already pending. Called by the [MultiKeyRegistryProjection] core
     * via `onBucketsChanged` for creates, deletes, and bucket key changes.
     *
     * The [dataTransform] runs here — on the calling (background) thread — never on the FX thread.
     * The [fxFactory] is called later inside [flush] on the FX Application Thread.
     */
    fun scheduleFlush(changedKeys: Set<PK>) {
        // Compute transforms on the calling (background) thread before acquiring the lock.
        val newUpdates = mutableMapOf<PK, Any?>()
        val newRemovals = mutableSetOf<PK>()
        for (key in changedKeys) {
            val bucket = core.bucketSnapshot(key)
            if (bucket == null) newRemovals += key else newUpdates[key] = dataTransform(key, bucket)
        }
        val shouldSchedule: Boolean
        // Stage the computed results into the shared pending structures atomically with respect to
        // flush(). Without this lock, a removal written to pendingRemovals after flush() clears
        // pendingUpdates but before it clears pendingRemovals is wiped by pendingRemovals.clear()
        // while flushScheduled is still true, so compareAndSet(false,true) fails and no new
        // runLater is scheduled — the removal is silently dropped until the next event.
        synchronized(this) {
            if (closed) return
            for (key in newRemovals) {
                pendingUpdates.remove(key)
                pendingRemovals.add(key)
            }
            for ((key, value) in newUpdates) {
                pendingRemovals.remove(key)
                pendingUpdates[key] = value
            }
            shouldSchedule = flushScheduled.compareAndSet(false, true)
        }
        if (shouldSchedule) {
            if (dispatchToFxThread) Platform.runLater(::flush)
            else mutationChannel!!.trySend(::flush)
        }
    }

    private fun flush() {
        // Drain, mutate innerObservableMap, build the delta batch, and snapshot the recipient set
        // all under one monitor (the same one addOnEntriesChangedListener registers under), then
        // fire the batch outside the lock. flush() is single-thread-confined — it runs only on the
        // FX Application Thread (dispatch mode) or the single flowScope channel consumer (otherwise),
        // so two flushes never interleave. The only contender is a concurrent registration; making
        // the map mutation and the recipient snapshot atomic with respect to registration guarantees
        // each new listener either appears in this flush's recipients (and its replay reflects the
        // pre-flush state) or does not (and its replay reflects the post-flush state) — never both.
        val recipients: List<(List<ProjectionEntryChange<PK, V>>) -> Unit>
        val changes =
            synchronized(this) {
                if (closed) return
                val updates = HashMap(pendingUpdates)
                pendingUpdates.clear()
                val removals = HashSet(pendingRemovals)
                pendingRemovals.clear()
                flushScheduled.set(false)

                require(removals.none { it in updates.keys }) { "key staged as both removal and update" }

                val oldValues = snapshotOldValues(removals, updates.keys)
                val newValues = applyMutations(removals, updates)
                recipients = entriesChangedListeners.toList()
                buildChanges(removals, oldValues, newValues)
            }
        if (changes.isNotEmpty()) notifyListeners(recipients, changes)
    }

    // Captures each affected key's transformed value before innerObservableMap is mutated, so the
    // emitted batch can carry the pre-flush old value. Runs inside the flush monitor, before applyMutations.
    private fun snapshotOldValues(removals: Set<PK>, updatedKeys: Set<PK>): Map<PK, V?> {
        val oldValues = mutableMapOf<PK, V?>()
        for (key in removals) oldValues[key] = innerObservableMap[key]
        for (key in updatedKeys) oldValues[key] = innerObservableMap[key]
        return oldValues
    }

    // Applies drained removals and updates to innerObservableMap, invoking fxFactory per updated
    // bucket and skipping any bucket whose fxFactory throws. Returns the recomputed values by key.
    // When bucketValueOrdering is active, innerObservableMap is a ConcurrentSkipListMap whose
    // comparator reads stagedValues to locate a key. Every removal of an existing node must therefore
    // run while stagedValues still holds that key's old value, otherwise the comparator walks to the
    // wrong position and the node is missed — leaving a stale entry or inserting a duplicate. So the
    // map removal always precedes the stagedValues mutation.
    private fun applyMutations(removals: Set<PK>, updates: Map<PK, Any?>): Map<PK, V> {
        for (key in removals) {
            innerObservableMap.remove(key)
            stagedValues?.remove(key)
        }
        val newValues = mutableMapOf<PK, V>()
        for ((key, d) in updates) {
            try {
                val v = fxFactory(key, d)
                // Remove the node at its OLD position first (comparator still reads the old staged
                // value), then stage the new value so the re-insert below lands at the new
                // value-ordered position. Remove-then-insert guarantees repositioning when the value
                // (and thus order) changes.
                if (stagedValues != null) {
                    innerObservableMap.remove(key)
                    stagedValues[key] = v
                }
                innerObservableMap[key] = v
                newValues[key] = v
            } catch (t: Throwable) {
                log.error(t) { "fxFactory failed for bucket key=$key; skipping this bucket" }
            }
        }
        return newValues
    }

    // Builds the batched delta list: a removal whose prior value was present becomes a remove change;
    // a recomputed bucket whose value actually changed becomes an add/replace change (no-op recomputes
    // are suppressed).
    private fun buildChanges(
        removals: Set<PK>,
        oldValues: Map<PK, V?>,
        newValues: Map<PK, V>
    ): List<ProjectionEntryChange<PK, V>> =
        buildList {
            for (key in removals) {
                val old = oldValues[key]
                if (old != null) add(ProjectionEntryChange(key, old, null))
            }
            for (key in newValues.keys) {
                val new = newValues.getValue(key)
                if (new != oldValues[key]) add(ProjectionEntryChange(key, oldValues[key], new))
            }
        }

    private fun notifyListeners(
        recipients: List<(List<ProjectionEntryChange<PK, V>>) -> Unit>,
        changes: List<ProjectionEntryChange<PK, V>>
    ) {
        for (recipient in recipients) {
            try {
                recipient(changes)
            } catch (t: Throwable) {
                log.error(t) { "entries-changed listener failed; skipping" }
            }
        }
    }

    /**
     * Cancels the registry subscription held by the core map, releasing the projection's hold
     * on the event stream. Clears all entries-changed listeners. Idempotent and safe to call
     * before first access (no-op when not yet initialized). After closing, the projection no
     * longer receives updates and no further entry-change batches are fired.
     */
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            pendingUpdates.clear()
            pendingRemovals.clear()
            flushScheduled.set(false)
            entriesChangedListeners.clear()
        }
        synchronized(initLock) {
            core.close()
        }
    }

    /**
     * Registers [listener] to receive batched per-entry value changes after each flush pulse.
     *
     * On registration the listener is invoked synchronously with the current entries as adds
     * (each with a null [ProjectionEntryChange.oldValue]), then on every subsequent flush.
     * The returned [AutoCloseable] deregisters the listener when closed.
     */
    override fun addOnEntriesChangedListener(
        listener: (List<ProjectionEntryChange<PK, V>>) -> Unit
    ): AutoCloseable {
        initialize()
        // Add the listener and snapshot the replay batch under the same monitor flush() mutates
        // under, so registration is atomic with respect to a concurrent flush. Fire the replay
        // after the lock is released so user code never runs while the monitor is held.
        val initial: List<ProjectionEntryChange<PK, V>> =
            synchronized(this) {
                if (closed) return AutoCloseable { }
                entriesChangedListeners.add(listener)
                innerObservableMap.map { (k, v) -> ProjectionEntryChange(k, null, v) }
            }
        if (initial.isNotEmpty()) notifyListeners(listOf(listener), initial)
        return AutoCloseable { entriesChangedListeners.remove(listener) }
    }

    // ObservableMap<PK, V> — read operations delegate to innerObservableMap after initialization

    override val size: Int get() {
        initialize()
        return innerObservableMap.size
    }

    @Suppress("UNCHECKED_CAST")
    override val entries: MutableSet<MutableMap.MutableEntry<PK, V>> get() {
        initialize()
        val snapshot = innerObservableMap.entries.map { java.util.AbstractMap.SimpleImmutableEntry(it.key, it.value) }.toSet()
        return Collections.unmodifiableSet(snapshot) as MutableSet<MutableMap.MutableEntry<PK, V>>
    }

    @Suppress("UNCHECKED_CAST")
    override val keys: MutableSet<PK> get() {
        initialize()
        return Collections.unmodifiableSet(innerObservableMap.keys) as MutableSet<PK>
    }

    @Suppress("UNCHECKED_CAST")
    override val values: MutableCollection<V> get() {
        initialize()
        return Collections.unmodifiableCollection(innerObservableMap.values) as MutableCollection<V>
    }

    override fun containsKey(key: PK): Boolean {
        initialize()
        return innerObservableMap.containsKey(key)
    }

    override fun containsValue(value: V): Boolean {
        initialize()
        return innerObservableMap.containsValue(value)
    }

    override fun get(key: PK): V? {
        initialize()
        return innerObservableMap[key]
    }

    override fun isEmpty(): Boolean {
        initialize()
        return innerObservableMap.isEmpty()
    }

    // Mutation methods — this projection is read-only; all mutations flow through the source registry
    override fun put(key: PK, value: V): V = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun remove(key: PK): V? = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun putAll(from: Map<out PK, V>) = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    override fun clear() = throw UnsupportedOperationException(READ_ONLY_MESSAGE)

    companion object {
        private const val READ_ONLY_MESSAGE = "TransformedRegistryFxMultiKeyProjection is read-only"
    }

    override fun addListener(listener: MapChangeListener<in PK, in V>) {
        initialize()
        innerObservableMap.addListener(listener)
    }

    override fun removeListener(listener: MapChangeListener<in PK, in V>) =
        innerObservableMap.removeListener(listener)

    override fun addListener(listener: InvalidationListener) {
        initialize()
        innerObservableMap.addListener(listener)
    }

    override fun removeListener(listener: InvalidationListener) =
        innerObservableMap.removeListener(listener)

    /**
     * Returns `this` projection map, initializing the registry subscription on the first call.
     *
     * Implements Kotlin `by`-delegation:
     * `val byGenre: ObservableMap<String, GenreStats> by registryFxMultiKeyProjection(repo, keys, transform)`.
     */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): TransformedRegistryFxMultiKeyProjection<K, PK, E, V> {
        initialize()
        return this
    }
}
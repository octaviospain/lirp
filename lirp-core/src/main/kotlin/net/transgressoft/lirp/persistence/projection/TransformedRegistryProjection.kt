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

package net.transgressoft.lirp.persistence.projection

import net.transgressoft.lirp.entity.IdentifiableEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A read-only value-transformed view of a [RegistryProjection] that derives a `Map<PK, V>` by
 * applying a [valueTransform] to each bucket in the backing projection. The transform is re-run
 * only for buckets whose contents actually changed in a given delta, not for the entire map.
 *
 * This decorator wraps a [RegistryProjection] and registers on its `addOnBucketsChangedListener` signal to
 * maintain an internal transform cache. When a bucket is emptied and its key is removed from the
 * backing map, the corresponding key is also removed from this view — the transform is never called
 * over an empty list.
 *
 * By default, [entries], [keys], and [values] iterate in PK natural order. When [bucketValueOrdering]
 * is supplied, buckets are ordered value-primary (reading the cached transformed value — the transform
 * is never re-invoked inside the comparator), then by [bucketKeyOrdering] as a tiebreak, and finally
 * by PK natural order as the mandatory deterministic final tiebreak (preventing two distinct keys
 * whose values compare equal from being collapsed).
 *
 * Because the cache holds the previous transformed value per key, this decorator can report both the
 * old and the new value for every changed key. It therefore implements [ObservableProjection]:
 * [addOnEntriesChangedListener] emits batched [ProjectionEntryChange] deltas (add / replace / remove),
 * letting a consumer drive a CRUD-style event stream directly without keeping its own diff cache.
 * [close] is delegated to the backing [RegistryProjection], releasing the registry subscription.
 *
 * **Weak cross-key consistency:** Two consecutive `get()` calls for different keys are NOT
 * a single snapshot — they may observe different states of an ongoing delta. Iteration via
 * [entries], [keys], or [values] is CME-free (snapshot-based from the ordered index) but each
 * call represents a weakly consistent view. This inherits the same weakly-consistent contract of
 * the backing map's [java.util.concurrent.ConcurrentSkipListMap] iteration.
 *
 * **Multi-subscriber:** This decorator registers one listener via `addOnBucketsChangedListener` on
 * the backing [RegistryProjection]. Additional listeners registered on the same backing map are
 * independent and will each receive their own notifications — no registration clobbers another.
 *
 * The initial transform cache is built lazily on the first map access. The backing map fires
 * `addOnBucketsChangedListener` synchronously during its own initial seed on the seeding thread; that same-thread
 * re-entry is short-circuited (the seed loop captures those keys directly), while cross-thread events
 * recompute under the cache lock from a non-initializing bucket snapshot.
 *
 * @param K the entity ID type, must be [Comparable]
 * @param PK the projection key type, must be [Comparable]
 * @param E the entity type
 * @param V the value type produced by [valueTransform]
 * @param backing the underlying [RegistryProjection] whose buckets are transformed
 * @param bucketKeyOrdering optional comparator that orders buckets by their projection key; used as a
 *   tiebreak after [bucketValueOrdering] (when supplied) and before the mandatory PK natural-order
 *   final tiebreak. `null` skips key-level ordering beyond the PK tiebreak.
 * @param bucketValueOrdering optional comparator that orders buckets by their cached transformed value;
 *   the comparator reads the pre-computed `V` — it never re-invokes [valueTransform]. `null` skips
 *   value-primary ordering.
 * @param valueTransform function applied to each `(PK, List<E>)` bucket to produce a `V` value;
 *   invoked only for buckets whose contents changed in a given delta
 */
internal class TransformedRegistryProjection<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V : Any>(
    private val backing: RegistryProjection<K, PK, E>,
    bucketKeyOrdering: Comparator<PK>? = null,
    bucketValueOrdering: Comparator<V>? = null,
    private val valueTransform: (PK, List<E>) -> V
) : AbstractMap<PK, V>(), AutoCloseable by backing, ObservableProjection<PK, V> {

    private val log = KotlinLogging.logger {}

    // By-key O(1) lookup cache — keyed on PK, never on V.
    private val transformCache = ConcurrentHashMap<PK, V>()

    // Ordered read surface: a TreeMap whose comparator determines iteration order.
    // Protected by cacheLock for all structural mutations (insert / remove to reposition keys).
    // The comparator reads cached values from transformCache; it must only be consulted while
    // transformCache holds a stable value for every key present in orderedIndex.
    private val orderedIndex: TreeMap<PK, Unit> = TreeMap(bucketComparator(bucketValueOrdering, bucketKeyOrdering) { transformCache[it]!! })

    private val cacheLock = Any()

    @Volatile
    private var cacheInitialized = false

    @Volatile
    private var seedingThread: Thread? = null

    private val entriesChangedListeners = CopyOnWriteArrayList<(List<ProjectionEntryChange<PK, V>>) -> Unit>()

    init {
        backing.addOnBucketsChangedListener { changedKeys ->
            // The backing map fires this synchronously, on the seeding thread, while building its
            // initial state. Reading the backing map then would re-enter its lazy initialization and
            // recurse, so the same-thread synchronous re-entry short-circuits — the seed loop already
            // captures those keys. Cross-thread events (e.g. registry CRUD deliveries on a coroutine
            // thread) run the hook normally and are never dropped, because they block on cacheLock
            // until the seed completes and then recompute from the live bucket snapshot.
            if (Thread.currentThread() !== seedingThread) {
                // Mutate the cache and snapshot the recipient set under cacheLock so the cache
                // state and the listener set advance atomically; the user callbacks fire after the
                // lock is released so arbitrary listener code never runs while the lock is held.
                val recipients: List<(List<ProjectionEntryChange<PK, V>>) -> Unit>
                val changes =
                    synchronized(cacheLock) {
                        val built =
                            buildList<ProjectionEntryChange<PK, V>> {
                                for (key in changedKeys) {
                                    val bucket = backing.bucketSnapshot(key)
                                    val oldValue = transformCache[key]
                                    if (bucket == null) {
                                        if (oldValue != null) {
                                            // Remove from ordered index BEFORE cache update so the
                                            // comparator can still find the old position.
                                            orderedIndex.remove(key)
                                            transformCache.remove(key)
                                            add(ProjectionEntryChange(key, oldValue, null))
                                        }
                                    } else {
                                        val newValue = valueTransform(key, bucket)
                                        // Reposition in orderedIndex: remove at old position (while
                                        // cache still holds old value), update cache, re-insert at
                                        // new position (comparator now sees the new value). The remove
                                        // only applies to an existing key — for a brand-new key the
                                        // cache has no entry yet, so a value-ordering comparator would
                                        // dereference an absent value and throw during the removal walk.
                                        if (oldValue != null) orderedIndex.remove(key)
                                        transformCache[key] = newValue
                                        orderedIndex[key] = Unit
                                        if (newValue != oldValue) add(ProjectionEntryChange(key, oldValue, newValue))
                                    }
                                }
                            }
                        recipients = entriesChangedListeners.toList()
                        built
                    }
                if (changes.isNotEmpty()) notifyListeners(recipients, changes)
            }
        }
    }

    override fun addOnEntriesChangedListener(listener: (List<ProjectionEntryChange<PK, V>>) -> Unit): AutoCloseable {
        // Register the listener and snapshot the replay batch under cacheLock so a concurrent delta
        // cannot interleave between the snapshot and the registration: the delta blocks on cacheLock,
        // and because the listener is already in the set its delta is delivered strictly after this
        // replay. The replay itself fires after the lock is released so user code never runs under it.
        val initial: List<ProjectionEntryChange<PK, V>> =
            synchronized(cacheLock) {
                initializeCache()
                entriesChangedListeners.add(listener)
                // Snapshot ordered entries for the replay batch.
                orderedIndex.keys.mapNotNull { key -> transformCache[key]?.let { value -> ProjectionEntryChange(key, null, value) } }
            }
        if (initial.isNotEmpty()) notifyListeners(listOf(listener), initial)
        return AutoCloseable { entriesChangedListeners.remove(listener) }
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

    private fun initializeCache() {
        if (cacheInitialized) return
        synchronized(cacheLock) {
            if (cacheInitialized) return
            seedingThread = Thread.currentThread()
            try {
                for ((key, bucket) in backing) {
                    val value = valueTransform(key, bucket)
                    transformCache[key] = value
                    orderedIndex[key] = Unit
                }
            } finally {
                seedingThread = null
                cacheInitialized = true
            }
        }
    }

    // Snapshot the ordered keys under cacheLock to produce a stable, ordered set of entries.
    override val entries: Set<Map.Entry<PK, V>>
        get() {
            initializeCache()
            return synchronized(cacheLock) {
                orderedIndex.keys.mapNotNull { key ->
                    transformCache[key]?.let { value -> java.util.AbstractMap.SimpleImmutableEntry(key, value) }
                }.toLinkedHashSet()
            }
        }

    override val size: Int
        get() {
            initializeCache()
            return transformCache.size
        }

    override val keys: Set<PK>
        get() {
            initializeCache()
            return synchronized(cacheLock) { LinkedHashSet(orderedIndex.keys) }
        }

    override val values: Collection<V>
        get() {
            initializeCache()
            return synchronized(cacheLock) {
                orderedIndex.keys.mapNotNull { transformCache[it] }
            }
        }

    override fun get(key: PK): V? {
        initializeCache()
        return transformCache[key]
    }

    override fun containsKey(key: PK): Boolean {
        initializeCache()
        return transformCache.containsKey(key)
    }

    override fun containsValue(value: V): Boolean {
        initializeCache()
        return transformCache.containsValue(value)
    }

    override fun isEmpty(): Boolean {
        initializeCache()
        return transformCache.isEmpty()
    }
}

private fun <T> List<T>.toLinkedHashSet(): LinkedHashSet<T> = LinkedHashSet(this)
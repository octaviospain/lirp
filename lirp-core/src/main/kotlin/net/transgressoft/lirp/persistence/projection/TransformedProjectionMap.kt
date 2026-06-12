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
import java.util.concurrent.ConcurrentHashMap

/**
 * A read-only value-transformed view of a [ProjectionMap] that derives a `Map<PK, V>` by applying
 * a [valueTransform] to each bucket in the backing projection. The transform is re-run only for
 * buckets whose contents actually changed in a given delta, not for the entire map.
 *
 * This decorator wraps a [ProjectionMap] and registers on its `addOnBucketsChangedListener` signal to
 * maintain an internal `ConcurrentHashMap<PK, V>` transform cache. When a bucket is emptied
 * and its key is removed from the backing map, the corresponding key is also removed from this
 * view — the transform is never called over an empty list.
 *
 * **Weak cross-key consistency:** Two consecutive `get()` calls for different keys are NOT
 * a single snapshot — they may observe different states of an ongoing delta. Iteration via
 * [entries], [keys], or [values] is CME-free (backed by [ConcurrentHashMap]) but each call to
 * the underlying iterator represents a weakly consistent view. This inherits the same
 * weakly-consistent contract of the backing map's [java.util.concurrent.ConcurrentSkipListMap] iteration.
 *
 * **Multi-subscriber:** This decorator registers one listener via `addOnBucketsChangedListener` on
 * the backing [ProjectionMap]. Additional listeners registered on the same backing map are
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
 * @param backing the underlying [ProjectionMap] whose buckets are transformed
 * @param valueTransform function applied to each `(PK, List<E>)` bucket to produce a `V` value;
 *   invoked only for buckets whose contents changed in a given delta
 */
internal class TransformedProjectionMap<K : Comparable<K>, PK : Comparable<PK>, E : IdentifiableEntity<K>, V>(
    private val backing: ProjectionMap<K, PK, E>,
    private val valueTransform: (PK, List<E>) -> V
) : AbstractMap<PK, V>() {

    private val transformCache = ConcurrentHashMap<PK, V>()

    private val cacheLock = Any()

    @Volatile
    private var cacheInitialized = false

    @Volatile
    private var seedingThread: Thread? = null

    init {
        backing.addOnBucketsChangedListener { changedKeys ->
            // The backing map fires this synchronously, on the seeding thread, while building its
            // initial state. Reading the backing map then would re-enter its lazy initialization and
            // recurse, so the same-thread synchronous re-entry short-circuits — the seed loop already
            // captures those keys. Cross-thread events (e.g. registry CRUD deliveries on a coroutine
            // thread) run the hook normally and are never dropped, because they block on cacheLock
            // until the seed completes and then recompute from the live bucket snapshot.
            if (Thread.currentThread() !== seedingThread) {
                synchronized(cacheLock) {
                    for (key in changedKeys) {
                        val bucket = backing.bucketSnapshot(key)
                        if (bucket == null) transformCache.remove(key)
                        else transformCache[key] = valueTransform(key, bucket)
                    }
                }
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
                    transformCache[key] = valueTransform(key, bucket)
                }
            } finally {
                seedingThread = null
                cacheInitialized = true
            }
        }
    }

    override val entries: Set<Map.Entry<PK, V>>
        get() {
            initializeCache()
            return transformCache.entries
        }

    override val size: Int
        get() {
            initializeCache()
            return transformCache.size
        }

    override val keys: Set<PK>
        get() {
            initializeCache()
            return transformCache.keys
        }

    override val values: Collection<V>
        get() {
            initializeCache()
            return transformCache.values
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
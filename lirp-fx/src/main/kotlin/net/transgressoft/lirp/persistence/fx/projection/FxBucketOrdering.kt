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

/**
 * Builds a composite bucket comparator over projection keys (`PK`) for use as a
 * [java.util.concurrent.ConcurrentSkipListMap] comparator on the FX observable backing.
 *
 * The composition order is:
 * 1. **Value-primary** — when [bucketValueOrdering] is supplied, buckets are compared first by
 *    the cached transformed value obtained via [valueOf]. The comparator reads the pre-computed
 *    value; it never invokes any transform.
 * 2. **Key tiebreak** — when [bucketKeyOrdering] is supplied, buckets that compare equal under
 *    [bucketValueOrdering] (or when [bucketValueOrdering] is absent) are further ordered by the
 *    bucket key itself.
 * 3. **PK natural-order final tiebreak** — `Comparator.naturalOrder<PK>()` is always appended.
 *    This is mandatory for correctness: a coarse comparator may return `0` for two distinct
 *    bucket keys; without the PK tiebreak, the ordered map would collapse those two distinct
 *    keys into one, silently dropping a bucket.
 *
 * @param PK the projection key type; must be [Comparable]
 * @param V the transformed value type; only used when [bucketValueOrdering] is non-null
 * @param bucketValueOrdering optional comparator that orders buckets by their transformed value;
 *   used with [valueOf] to compare the cached `V` for two keys. `null` skips value-primary ordering.
 * @param bucketKeyOrdering optional comparator that orders buckets by the bucket key itself.
 *   `null` skips key-level ordering (beyond the mandatory PK final tiebreak).
 * @param valueOf accessor that returns the cached transformed value for a given bucket key;
 *   called inside the returned comparator — must read a cached result, never recompute a transform.
 * @return a [Comparator]<PK> resolving to a stable total order over all distinct bucket keys
 */
internal fun <PK : Comparable<PK>, V : Any> buildBucketComparator(
    bucketValueOrdering: Comparator<V>?,
    bucketKeyOrdering: Comparator<PK>?,
    valueOf: (PK) -> V?
): Comparator<PK> {
    var cmp: Comparator<PK>? = null
    if (bucketValueOrdering != null) {
        cmp =
            Comparator { a, b ->
                val va = valueOf(a)
                val vb = valueOf(b)
                when {
                    va == null && vb == null -> 0
                    va == null -> -1
                    vb == null -> 1
                    else -> bucketValueOrdering.compare(va, vb)
                }
            }
    }
    if (bucketKeyOrdering != null) {
        cmp = cmp?.thenComparing(bucketKeyOrdering) ?: bucketKeyOrdering
    }
    val natural = Comparator.naturalOrder<PK>()
    return cmp?.thenComparing(natural) ?: natural
}
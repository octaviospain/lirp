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

/**
 * Builds a composite bucket comparator over projection keys (`PK`) that resolves
 * to a stable total order suitable for use as a [java.util.concurrent.ConcurrentSkipListMap] comparator.
 *
 * The composition order is:
 * 1. **Value-primary** — when [bucketValueOrdering] is supplied, buckets are compared first by
 *    the cached transformed value obtained via [valueOf]. The comparator reads the pre-computed
 *    value; it does not invoke any transform.
 * 2. **Key tiebreak** — when [bucketKeyOrdering] is supplied, buckets that compare equal under
 *    [bucketValueOrdering] (or when [bucketValueOrdering] is absent) are further ordered by the
 *    bucket key itself.
 * 3. **PK natural-order final tiebreak** — `Comparator.naturalOrder<PK>()` is always appended
 *    as the last tiebreak. This is mandatory for correctness: a coarse [bucketValueOrdering] or
 *    [bucketKeyOrdering] may return `0` for two distinct bucket keys; without the PK tiebreak,
 *    an ordered map would collapse those two distinct keys into one, silently dropping a bucket.
 *    The `PK : Comparable<PK>` bound already imposed by all registry factories is the contract
 *    — no additional comparator parameter is required.
 *
 * Either or both of [bucketValueOrdering] and [bucketKeyOrdering] may be `null`. When both are
 * null the returned comparator is simply `Comparator.naturalOrder<PK>()`, matching the default
 * PK-natural backing order of non-ordered projections.
 *
 * @param PK the projection key (bucket key) type; must be [Comparable]
 * @param V the transformed value type; only used when [bucketValueOrdering] is non-null
 * @param bucketValueOrdering optional comparator that orders buckets by their transformed value;
 *   used with [valueOf] to compare the cached `V` for two keys. `null` skips value-primary ordering.
 * @param bucketKeyOrdering optional comparator that orders buckets by the bucket key itself.
 *   `null` skips key-level ordering (beyond the mandatory PK final tiebreak).
 * @param valueOf accessor that returns the cached transformed value for a given bucket key;
 *   called inside the returned comparator, so it must be fast and must read a cached result —
 *   never recompute a transform inside this call.
 * @return a [Comparator]<PK> that resolves to a stable total order over all distinct bucket keys
 */
internal fun <PK : Comparable<PK>, V : Any> bucketComparator(
    bucketValueOrdering: Comparator<V>?,
    bucketKeyOrdering: Comparator<PK>?,
    valueOf: (PK) -> V
): Comparator<PK> {
    var cmp: Comparator<PK>? = null
    if (bucketValueOrdering != null) {
        cmp = Comparator { a, b -> bucketValueOrdering.compare(valueOf(a), valueOf(b)) }
    }
    if (bucketKeyOrdering != null) {
        cmp = cmp?.thenComparing(bucketKeyOrdering) ?: bucketKeyOrdering
    }
    // Mandatory final tiebreak on PK uniqueness — guarantees a stable total order so two
    // distinct buckets that compare equal on value or key do not collide or drop.
    val natural = Comparator.naturalOrder<PK>()
    return cmp?.thenComparing(natural) ?: natural
}
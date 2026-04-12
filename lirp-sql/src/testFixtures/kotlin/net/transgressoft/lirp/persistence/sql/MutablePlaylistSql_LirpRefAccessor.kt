package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.CollectionRefEntry
import net.transgressoft.lirp.persistence.LirpRefAccessor
import net.transgressoft.lirp.persistence.RefEntry

/**
 * Manual [LirpRefAccessor] for [MutablePlaylistSql], equivalent to the KSP-generated accessor.
 *
 * Required because the `lirp-sql` module does not apply the `lirp-ksp` processor, yet
 * [MutablePlaylistSql] uses a [mutableAggregateList] delegate that triggers the fail-fast
 * check in [RegistryBase.discoverRefs].
 */
@Suppress("ktlint:standard:class-naming")
@SuppressWarnings("kotlin:S101")
class MutablePlaylistSql_LirpRefAccessor : LirpRefAccessor<MutablePlaylistSql> {
    override val entries: List<RefEntry<*, MutablePlaylistSql>> = emptyList()

    override val collectionEntries: List<CollectionRefEntry<*, MutablePlaylistSql>> =
        listOf(
            @Suppress("UNCHECKED_CAST")
            CollectionRefEntry(
                refName = "tracks",
                idsGetter = { it.tracks.referenceIds },
                delegateGetter = { it.tracks as AggregateCollectionRef<*, *> },
                referencedClass = SqlTestTrack::class.java,
                cascadeAction = CascadeAction.NONE,
                isOrdered = true
            )
        )

    override fun cancelAllBubbleUp(entity: MutablePlaylistSql) {
        entries.forEach { entry -> entry.delegateGetter(entity).cancelBubbleUp() }
    }
}
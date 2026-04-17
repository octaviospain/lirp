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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Aggregate
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.LirpRegistryInfo
import net.transgressoft.lirp.persistence.mutableAggregateList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * Minimal entity referenced by [MutablePlaylistSql]'s aggregate delegate.
 *
 * Lives in the same [LirpContext][net.transgressoft.lirp.persistence.LirpContext] so the mutable
 * aggregate delegate can resolve IDs to entity instances at runtime.
 */
class SqlTestTrack(override val id: Int, title: String) : ReactiveEntityBase<Int, SqlTestTrack>() {
    var title: String by reactiveProperty(title)
    override val uniqueId: String get() = "sql-track-$id"

    override fun clone(): SqlTestTrack = SqlTestTrack(id, title)
}

/**
 * SQL table definition for [SqlTestTrack].
 */
object SqlTestTrackTableDef : SqlTableDef<SqlTestTrack> {
    override val tableName = "sql_test_tracks"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("title", ColumnType.VarcharType(200), nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): SqlTestTrack {
        val cols = table.columns.associateBy { it.name }
        return SqlTestTrack(
            row[cols["id"]!! as Column<Int>],
            row[cols["title"]!! as Column<String>]
        )
    }

    override fun toParams(entity: SqlTestTrack, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["title"]!! to entity.title
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: SqlTestTrack, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.title = row[cols["title"]!! as Column<String>]
        // id is PK — immutable on SqlTestTrack
    }
}

/**
 * Test entity with a [mutableAggregateList] delegate whose backing ID field ([trackIds]) is stored
 * as a comma-separated TEXT column in SQL. Demonstrates mutable aggregate round-trip through the
 * SQL persistence pipeline.
 *
 * The [clone] method deep-copies [trackIds] to satisfy the `mutateAndPublish` equality check.
 */
class MutablePlaylistSql(
    override val id: Long,
    initialTrackIds: List<Int> = emptyList()
) : ReactiveEntityBase<Long, MutablePlaylistSql>() {
    var name: String by reactiveProperty("")
    var trackIds: List<Int> = initialTrackIds

    @Aggregate(onDelete = CascadeAction.NONE)
    val tracks by mutableAggregateList<Int, SqlTestTrack>(trackIds)

    override val uniqueId: String get() = "mutable-playlist-sql-$id"

    override fun clone(): MutablePlaylistSql =
        MutablePlaylistSql(id, tracks.referenceIds.toList()).also { copy ->
            copy.withEventsDisabled {
                copy.name = name
            }
        }
}

/**
 * SQL table definition for [MutablePlaylistSql], storing [MutablePlaylistSql.trackIds] as a
 * comma-separated TEXT column for round-trip persistence of the mutable aggregate backing field.
 */
object MutablePlaylistSqlTableDef : SqlTableDef<MutablePlaylistSql> {
    override val tableName = "mutable_playlists"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.LongType, nullable = false, primaryKey = true),
            ColumnDef("name", ColumnType.VarcharType(200), nullable = false, primaryKey = false),
            ColumnDef("track_ids", ColumnType.TextType, nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): MutablePlaylistSql {
        val cols = table.columns.associateBy { it.name }
        val trackIdsText = row[cols["track_ids"]!! as Column<String>]
        val parsedIds =
            if (trackIdsText.isBlank()) emptyList()
            else trackIdsText.split(",").mapNotNull { it.trim().toIntOrNull() }

        return MutablePlaylistSql(row[cols["id"]!! as Column<Long>], parsedIds).also {
            it.name = row[cols["name"]!! as Column<String>]
        }
    }

    override fun toParams(entity: MutablePlaylistSql, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["name"]!! to entity.name,
            cols["track_ids"]!! to entity.tracks.referenceIds.joinToString(",")
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: MutablePlaylistSql, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.name = row[cols["name"]!! as Column<String>]
        // id is PK — immutable. track_ids is backed by the `tracks` mutableAggregateList
        // delegate (val), whose collection state is managed via the delegate's mutation API —
        // not applicable to applyRow (the delegate is constructed once at fromRow time).
        //
        // Reload safety: [MutablePlaylistSql] has no `@Version` annotation and therefore never
        // triggers the optimistic-lock reload path where this method is invoked. If a `@Version`
        // is ever added to this entity (or if `applyRow` is invoked from a future "reload from
        // DB" operation), the in-memory `tracks` collection would silently diverge from the DB
        // row. In that case, reconcile the delegate's `referenceIds` here (clear + addAll inside
        // `entity.withEventsDisabled { ... }`) before claiming `@Version` support on this entity.
    }
}

/**
 * Named [SqlRepository] subclass for [SqlTestTrack] entities, enabling auto-registration in
 * [LirpContext][net.transgressoft.lirp.persistence.LirpContext] via the convention-based
 * `_LirpRegistryInfo` lookup.
 */
class SqlTestTrackRepository(jdbcUrl: String) : SqlRepository<Int, SqlTestTrack>(jdbcUrl, SqlTestTrackTableDef)

/** Manual [LirpRegistryInfo] for [SqlTestTrackRepository] (equivalent to KSP-generated). */
@Suppress("ClassName")
class `SqlTestTrackRepository_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = SqlTestTrack::class.java
}

/**
 * Named [SqlRepository] subclass for [MutablePlaylistSql] entities, enabling auto-registration in
 * [LirpContext][net.transgressoft.lirp.persistence.LirpContext] via the convention-based
 * `_LirpRegistryInfo` lookup.
 */
class MutablePlaylistSqlRepository(jdbcUrl: String) :
    SqlRepository<Long, MutablePlaylistSql>(jdbcUrl, MutablePlaylistSqlTableDef)

/** Manual [LirpRegistryInfo] for [MutablePlaylistSqlRepository] (equivalent to KSP-generated). */
@Suppress("ClassName")
class `MutablePlaylistSqlRepository_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = MutablePlaylistSql::class.java
}
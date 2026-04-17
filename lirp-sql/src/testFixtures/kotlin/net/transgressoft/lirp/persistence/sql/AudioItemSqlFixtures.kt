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

import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.DefaultAudioPlaylist
import net.transgressoft.lirp.persistence.LirpRegistryInfo
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.MutableAudioPlaylist
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * SQL table definition for [AudioItem], mapping the [id], [title], and [albumName] fields
 * to their respective SQL columns.
 */
object AudioItemSqlTableDef : SqlTableDef<AudioItem> {
    override val tableName = "audio_items"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("title", ColumnType.VarcharType(500), nullable = false, primaryKey = false),
            ColumnDef("album_name", ColumnType.VarcharType(255), nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): AudioItem {
        val cols = table.columns.associateBy { it.name }
        return MutableAudioItem(
            row[cols["id"]!! as Column<Int>],
            row[cols["title"]!! as Column<String>],
            row[cols["album_name"]!! as Column<String>]
        )
    }

    override fun toParams(entity: AudioItem, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["title"]!! to entity.title,
            cols["album_name"]!! to entity.albumName
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: AudioItem, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.title = row[cols["title"]!! as Column<String>]
        entity.albumName = row[cols["album_name"]!! as Column<String>]
        // id is PK — immutable on AudioItem
    }
}

/**
 * SQL table definition for [MutableAudioPlaylist], storing aggregate reference IDs
 * ([audioItems] and [playlists]) as comma-separated TEXT columns for round-trip persistence.
 *
 * Both [audio_item_ids] and [playlist_ids] columns are serialized as CSV strings and parsed back
 * into [List] and [Set] of [Int] on read.
 */
object AudioPlaylistSqlTableDef : SqlTableDef<MutableAudioPlaylist> {
    override val tableName = "audio_playlists"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("name", ColumnType.VarcharType(500), nullable = false, primaryKey = false),
            ColumnDef("audio_item_ids", ColumnType.TextType, nullable = false, primaryKey = false),
            ColumnDef("playlist_ids", ColumnType.TextType, nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): MutableAudioPlaylist {
        val cols = table.columns.associateBy { it.name }
        val audioItemIdsText = row[cols["audio_item_ids"]!! as Column<String>]
        val parsedAudioItemIds =
            if (audioItemIdsText.isBlank()) emptyList()
            else audioItemIdsText.split(",").mapNotNull { it.trim().toIntOrNull() }
        val playlistIdsText = row[cols["playlist_ids"]!! as Column<String>]
        val parsedPlaylistIds =
            if (playlistIdsText.isBlank()) emptySet()
            else playlistIdsText.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        return DefaultAudioPlaylist(row[cols["id"]!! as Column<Int>], row[cols["name"]!! as Column<String>], parsedAudioItemIds, parsedPlaylistIds)
    }

    override fun toParams(entity: MutableAudioPlaylist, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        val concrete = entity as DefaultAudioPlaylist
        return mapOf(
            cols["id"]!! to entity.id,
            cols["name"]!! to entity.name,
            cols["audio_item_ids"]!! to concrete.audioItems.referenceIds.joinToString(","),
            cols["playlist_ids"]!! to concrete.playlists.referenceIds.joinToString(",")
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: MutableAudioPlaylist, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.name = row[cols["name"]!! as Column<String>]
        // id is PK — immutable. audio_item_ids/playlist_ids are backed by aggregate delegates
        // (val), so the collection state is managed via the aggregate delegate's mutation API —
        // not applicable to applyRow (the delegate is constructed once at fromRow time).
    }
}

/**
 * Named [SqlRepository] subclass for [AudioItem] entities, enabling auto-registration in
 * [LirpContext][net.transgressoft.lirp.persistence.LirpContext] via the convention-based
 * `_LirpRegistryInfo` lookup.
 */
class AudioItemSqlRepository(dataSource: HikariDataSource) :
    SqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)

/** Manual [LirpRegistryInfo] for [AudioItemSqlRepository] (equivalent to KSP-generated). */
@Suppress("ClassName")
class `AudioItemSqlRepository_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = AudioItem::class.java
}

/**
 * Named [SqlRepository] subclass for [MutableAudioPlaylist] entities, enabling auto-registration
 * in [LirpContext][net.transgressoft.lirp.persistence.LirpContext] via the convention-based
 * `_LirpRegistryInfo` lookup.
 */
class AudioPlaylistSqlRepository(dataSource: HikariDataSource) :
    SqlRepository<Int, MutableAudioPlaylist>(dataSource, AudioPlaylistSqlTableDef)

/** Manual [LirpRegistryInfo] for [AudioPlaylistSqlRepository] (equivalent to KSP-generated). */
@Suppress("ClassName")
class `AudioPlaylistSqlRepository_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = MutableAudioPlaylist::class.java
}
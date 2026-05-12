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

import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * Raw-JDBC helpers for code that needs to bind or read
 * [net.transgressoft.lirp.persistence.ColumnType.UuidType] columns outside Exposed.
 *
 * | Dialect | Storage | JDBC bind/read |
 * |---------|---------|----------------|
 * | H2 / PostgreSQL | native `UUID` | `setObject(i, uuid)` / `getObject(c, UUID::class.java)` |
 * | MySQL / MariaDB | `BINARY(16)` | `setBytes(i, bigEndianBytes)` / `getBytes(c)` |
 * | SQLite | `BLOB(16)` | `setBytes(i, bigEndianBytes)` / `getBytes(c)` |
 *
 * Keeping this dispatch here lets migration scripts, reporting jobs, and manual JDBC tools
 * use the same UUID representation as LIRP-managed tables without depending on Exposed internals.
 * Canonical hex strings are intentionally avoided because byte-backed UUID columns compare
 * against their binary representation, not their textual form.
 */
public fun PreparedStatement.bindUuid(index: Int, uuid: UUID, connection: Connection) {
    val productName = connection.metaData.databaseProductName
    when (productName) {
        "H2", "PostgreSQL" -> setObject(index, uuid)
        "MySQL", "MariaDB", "SQLite" -> setBytes(index, uuid.toBytesBigEndian())
        else -> error("UUID bind not supported for dialect '$productName'. Supported: H2, PostgreSQL, MySQL, MariaDB, SQLite.")
    }
}

/**
 * Reads a UUID using the same dialect-specific representation as [bindUuid].
 *
 * Native UUID dialects require `wasNull()` after typed reads; byte-backed dialects return
 * `null` directly from `getBytes`. The read path mirrors the write path so callers do not
 * need to know whether the underlying column stores native UUIDs or 16-byte values.
 */
public fun ResultSet.readUuid(columnIndex: Int, connection: Connection): UUID? {
    val productName = connection.metaData.databaseProductName
    return when (productName) {
        "H2", "PostgreSQL" -> {
            val value = getObject(columnIndex, UUID::class.java)
            if (wasNull()) null else value
        }
        "MySQL", "MariaDB", "SQLite" -> getBytes(columnIndex)?.toUuidBigEndian()
        else -> error("UUID read not supported for dialect '$productName'. Supported: H2, PostgreSQL, MySQL, MariaDB, SQLite.")
    }
}

/**
 * Reads a UUID by column label using the same dialect-specific representation as [bindUuid].
 *
 * This overload mirrors [ResultSet]'s own index and label accessors so callers do not need to
 * resolve column positions before reading UUID columns.
 */
public fun ResultSet.readUuid(columnLabel: String, connection: Connection): UUID? {
    val productName = connection.metaData.databaseProductName
    return when (productName) {
        "H2", "PostgreSQL" -> {
            val value = getObject(columnLabel, UUID::class.java)
            if (wasNull()) null else value
        }
        "MySQL", "MariaDB", "SQLite" -> getBytes(columnLabel)?.toUuidBigEndian()
        else -> error("UUID read not supported for dialect '$productName'. Supported: H2, PostgreSQL, MySQL, MariaDB, SQLite.")
    }
}

private fun UUID.toBytesBigEndian(): ByteArray =
    ByteBuffer.allocate(16)
        .putLong(mostSignificantBits)
        .putLong(leastSignificantBits)
        .array()

private fun ByteArray.toUuidBigEndian(): UUID {
    require(size == 16) { "UUID byte array must be exactly 16 bytes, got $size" }
    return ByteBuffer.wrap(this).let { UUID(it.long, it.long) }
}
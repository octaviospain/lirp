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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

internal class SqlitePragmaAssertionIntegrationTest : StringSpec({

    "connectionInitSql enables foreign-key enforcement on every pooled connection" {
        val ds = SqliteFileSupport.buildDataSource()
        try {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("PRAGMA foreign_keys;")
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
        } finally {
            ds.close()
        }
    }

    "connectionInitSql sets WAL journal mode on file-backed databases" {
        val ds = SqliteFileSupport.buildDataSource()
        try {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("PRAGMA journal_mode;")
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe "wal"
                }
            }
        } finally {
            ds.close()
        }
    }
})
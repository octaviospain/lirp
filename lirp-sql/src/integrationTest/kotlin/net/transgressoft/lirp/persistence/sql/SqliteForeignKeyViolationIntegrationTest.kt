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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.sqlite.SQLiteException
import java.sql.SQLException

internal class SqliteForeignKeyViolationIntegrationTest : StringSpec({

    "orphan child insert with PRAGMA foreign_keys=ON throws SQLITE_CONSTRAINT_FOREIGNKEY" {
        val ds = SqliteFileSupport.buildDataSource()
        try {
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)")
                    stmt.execute(
                        "CREATE TABLE child (" +
                            "id INTEGER PRIMARY KEY, " +
                            "parent_id INTEGER NOT NULL REFERENCES parent(id) ON DELETE RESTRICT" +
                            ")"
                    )
                    stmt.execute("INSERT INTO parent (id) VALUES (1)")
                }

                val ex =
                    shouldThrow<SQLException> {
                        conn.createStatement().use { stmt ->
                            // parent_id=999 has no matching parent row → FK violation
                            stmt.execute("INSERT INTO child (id, parent_id) VALUES (1, 999)")
                        }
                    }

                // SQLState is intentionally NOT asserted — sqlite-jdbc passes null SQLState.
                ex.shouldBeInstanceOf<SQLiteException>()
                // SQLITE_CONSTRAINT base code; sqlite-jdbc masks the extended 787 code to 19.
                ex.errorCode shouldBe 19
                ex.message!! shouldContain "FOREIGN KEY constraint failed"
            }
        } finally {
            ds.close()
        }
    }
})
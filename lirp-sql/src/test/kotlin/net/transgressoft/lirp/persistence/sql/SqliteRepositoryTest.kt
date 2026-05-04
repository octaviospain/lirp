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

import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

internal class SqliteRepositoryTest : StringSpec({

    "inMemory factory builds a JDBC URL containing :memory:" {
        val repo = SqliteRepository.inMemory(TestPersonTableDef)
        try {
            val dataSourceField = SqlRepository::class.java.getDeclaredField("dataSource")
            dataSourceField.isAccessible = true
            val hikariDs = dataSourceField.get(repo) as HikariDataSource
            hikariDs.jdbcUrl shouldContain ":memory:"
        } finally {
            repo.close()
        }
    }

    "inMemory factory pins maximumPoolSize to 1" {
        val repo = SqliteRepository.inMemory(TestPersonTableDef)
        try {
            val dataSourceField = SqlRepository::class.java.getDeclaredField("dataSource")
            dataSourceField.isAccessible = true
            val hikariDs = dataSourceField.get(repo) as HikariDataSource
            hikariDs.maximumPoolSize shouldBe 1
        } finally {
            repo.close()
        }
    }
})
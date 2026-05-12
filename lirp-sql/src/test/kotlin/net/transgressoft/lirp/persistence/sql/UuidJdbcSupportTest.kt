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
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

/**
 * Unit coverage for [bindUuid] and [readUuid] dialect dispatch.
 */
internal class UuidJdbcSupportTest : FunSpec({

    context("PreparedStatement.bindUuid uses native UUID binding for native dialects") {
        withTests("H2", "PostgreSQL") { dialect ->
            val uuid = UUID.randomUUID()
            val recorder = PreparedStatementRecorder()

            recorder.statement.bindUuid(1, uuid, connectionFor(dialect))

            recorder.objectValue shouldBe uuid
            recorder.bytesValue.shouldBeNull()
        }
    }

    context("PreparedStatement.bindUuid uses big-endian bytes for byte-backed dialects") {
        withTests("MySQL", "MariaDB", "SQLite") { dialect ->
            val uuid = UUID.randomUUID()
            val recorder = PreparedStatementRecorder()

            recorder.statement.bindUuid(1, uuid, connectionFor(dialect))

            recorder.objectValue.shouldBeNull()
            recorder.bytesValue!!.toList() shouldBe uuid.toExpectedBytes().toList()
        }
    }

    test("PreparedStatement.bindUuid throws for unknown dialect") {
        val ex =
            shouldThrow<IllegalStateException> {
                PreparedStatementRecorder().statement.bindUuid(1, UUID.randomUUID(), connectionFor("UnknownDB"))
            }

        ex.message shouldContain "UUID bind not supported for dialect 'UnknownDB'"
    }

    test("PreparedStatement.bindUuid throws for null dialect") {
        val ex =
            shouldThrow<IllegalStateException> {
                PreparedStatementRecorder().statement.bindUuid(1, UUID.randomUUID(), connectionFor(null))
            }

        ex.message shouldContain "UUID bind not supported for dialect 'null'"
    }

    context("ResultSet.readUuid returns native UUID values for native dialects") {
        withTests("H2", "PostgreSQL") { dialect ->
            val uuid = UUID.randomUUID()
            val resultSet = resultSetFor(objectValue = uuid, bytesValue = null, wasNull = false)

            resultSet.readUuid(1, connectionFor(dialect)) shouldBe uuid
            resultSet.readUuid("id", connectionFor(dialect)) shouldBe uuid
        }
    }

    context("ResultSet.readUuid returns byte-backed UUID values for byte-backed dialects") {
        withTests("MySQL", "MariaDB", "SQLite") { dialect ->
            val uuid = UUID.randomUUID()
            val resultSet = resultSetFor(objectValue = null, bytesValue = uuid.toExpectedBytes(), wasNull = false)

            resultSet.readUuid(1, connectionFor(dialect)) shouldBe uuid
            resultSet.readUuid("id", connectionFor(dialect)) shouldBe uuid
        }
    }

    test("ResultSet.readUuid returns null for native SQL NULL") {
        val resultSet = resultSetFor(objectValue = null, bytesValue = null, wasNull = true)

        resultSet.readUuid(1, connectionFor("H2")).shouldBeNull()
    }

    test("ResultSet.readUuid by label returns null for native SQL NULL") {
        val resultSet = resultSetFor(objectValue = null, bytesValue = null, wasNull = true)

        resultSet.readUuid("id", connectionFor("H2")).shouldBeNull()
    }

    test("ResultSet.readUuid returns null for byte-backed SQL NULL") {
        val resultSet = resultSetFor(objectValue = null, bytesValue = null, wasNull = true)

        resultSet.readUuid(1, connectionFor("SQLite")).shouldBeNull()
    }

    test("ResultSet.readUuid by label returns null for byte-backed SQL NULL") {
        val resultSet = resultSetFor(objectValue = null, bytesValue = null, wasNull = true)

        resultSet.readUuid("id", connectionFor("SQLite")).shouldBeNull()
    }

    test("ResultSet.readUuid throws for unknown dialect") {
        val ex =
            shouldThrow<IllegalStateException> {
                resultSetFor(objectValue = null, bytesValue = null, wasNull = false)
                    .readUuid(1, connectionFor("UnknownDB"))
            }

        ex.message shouldContain "UUID read not supported for dialect 'UnknownDB'"
    }

    test("ResultSet.readUuid by label throws for unknown dialect") {
        val ex =
            shouldThrow<IllegalStateException> {
                resultSetFor(objectValue = null, bytesValue = null, wasNull = false)
                    .readUuid("id", connectionFor("UnknownDB"))
            }

        ex.message shouldContain "UUID read not supported for dialect 'UnknownDB'"
    }

    test("ResultSet.readUuid throws for null dialect") {
        val ex =
            shouldThrow<IllegalStateException> {
                resultSetFor(objectValue = null, bytesValue = null, wasNull = false)
                    .readUuid(1, connectionFor(null))
            }

        ex.message shouldContain "UUID read not supported for dialect 'null'"
    }

    test("ResultSet.readUuid rejects malformed byte arrays") {
        val resultSet = resultSetFor(objectValue = null, bytesValue = ByteArray(15), wasNull = false)

        val ex =
            shouldThrow<IllegalArgumentException> {
                resultSet.readUuid(1, connectionFor("SQLite"))
            }

        ex.message shouldContain "UUID byte array must be exactly 16 bytes"
    }

    test("ResultSet.readUuid by label rejects malformed byte arrays") {
        val resultSet = resultSetFor(objectValue = null, bytesValue = ByteArray(15), wasNull = false)

        val ex =
            shouldThrow<IllegalArgumentException> {
                resultSet.readUuid("id", connectionFor("SQLite"))
            }

        ex.message shouldContain "UUID byte array must be exactly 16 bytes"
    }
})

data class PreparedStatementRecorder(
    var objectValue: Any? = null,
    var bytesValue: ByteArray? = null
) {
    val statement: PreparedStatement =
        proxy(PreparedStatement::class.java) { method, args ->
            when (method.name) {
                "setObject" -> {
                    objectValue = args!![1]
                    null
                }
                "setBytes" -> {
                    bytesValue = args!![1] as ByteArray
                    null
                }
                else -> unsupported(method)
            }
        }
}

fun connectionFor(productName: String?): Connection {
    val metadata =
        proxy(DatabaseMetaData::class.java) { method, _ ->
            when (method.name) {
                "getDatabaseProductName" -> productName
                else -> unsupported(method)
            }
        }
    return proxy(Connection::class.java) { method, _ ->
        when (method.name) {
            "getMetaData" -> metadata
            else -> unsupported(method)
        }
    }
}

fun resultSetFor(objectValue: UUID?, bytesValue: ByteArray?, wasNull: Boolean): ResultSet =
    proxy(ResultSet::class.java) { method, _ ->
        when (method.name) {
            "getObject" -> objectValue
            "getBytes" -> bytesValue
            "wasNull" -> wasNull
            else -> unsupported(method)
        }
    }

fun UUID.toExpectedBytes(): ByteArray =
    ByteBuffer.allocate(16)
        .putLong(mostSignificantBits)
        .putLong(leastSignificantBits)
        .array()

fun <T> proxy(type: Class<T>, handler: (Method, Array<Any?>?) -> Any?): T {
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        when (method.name) {
            "toString" -> "Proxy(${type.simpleName})"
            "hashCode" -> 0
            "equals" -> false
            else -> handler(method, args)
        }
    } as T
}

fun unsupported(method: Method): Nothing =
    error("Unexpected JDBC call: ${method.name}")
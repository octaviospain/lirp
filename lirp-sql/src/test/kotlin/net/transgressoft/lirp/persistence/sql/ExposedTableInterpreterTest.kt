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

import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.LirpTableDef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.FloatColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.datetime.KotlinLocalDateColumnType
import org.jetbrains.exposed.v1.datetime.KotlinLocalDateTimeColumnType
import org.junit.jupiter.api.DisplayName
import kotlin.uuid.ExperimentalUuidApi

/**
 * Unit tests for [ExposedTableInterpreter] verifying that each [ColumnType] variant is correctly mapped
 * to the corresponding JetBrains Exposed column type without requiring a live database connection.
 */
@OptIn(ExperimentalUuidApi::class)
@DisplayName("ExposedTableInterpreter")
internal class ExposedTableInterpreterTest : FunSpec({

    val interpreter = ExposedTableInterpreter()

    fun singleColumnDef(def: ColumnDef, tableName: String = "test_table"): ExposedTable {
        val tableDef =
            object : LirpTableDef<Any> {
                override val tableName = tableName
                override val columns = listOf(def)
            }
        return interpreter.interpret(tableDef)
    }

    test("creates table with correct table name from LirpTableDef") {
        val def =
            object : LirpTableDef<Any> {
                override val tableName = "my_entities"
                override val columns = listOf(ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true))
            }
        val result = interpreter.interpret(def)
        result.table.tableName shouldBe "my_entities"
    }

    test("maps IntType column to Exposed integer column") {
        val result = singleColumnDef(ColumnDef("count", ColumnType.IntType, nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "count"
        result.columnsByName["count"]!!.columnType.shouldBeInstanceOf<IntegerColumnType>()
    }

    test("maps LongType column to Exposed long column") {
        val result = singleColumnDef(ColumnDef("big_num", ColumnType.LongType, nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "big_num"
        result.columnsByName["big_num"]!!.columnType.shouldBeInstanceOf<LongColumnType>()
    }

    test("maps TextType column to Exposed text column") {
        val result = singleColumnDef(ColumnDef("description", ColumnType.TextType, nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "description"
        result.columnsByName["description"]!!.columnType.shouldBeInstanceOf<TextColumnType>()
    }

    test("maps VarcharType column to Exposed varchar column with correct length") {
        val result = singleColumnDef(ColumnDef("name", ColumnType.VarcharType(100), nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "name"
        val colType = result.columnsByName["name"]!!.columnType
        colType.shouldBeInstanceOf<VarCharColumnType>()
        (colType as VarCharColumnType).colLength shouldBe 100
    }

    test("maps BooleanType column to Exposed bool column") {
        val result = singleColumnDef(ColumnDef("active", ColumnType.BooleanType, nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "active"
        result.columnsByName["active"]!!.columnType.shouldBeInstanceOf<BooleanColumnType>()
    }

    test("maps DoubleType column to Exposed double column") {
        val result = singleColumnDef(ColumnDef("ratio", ColumnType.DoubleType, nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "ratio"
        result.columnsByName["ratio"]!!.columnType.shouldBeInstanceOf<DoubleColumnType>()
    }

    test("maps FloatType column to Exposed float column") {
        val result = singleColumnDef(ColumnDef("weight", ColumnType.FloatType, nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "weight"
        result.columnsByName["weight"]!!.columnType.shouldBeInstanceOf<FloatColumnType>()
    }

    test("maps UuidType column to Exposed uuid column") {
        val result = singleColumnDef(ColumnDef("external_id", ColumnType.UuidType, nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "external_id"
        result.columnsByName["external_id"]!!.columnType.shouldBeInstanceOf<UuidColumnType>()
    }

    test("maps DateType column to Exposed date column") {
        val result = singleColumnDef(ColumnDef("birth_date", ColumnType.DateType, nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "birth_date"
        result.columnsByName["birth_date"]!!.columnType.shouldBeInstanceOf<KotlinLocalDateColumnType>()
    }

    test("maps DateTimeType column to Exposed datetime column") {
        val result = singleColumnDef(ColumnDef("created_at", ColumnType.DateTimeType, nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "created_at"
        result.columnsByName["created_at"]!!.columnType.shouldBeInstanceOf<KotlinLocalDateTimeColumnType>()
    }

    test("maps DecimalType column to Exposed decimal column with correct precision and scale") {
        val result = singleColumnDef(ColumnDef("price", ColumnType.DecimalType(10, 2), nullable = false, primaryKey = false))
        result.columnsByName shouldContainKey "price"
        val colType = result.columnsByName["price"]!!.columnType
        colType.shouldBeInstanceOf<DecimalColumnType>()
        (colType as DecimalColumnType).precision shouldBe 10
        colType.scale shouldBe 2
    }

    test("maps EnumType column to Exposed varchar column") {
        val result =
            singleColumnDef(
                ColumnDef("status", ColumnType.EnumType("com.example.Status"), nullable = false, primaryKey = false)
            )
        result.columnsByName shouldContainKey "status"
        val colType = result.columnsByName["status"]!!.columnType
        colType.shouldBeInstanceOf<VarCharColumnType>()
        (colType as VarCharColumnType).colLength shouldBe 255
    }

    test("marks nullable columns with nullable modifier") {
        val result = singleColumnDef(ColumnDef("optional_text", ColumnType.TextType, nullable = true, primaryKey = false))
        result.columnsByName shouldContainKey "optional_text"
        result.columnsByName["optional_text"]!!.columnType.nullable shouldBe true
    }

    test("sets primary key on the correct column") {
        val def =
            object : LirpTableDef<Any> {
                override val tableName = "entities"
                override val columns =
                    listOf(
                        ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
                        ColumnDef("name", ColumnType.VarcharType(50), nullable = false, primaryKey = false)
                    )
            }
        val result = interpreter.interpret(def)
        val pk = result.table.primaryKey
        pk.shouldNotBeNull()
        pk.columns.map { it.name } shouldBe listOf("id")
    }

    test("provides column lookup by name") {
        val def =
            object : LirpTableDef<Any> {
                override val tableName = "things"
                override val columns =
                    listOf(
                        ColumnDef("id", ColumnType.LongType, nullable = false, primaryKey = true),
                        ColumnDef("label", ColumnType.VarcharType(200), nullable = false, primaryKey = false),
                        ColumnDef("score", ColumnType.DoubleType, nullable = true, primaryKey = false)
                    )
            }
        val result = interpreter.interpret(def)
        result.columnsByName shouldContainKey "id"
        result.columnsByName shouldContainKey "label"
        result.columnsByName shouldContainKey "score"
        result.columnsByName["id"].shouldNotBeNull()
        result.columnsByName["label"].shouldNotBeNull()
        result.columnsByName["score"].shouldNotBeNull()
    }
})
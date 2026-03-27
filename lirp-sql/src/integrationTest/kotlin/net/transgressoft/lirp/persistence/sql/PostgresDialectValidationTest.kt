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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.sql.Connection
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * A test entity with all properties needed for CRUD integration testing against PostgreSQL.
 */
private class TestPerson(override val id: Int) : ReactiveEntityBase<Int, TestPerson>() {
    var firstName: String by reactiveProperty("")
    var lastName: String by reactiveProperty("")
    var age: Int by reactiveProperty(0)
    override val uniqueId: String get() = id.toString()

    override fun clone(): TestPerson =
        TestPerson(id).also {
            it.firstName = firstName
            it.lastName = lastName
            it.age = age
        }
}

/**
 * Manual [SqlTableDef] for [TestPerson] using VARCHAR columns for all string fields.
 */
private object TestPersonTableDef : SqlTableDef<TestPerson> {
    override val tableName = "test_persons"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("first_name", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("last_name", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("age", ColumnType.IntType, nullable = false, primaryKey = false)
        )

    override fun fromRow(row: ResultRow, table: Table): TestPerson {
        val cols = table.columns.associateBy { it.name }

        @Suppress("UNCHECKED_CAST")
        val entity = TestPerson(row[cols["id"]!! as Column<Int>])
        @Suppress("UNCHECKED_CAST")
        entity.firstName = row[cols["first_name"]!! as Column<String>]
        @Suppress("UNCHECKED_CAST")
        entity.lastName = row[cols["last_name"]!! as Column<String>]
        @Suppress("UNCHECKED_CAST")
        entity.age = row[cols["age"]!! as Column<Int>]
        return entity
    }

    override fun toParams(entity: TestPerson, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["first_name"]!! to entity.firstName,
            cols["last_name"]!! to entity.lastName,
            cols["age"]!! to entity.age
        )
    }
}

/**
 * A test entity covering all 12 [ColumnType] variants, used to verify DDL type mapping against PostgreSQL.
 *
 * Date and datetime fields use `kotlinx.datetime` types because JetBrains Exposed's
 * `exposed-kotlin-datetime` module stores and retrieves [LocalDate] and [LocalDateTime] instances.
 * The `uuid_val` field uses [java.util.UUID] because Exposed's `uuid()` column operates on that type.
 * The `enum_val` field uses [String] because [ColumnType.EnumType] is stored as `VARCHAR(255)`.
 */
@OptIn(ExperimentalUuidApi::class)
private class AllTypesEntity(override val id: Int) : ReactiveEntityBase<Int, AllTypesEntity>() {
    var longVal: Long by reactiveProperty(0L)
    var textVal: String by reactiveProperty("")
    var boolVal: Boolean by reactiveProperty(false)
    var doubleVal: Double by reactiveProperty(0.0)
    var floatVal: Float by reactiveProperty(0.0f)
    var uuidVal: UUID by reactiveProperty(UUID.randomUUID())
    var dateVal: LocalDate by reactiveProperty(LocalDate(2025, 1, 1))
    var dateTimeVal: LocalDateTime by reactiveProperty(LocalDateTime(2025, 1, 1, 0, 0, 0))
    var varcharVal: String by reactiveProperty("")
    var decimalVal: BigDecimal by reactiveProperty(BigDecimal.ZERO)
    var enumVal: String by reactiveProperty("")
    override val uniqueId: String get() = id.toString()

    override fun clone(): AllTypesEntity =
        AllTypesEntity(id).also {
            it.longVal = longVal
            it.textVal = textVal
            it.boolVal = boolVal
            it.doubleVal = doubleVal
            it.floatVal = floatVal
            it.uuidVal = uuidVal
            it.dateVal = dateVal
            it.dateTimeVal = dateTimeVal
            it.varcharVal = varcharVal
            it.decimalVal = decimalVal
            it.enumVal = enumVal
        }
}

/**
 * Manual [SqlTableDef] for [AllTypesEntity] covering all 12 [ColumnType] variants.
 *
 * The `uuid` column uses [ColumnType.UuidType] which Exposed translates to the native PostgreSQL UUID type.
 * The `enum_val` column uses [ColumnType.EnumType] which is stored as `VARCHAR(255)` for portability.
 */
@OptIn(ExperimentalUuidApi::class)
private object AllTypesTableDef : SqlTableDef<AllTypesEntity> {
    override val tableName = "all_types"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("long_val", ColumnType.LongType, nullable = false, primaryKey = false),
            ColumnDef("text_val", ColumnType.TextType, nullable = false, primaryKey = false),
            ColumnDef("bool_val", ColumnType.BooleanType, nullable = false, primaryKey = false),
            ColumnDef("double_val", ColumnType.DoubleType, nullable = false, primaryKey = false),
            ColumnDef("float_val", ColumnType.FloatType, nullable = false, primaryKey = false),
            ColumnDef("uuid_val", ColumnType.UuidType, nullable = false, primaryKey = false),
            ColumnDef("date_val", ColumnType.DateType, nullable = false, primaryKey = false),
            ColumnDef("date_time_val", ColumnType.DateTimeType, nullable = false, primaryKey = false),
            ColumnDef("varchar_val", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("decimal_val", ColumnType.DecimalType(10, 2), nullable = false, primaryKey = false),
            ColumnDef("enum_val", ColumnType.EnumType("com.example.Status"), nullable = false, primaryKey = false)
        )

    override fun fromRow(row: ResultRow, table: Table): AllTypesEntity {
        val cols = table.columns.associateBy { it.name }

        @Suppress("UNCHECKED_CAST")
        return AllTypesEntity(row[cols["id"]!! as Column<Int>]).also { e ->
            e.longVal = row[cols["long_val"]!! as Column<Long>]
            e.textVal = row[cols["text_val"]!! as Column<String>]
            e.boolVal = row[cols["bool_val"]!! as Column<Boolean>]
            e.doubleVal = row[cols["double_val"]!! as Column<Double>]
            e.floatVal = row[cols["float_val"]!! as Column<Float>]
            e.uuidVal = row[cols["uuid_val"]!! as Column<UUID>]
            e.dateVal = row[cols["date_val"]!! as Column<LocalDate>]
            e.dateTimeVal = row[cols["date_time_val"]!! as Column<LocalDateTime>]
            e.varcharVal = row[cols["varchar_val"]!! as Column<String>]
            e.decimalVal = row[cols["decimal_val"]!! as Column<BigDecimal>]
            e.enumVal = row[cols["enum_val"]!! as Column<String>]
        }
    }

    override fun toParams(entity: AllTypesEntity, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["long_val"]!! to entity.longVal,
            cols["text_val"]!! to entity.textVal,
            cols["bool_val"]!! to entity.boolVal,
            cols["double_val"]!! to entity.doubleVal,
            cols["float_val"]!! to entity.floatVal,
            cols["uuid_val"]!! to entity.uuidVal,
            cols["date_val"]!! to entity.dateVal,
            cols["date_time_val"]!! to entity.dateTimeVal,
            cols["varchar_val"]!! to entity.varcharVal,
            cols["decimal_val"]!! to entity.decimalVal,
            cols["enum_val"]!! to entity.enumVal
        )
    }
}

/**
 * Integration tests validating PostgreSQL dialect correctness for [SqlRepository] and [ExposedTableInterpreter].
 *
 * Uses Testcontainers to spin up a real PostgreSQL 16 instance. Validates that:
 * - All 12 [ColumnType] variants produce correct PostgreSQL DDL types via `information_schema.columns`.
 * - Exposed auto-detects the PostgreSQL dialect from the DataSource's JDBC metadata.
 * - [SqlRepository] CRUD operations (add, findById, remove, clear, mutation persistence) work against PostgreSQL.
 */
@OptIn(ExperimentalUuidApi::class)
@DisplayName("PostgreSQL Dialect Validation")
internal class PostgresDialectValidationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
    var dataSource: HikariDataSource? = null

    beforeSpec {
        container.start()
        val config =
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = 10
            }
        dataSource = HikariDataSource(config)
    }

    afterSpec {
        dataSource?.close()
        container.stop()
    }

    /** Queries information_schema.columns for the given table, returning a map of column name to data_type. */
    fun columnTypes(tableName: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        dataSource!!.connection.use { conn: Connection ->
            conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ? AND table_schema = 'public'"
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        result[rs.getString("column_name")] = rs.getString("data_type")
                    }
                }
            }
        }
        return result
    }

    /** Drops the given table within a transaction for inter-test isolation. */
    fun dropTable(db: Database, table: Table) {
        transaction(db) {
            SchemaUtils.drop(table)
        }
    }

    test("auto-creates table with correct PostgreSQL column types for all 12 ColumnType variants") {
        val repo = SqlRepository(dataSource!!, AllTypesTableDef)
        val types = columnTypes("all_types")

        types["id"] shouldBe "integer"
        types["long_val"] shouldBe "bigint"
        types["text_val"] shouldBe "text"
        types["bool_val"] shouldBe "boolean"
        types["double_val"] shouldBe "double precision"
        types["float_val"] shouldBe "real"
        types["uuid_val"] shouldBe "uuid"
        types["date_val"] shouldBe "date"
        types["date_time_val"] shouldBe "timestamp without time zone"
        types["varchar_val"] shouldBe "character varying"
        types["decimal_val"] shouldBe "numeric"
        types["enum_val"] shouldBe "character varying"

        val db = Database.connect(dataSource!!)
        val exposedTable = ExposedTableInterpreter().interpret(AllTypesTableDef)
        dropTable(db, exposedTable.table)
        repo.close()
    }

    test("detects PostgreSQL dialect automatically from DataSource") {
        val db = Database.connect(dataSource!!)
        val dialectName =
            transaction(db) {
                this.db.dialect.name
            }
        // Exposed reports the PostgreSQL dialect name as "PostgreSQL" (mixed case)
        dialectName shouldBe "PostgreSQL"
    }

    test("adds entity and reads it back from PostgreSQL") {
        val repo =
            SqlRepository(dataSource!!, TestPersonTableDef).also { r ->
                r.add(
                    TestPerson(1).apply {
                        firstName = "Alice"
                        lastName = "Smith"
                        age = 30
                    }
                )
            }

        repo.findById(1).shouldBePresent {
            it.firstName shouldBe "Alice"
            it.lastName shouldBe "Smith"
            it.age shouldBe 30
        }

        // A second repo on the same DataSource confirms persistence to DB
        val repo2 = SqlRepository(dataSource!!, TestPersonTableDef)
        repo2.findById(1).shouldBePresent { it.firstName shouldBe "Alice" }

        val db = Database.connect(dataSource!!)
        val exposedTable = ExposedTableInterpreter().interpret(TestPersonTableDef)
        dropTable(db, exposedTable.table)
        repo.close()
        repo2.close()
    }

    test("persists entity mutation to PostgreSQL via onDirty") {
        val repo =
            SqlRepository(dataSource!!, TestPersonTableDef).also { r ->
                r.add(
                    TestPerson(5).apply {
                        firstName = "Frank"
                        lastName = "Lee"
                        age = 20
                    }
                )
            }

        // Mutate via the repo's in-memory reference to trigger onDirty
        repo.findById(5).get().firstName = "Franklin"
        Thread.sleep(200)

        val repo2 = SqlRepository(dataSource!!, TestPersonTableDef)
        repo2.findById(5).shouldBePresent { it.firstName shouldBe "Franklin" }

        val db = Database.connect(dataSource!!)
        val exposedTable = ExposedTableInterpreter().interpret(TestPersonTableDef)
        dropTable(db, exposedTable.table)
        repo.close()
        repo2.close()
    }

    test("removes entity from PostgreSQL") {
        val repo =
            SqlRepository(dataSource!!, TestPersonTableDef).also { r ->
                r.add(TestPerson(10).apply { firstName = "Grace" })
            }
        repo.remove(repo.findById(10).get())

        val repo2 = SqlRepository(dataSource!!, TestPersonTableDef)
        repo2.size() shouldBe 0

        val db = Database.connect(dataSource!!)
        val exposedTable = ExposedTableInterpreter().interpret(TestPersonTableDef)
        dropTable(db, exposedTable.table)
        repo.close()
        repo2.close()
    }

    test("clears all entities from PostgreSQL") {
        val repo =
            SqlRepository(dataSource!!, TestPersonTableDef).also { r ->
                r.add(TestPerson(20).apply { firstName = "Henry" })
                r.add(TestPerson(21).apply { firstName = "Iris" })
            }
        repo.clear()

        val repo2 = SqlRepository(dataSource!!, TestPersonTableDef)
        repo2.size() shouldBe 0

        val db = Database.connect(dataSource!!)
        val exposedTable = ExposedTableInterpreter().interpret(TestPersonTableDef)
        dropTable(db, exposedTable.table)
        repo.close()
        repo2.close()
    }
})
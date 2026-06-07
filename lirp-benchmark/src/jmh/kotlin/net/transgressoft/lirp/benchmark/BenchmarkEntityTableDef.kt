package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.LirpRawInitializer
import net.transgressoft.lirp.persistence.sql.SqlTableDef
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * Manual [SqlTableDef] for [BenchmarkEntity] mapping four columns: id (PK), label, age, and name.
 *
 * Hand-written because the benchmark module does not apply the `net.transgressoft.lirp.sql`
 * Gradle plugin (which generates KSP-based SqlTableDef). The [LirpIndexAccessor] for
 * [BenchmarkEntity]'s `@Indexed` properties is generated normally by the KSP processor.
 */
object BenchmarkEntityTableDef : SqlTableDef<BenchmarkEntity> {
    override val tableName = "benchmark_entities"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("label", ColumnType.VarcharType(255), nullable = false, primaryKey = false),
            ColumnDef("age", ColumnType.IntType, nullable = false, primaryKey = false),
            ColumnDef("name", ColumnType.VarcharType(255), nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): BenchmarkEntity {
        val cols = table.columns.associateBy { it.name }
        val id = row[cols["id"]!! as Column<Int>]
        val label = row[cols["label"]!! as Column<String>]
        val age = row[cols["age"]!! as Column<Int>]
        val entity = BenchmarkEntity(id, label, age)
        entity.name = row[cols["name"]!! as Column<String>]
        return entity
    }

    override fun toParams(entity: BenchmarkEntity, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["label"]!! to entity.label,
            cols["age"]!! to entity.age,
            cols["name"]!! to entity.name
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: BenchmarkEntity, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.name = row[cols["name"]!! as Column<String>]
    }

    override fun applyScalarRow(entity: BenchmarkEntity, row: ResultRow, table: Table, rawInit: LirpRawInitializer<BenchmarkEntity>) {
        // No-op: entity state is fully populated by fromRow.
    }
}
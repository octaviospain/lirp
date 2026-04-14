package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.sql.SqlTableDef
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * Manual [SqlTableDef] for [BenchmarkEntity] mapping three columns: id (PK), label, and name.
 *
 * Provided without KSP generation since the benchmark module does not run the LIRP KSP processor.
 * Mirrors the pattern used by [net.transgressoft.lirp.persistence.sql.TestPersonTableDef].
 */
object BenchmarkEntityTableDef : SqlTableDef<BenchmarkEntity> {
    override val tableName = "benchmark_entities"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("label", ColumnType.VarcharType(255), nullable = false, primaryKey = false),
            ColumnDef("name", ColumnType.VarcharType(255), nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): BenchmarkEntity {
        val cols = table.columns.associateBy { it.name }
        val id = row[cols["id"]!! as Column<Int>]
        val label = row[cols["label"]!! as Column<String>]
        val entity = BenchmarkEntity(id, label)
        entity.name = row[cols["name"]!! as Column<String>]
        return entity
    }

    override fun toParams(entity: BenchmarkEntity, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["label"]!! to entity.label,
            cols["name"]!! to entity.name
        )
    }
}
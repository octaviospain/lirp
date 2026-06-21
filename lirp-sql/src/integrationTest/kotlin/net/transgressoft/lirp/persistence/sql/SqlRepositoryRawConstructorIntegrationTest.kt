/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.LirpRawConstructor
import net.transgressoft.lirp.persistence.LirpRawInitializer
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.withDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.DisplayName
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * Test entity whose primary constructor is `internal`, standing in for a domain aggregate that a
 * persistence module in another Gradle module could not construct directly. All persisted state is
 * carried by constructor parameters so the entity is fully rebuilt by its [LirpRawConstructor] alone.
 */
class RawCtorEntity internal constructor(
    override val id: Int,
    val label: String,
    val score: Int
) : ReactiveEntityBase<Int, RawCtorEntity>() {
    override val uniqueId: String get() = id.toString()

    override fun clone(): RawCtorEntity = RawCtorEntity(id, label, score)
}

/**
 * Co-located constructor SPI for [RawCtorEntity]. Resolved by `SqlRepository.loadFromStore` via
 * `Class.forName` on the entity's binary name plus the `_LirpRawConstructor` suffix. Being in the
 * entity's own module, it reaches the `internal` constructor — the persistence-side
 * [RawConstructibleTableDef] never does.
 */
@Suppress("ClassName")
class RawCtorEntity_LirpRawConstructor : LirpRawConstructor<RawCtorEntity> {
    override fun construct(params: Map<String, Any?>): RawCtorEntity =
        RawCtorEntity(
            id = params["id"] as Int,
            label = params["label"] as String,
            score = params["score"] as Int
        )
}

/**
 * Construction-free [SqlTableDef] for [RawCtorEntity]: it maps columns and extracts constructor
 * argument values from a row, but delegates construction to [RawCtorEntity_LirpRawConstructor]. It
 * does not implement [fromRow] — the inherited default throws, proving that a successful load went
 * through the constructor SPI rather than `fromRow`.
 */
object RawCtorEntityTableDef : RawConstructibleTableDef<RawCtorEntity> {
    override val tableName = "raw_ctor_entities"
    override val entityClassName = "net.transgressoft.lirp.persistence.sql.RawCtorEntity"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("label", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("score", ColumnType.IntType, nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun constructorParams(row: ResultRow, table: Table): Map<String, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            "id" to row[cols["id"]!! as Column<Int>],
            "label" to row[cols["label"]!! as Column<String>],
            "score" to row[cols["score"]!! as Column<Int>]
        )
    }

    override fun toParams(entity: RawCtorEntity, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["label"]!! to entity.label,
            cols["score"]!! to entity.score
        )
    }

    override fun applyRow(entity: RawCtorEntity, row: ResultRow, table: Table) {
        // No-op: all state is immutable and supplied at construction; only reachable via @Version
        // optimistic-lock recovery, which this entity does not use.
    }

    override fun applyScalarRow(entity: RawCtorEntity, row: ResultRow, table: Table, rawInit: LirpRawInitializer<RawCtorEntity>) {
        // No-op: every field is a constructor parameter, so the entity is complete after construction.
    }
}

/**
 * Versioned variant of [RawCtorEntity] with an `internal` constructor. `id`, `tag`, and the
 * optimistic-lock `version` are constructor parameters rebuilt by its [LirpRawConstructor]; `note`
 * is a non-constructor reactive field restored separately through its [LirpRawInitializer]. The mix
 * exercises the optimistic-lock recovery path, which must both construct the instance and hydrate
 * its non-constructor scalars before re-inserting it.
 */
class VersionedRawCtorEntity internal constructor(
    override val id: Int,
    val tag: String,
    initialVersion: Long
) : ReactiveEntityBase<Int, VersionedRawCtorEntity>() {

    var version: Long by reactiveProperty(initialVersion)
    var note: String by reactiveProperty("")

    override val uniqueId: String get() = id.toString()

    override fun clone(): VersionedRawCtorEntity =
        VersionedRawCtorEntity(id, tag, version).also { copy ->
            copy.withEventsDisabled { copy.note = note }
        }
}

/** Co-located constructor SPI for [VersionedRawCtorEntity], reaching its `internal` constructor. */
@Suppress("ClassName")
class VersionedRawCtorEntity_LirpRawConstructor : LirpRawConstructor<VersionedRawCtorEntity> {
    override fun construct(params: Map<String, Any?>): VersionedRawCtorEntity =
        VersionedRawCtorEntity(
            id = params["id"] as Int,
            tag = params["tag"] as String,
            initialVersion = params["version"] as Long
        )
}

/**
 * Construction-free [SqlTableDef] for [VersionedRawCtorEntity] that also opts into optimistic
 * locking via [VersionedTableDef]. Construction (version included) is delegated to
 * [VersionedRawCtorEntity_LirpRawConstructor]; `fromRow` stays unimplemented and throws.
 */
object VersionedRawCtorTableDef :
    RawConstructibleTableDef<VersionedRawCtorEntity>,
    VersionedTableDef<VersionedRawCtorEntity> {
    override val tableName = "versioned_raw_ctor_entities"
    override val entityClassName = "net.transgressoft.lirp.persistence.sql.VersionedRawCtorEntity"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("tag", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("note", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("version", ColumnType.LongType, nullable = false, primaryKey = false, isVersion = true)
        )

    @Suppress("UNCHECKED_CAST")
    override fun constructorParams(row: ResultRow, table: Table): Map<String, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            "id" to row[cols["id"]!! as Column<Int>],
            "tag" to row[cols["tag"]!! as Column<String>],
            "version" to row[cols["version"]!! as Column<Long>]
        )
    }

    override fun toParams(entity: VersionedRawCtorEntity, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["tag"]!! to entity.tag,
            cols["note"]!! to entity.note,
            cols["version"]!! to entity.version
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: VersionedRawCtorEntity, row: ResultRow, table: Table) {
        // Refresh the mutable non-PK fields; `tag` is an immutable constructor field. Reachable on
        // the in-memory-survivor conflict path, not on the DELETE-defeat reconstruct path tested here.
        val cols = table.columns.associateBy { it.name }
        entity.note = row[cols["note"]!! as Column<String>]
        entity.version = row[cols["version"]!! as Column<Long>]
    }

    override fun bumpVersion(entity: VersionedRawCtorEntity, newVersion: Long) {
        entity.version = newVersion
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyScalarRow(
        entity: VersionedRawCtorEntity,
        row: ResultRow,
        table: Table,
        rawInit: LirpRawInitializer<VersionedRawCtorEntity>
    ) {
        // Restore the non-constructor `note` field through the entity's raw initializer, mirroring
        // the bulk-load scalar pass. `id`, `tag`, and `version` are constructor parameters.
        val cols = table.columns.associateBy { it.name }
        val note = row[cols["note"]!! as Column<String>]
        rawInit.entries.first { it.name == "note" }.silentSetter(entity, note)
    }
}

/**
 * Integration tests for the construction-delegation SPI: when a table definition is a
 * [RawConstructibleTableDef], `SqlRepository` builds entities through the entity's co-located
 * [LirpRawConstructor] — never through [SqlTableDef.fromRow] — so an entity with a non-public
 * constructor can be mapped from a separate module without any public construction factory.
 *
 * Covers both paths that materialize an entity from a row: the bulk load in `SqlEntityLoader` and
 * the optimistic-lock recovery reconstruct in `OptimisticLockRecovery`. Runs against PostgreSQL,
 * MySQL, MariaDB, SQLite, and H2 via the shared database harness.
 */
@DisplayName("SqlRepository RawConstructor Integration")
internal class SqlRepositoryRawConstructorIntegrationTest : FunSpec({

    // Opens a short-lived Exposed transaction against [dataSource] so a "third-party writer" can
    // bump a row's version independently of the repository, making the DELETE-defeat conflict
    // deterministic instead of timing-sensitive.
    fun <T> rawTransaction(dataSource: HikariDataSource, block: Table.() -> T): T {
        val db = Database.connect(dataSource)
        val exposed = ExposedTableInterpreter().interpret(VersionedRawCtorTableDef)
        return transaction(db) { exposed.table.block() }
    }

    context("[SqlRepository] bulk load builds internal entities via LirpRawConstructor, not fromRow") {
        withTests(databases) { db ->
            withDatabaseTest(db, RawCtorEntityTableDef) { dataSource ->
                // Seed via a first repository instance; writes go through toParams only.
                val seedRepo = SqlRepository(dataSource, RawCtorEntityTableDef)
                try {
                    repeat(8) { i -> seedRepo.add(RawCtorEntity(i, "label-$i", 100 + i)) }
                } finally {
                    seedRepo.close()
                }

                // A fresh repository bulk-loads from the store. Construction is delegated to
                // RawCtorEntity_LirpRawConstructor; fromRow is never invoked.
                val repo = SqlRepository(dataSource, RawCtorEntityTableDef)
                try {
                    repo.size() shouldBe 8
                    for (i in 0 until 8) {
                        val entity = repo.findById(i).orElseThrow()
                        entity.label shouldBe "label-$i"
                        entity.score shouldBe 100 + i
                    }
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("[SqlRepository] rebuilds an internal versioned entity via LirpRawConstructor during DELETE-defeat recovery") {
        withTests(databases) { db ->
            withDatabaseTest(db, VersionedRawCtorTableDef) { dataSource ->
                val conflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<*, *>>())
                val repo = SqlRepository(dataSource, VersionedRawCtorTableDef)
                try {
                    repo.subscribe { event ->
                        if (event is StandardCrudEvent.Conflict<*, *>) conflicts.add(event)
                    }

                    repo.add(VersionedRawCtorEntity(7, "tag-7", 0L).apply { note = "note-7" })

                    // Wait until the row is persisted at version 0 before the third-party bump.
                    eventually(10.seconds) {
                        val version =
                            rawTransaction(dataSource) {
                                @Suppress("UNCHECKED_CAST")
                                selectAll()
                                    .where { (columns.first { it.name == "id" } as Column<Int>) eq 7 }
                                    .singleOrNull()
                                    ?.let { row ->
                                        @Suppress("UNCHECKED_CAST")
                                        row[columns.first { it.name == "version" } as Column<Long>]
                                    }
                            }
                        version shouldBe 0L
                    }

                    // Third-party writer bumps the version so our subsequent versioned DELETE
                    // (WHERE version = 0) is defeated, driving the Case 2b reconstruct path.
                    rawTransaction(dataSource) {
                        @Suppress("UNCHECKED_CAST")
                        update({ (columns.first { it.name == "id" } as Column<Int>) eq 7 }) { row ->
                            @Suppress("UNCHECKED_CAST")
                            row[columns.first { it.name == "version" } as Column<Long>] = 1L
                        }
                    }

                    repo.remove(repo.findById(7).orElseThrow())

                    eventually(15.seconds) { conflicts.size shouldBe 1 }

                    // The entity is rebuilt through VersionedRawCtorEntity_LirpRawConstructor (fromRow
                    // throws) and re-inserted with the canonical version. `note` is a non-constructor
                    // field, so its restoration proves recovery also runs the scalar-row pass via the
                    // LirpRawInitializer rather than re-inserting a partially hydrated instance.
                    eventually(5.seconds) {
                        val recovered = repo.findById(7).orElseThrow()
                        recovered.tag shouldBe "tag-7"
                        recovered.version shouldBe 1L
                        recovered.note shouldBe "note-7"
                    }
                } finally {
                    repo.close()
                }
            }
        }
    }
})
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
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.PropertyChanged
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.LirpRegistryInfo
import net.transgressoft.lirp.persistence.LirpTransactionException
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.PendingDelete
import net.transgressoft.lirp.persistence.PendingUpdate
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.TransactionBuffer
import net.transgressoft.lirp.persistence.TransactionConflictException
import net.transgressoft.lirp.persistence.transaction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import javax.sql.DataSource

@Suppress("UNCHECKED_CAST")
private fun Table.audioItemIdColumn(): Column<Int> = columns.first { it.name == "id" } as Column<Int>

@Suppress("UNCHECKED_CAST")
private fun Table.audioItemTitleColumn(): Column<String> = columns.first { it.name == "title" } as Column<String>

/**
 * H2 unit tests for [SqlRepository.commitTransactionBuffer]: covers the all-or-nothing commit
 * semantics, in-memory rollback, `@Version` conflict surfacing as [TransactionConflictException],
 * deferred-event collapse, cascade validation, and the `onError` suppression path.
 *
 * Test isolation is achieved by creating a fresh H2 in-memory database per case via
 * [H2ContainerSupport.buildH2DataSource]. Rows that must exist in DB before the transaction
 * are written by closing a seed repository (which performs a synchronous final flush).
 *
 * **Note on `@Version` conflict and cascade tests:** property mutations inside a `transaction()`
 * block are intercepted by the entity-level `_txEventBuffer` and routed to `deferredEvents`,
 * bypassing `publisher.emitAsync()`. Consequently `buffer.updates` is never populated by the
 * reactive channel path during the block. The conflict-detection and cascade-validation tests
 * therefore call [SqlRepository.commitTransactionBuffer] directly with a hand-assembled
 * [TransactionBuffer] that carries a [PendingUpdate] representing the intended mutation, which
 * is the only reliable way to exercise those code paths at the H2 unit level.
 */
@DisplayName("SqlRepository transaction commit contract (H2)")
internal class SqlTransactionTest : StringSpec() {

    init {

        afterEach {
            // Deregister cascade-test repos from the shared LirpContext so subsequent tests
            // can re-register fresh instances without hitting the "already registered" check.
            RegistryBase.deregisterRepository(SqlTestTrack::class.java)
            RegistryBase.deregisterRepository(MutablePlaylistSql::class.java)
        }

        "transaction commits mutations to SQL and the row is visible after commit" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            // Seed: add item and flush to DB via close.
            val seedRepo = AudioItemSqlRepository(dataSource)
            seedRepo.add(MutableAudioItem(1, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem)
            seedRepo.close()

            val repo = AudioItemSqlRepository(dataSource)
            try {
                transaction(repo) { r ->
                    (r.findById(1).get() as MutableAudioItem).title = "Killer Queen"
                }

                // In-memory assertion.
                repo.findById(1).shouldBePresent { it.title shouldBe "Killer Queen" }

                // DB-level assertion via rawTransaction (bypasses the in-memory cache).
                val titleInDb =
                    DatabaseTestSupport.rawTransaction(dataSource, AudioItemSqlTableDef) {
                        selectAll()
                            .where { audioItemIdColumn() eq 1 }
                            .singleOrNull()
                            ?.let { it[audioItemTitleColumn()] }
                    }
                titleInDb shouldBe "Killer Queen"
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "transaction insert inside block is persisted to DB and visible via raw query" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = AudioItemSqlRepository(dataSource)
            try {
                transaction(repo) { r ->
                    r.add(MutableAudioItem(50, "Somebody to Love", "A Day at the Races") as AudioItem)
                }

                // In-memory: inserted entity is present.
                repo.findById(50).shouldBePresent { it.title shouldBe "Somebody to Love" }

                // DB-level: raw query confirms the row was committed.
                val titleInDb =
                    DatabaseTestSupport.rawTransaction(dataSource, AudioItemSqlTableDef) {
                        selectAll()
                            .where { audioItemIdColumn() eq 50 }
                            .singleOrNull()
                            ?.let { it[audioItemTitleColumn()] }
                    }
                titleInDb shouldBe "Somebody to Love"
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "transaction delete inside block is persisted to DB and row absent from raw query" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val seedRepo = AudioItemSqlRepository(dataSource)
            seedRepo.add(MutableAudioItem(51, "We Are the Champions", "News of the World") as AudioItem)
            seedRepo.close()

            val repo = AudioItemSqlRepository(dataSource)
            try {
                transaction(repo) { r ->
                    r.remove(r.findById(51).get())
                }

                // In-memory: entity removed.
                repo.findById(51).isPresent shouldBe false

                // DB-level: raw query confirms the row is gone.
                val rowInDb =
                    DatabaseTestSupport.rawTransaction(dataSource, AudioItemSqlTableDef) {
                        selectAll()
                            .where { audioItemIdColumn() eq 51 }
                            .singleOrNull()
                    }
                rowInDb shouldBe null
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "commitTransactionBuffer @Version conflict rolls back ALL DB ops atomically — sibling write absent from DB" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            // Seed two versioned persons: one will conflict, one is a sibling write in the same buffer.
            val seedRepo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
            val alice = TestVersionedPerson(10).apply { firstName = "Alice" }
            val bob = TestVersionedPerson(11).apply { firstName = "Bob" }
            seedRepo.add(alice)
            seedRepo.add(bob)
            // Flush both to DB synchronously.
            transaction(seedRepo) { _ -> }
            seedRepo.close()

            val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
            try {
                // Third-party writer bumps Alice's DB version to 1. The in-memory entity keeps version=0.
                DatabaseTestSupport.rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    update({ (columns.first { it.name == "id" } as Column<Int>) eq 10 }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                val liveAlice = repo.findById(10).get()
                val liveBob = repo.findById(11).get()

                // Assemble a buffer: Alice (will conflict at version=0) and Bob (clean sibling write).
                @Suppress("UNCHECKED_CAST")
                (liveAlice as ReactiveEntityBase<Int, TestVersionedPerson>).withEventsDisabled {
                    liveAlice.firstName = "AliceAttempted"
                }
                @Suppress("UNCHECKED_CAST")
                (liveBob as ReactiveEntityBase<Int, TestVersionedPerson>).withEventsDisabled {
                    liveBob.firstName = "BobAttempted"
                }
                val buffer = TransactionBuffer(repo)
                buffer.updates.add(PendingUpdate(liveAlice, expectedVersion = 0L))
                buffer.updates.add(PendingUpdate(liveBob, expectedVersion = 0L))

                shouldThrow<TransactionConflictException> {
                    repo.commitTransactionBuffer(buffer)
                }

                // DB-level: the sibling Bob write must NOT be in the DB (full rollback required).
                val exposedTable = ExposedTableInterpreter().interpret(TestVersionedPersonTableDef)
                val db = Database.connect(dataSource)
                val bobFirstNameInDb =
                    transaction(db) {
                        exposedTable.table
                            .selectAll()
                            .where { (exposedTable.table.columns.first { it.name == "id" } as Column<Int>) eq 11 }
                            .singleOrNull()
                            ?.let { row ->
                                @Suppress("UNCHECKED_CAST")
                                row[exposedTable.table.columns.first { it.name == "first_name" } as Column<String>]
                            }
                    }
                // Bob's DB row must remain "Bob" — the entire DB transaction was rolled back.
                bobFirstNameInDb shouldBe "Bob"
            } finally {
                try {
                    repo.close()
                } catch (_: Exception) {
                }
                dataSource.close()
            }
        }

        "transaction rollback restores in-memory state when the block throws" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val seedRepo = AudioItemSqlRepository(dataSource)
            seedRepo.add(MutableAudioItem(2, "Don't Stop Me Now", "Jazz") as AudioItem)
            seedRepo.close()

            val repo = AudioItemSqlRepository(dataSource)
            try {
                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        (r.findById(2).get() as MutableAudioItem).title = "mutated"
                        throw RuntimeException("injected block failure")
                    }
                }

                // In-memory state reverted to pre-block value.
                repo.findById(2).shouldBePresent { it.title shouldBe "Don't Stop Me Now" }
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "commitTransactionBuffer @Version conflict throws TransactionConflictException with in-block entity state in ConflictInfo" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = SqlRepository(dataSource, TestVersionedPersonTableDef)
            try {
                val entity = TestVersionedPerson(1).apply { firstName = "Alice" }
                repo.add(entity)
                // Flush INSERT to DB synchronously via a no-op transaction (pre-flush step writes the row).
                transaction(repo) { _ -> }

                // Bump the DB row's version externally. The in-memory entity keeps version=0.
                DatabaseTestSupport.rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    update({ (columns.first { it.name == "id" } as Column<Int>) eq 1 }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "age" } as Column<Int>] = 99
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                // Simulate the in-block mutation: set the entity's firstName to the attempted value
                // without firing reactive events (those would be deferred inside a real block).
                @Suppress("UNCHECKED_CAST")
                (entity as ReactiveEntityBase<Int, TestVersionedPerson>).withEventsDisabled {
                    entity.firstName = "InBlockValue"
                }

                // Assemble the TransactionBuffer as commitTransactionBuffer expects it.
                // expectedVersion=0 matches the entity's current in-memory version (pre-bump).
                val buffer = TransactionBuffer(repo)
                buffer.updates.add(PendingUpdate(entity, expectedVersion = 0L))

                val ex =
                    shouldThrow<TransactionConflictException> {
                        repo.commitTransactionBuffer(buffer)
                    }

                // ConflictInfo.entity carries the value attempted inside the block (pre-rollback).
                ex.conflicts shouldHaveSize 1
                @Suppress("UNCHECKED_CAST")
                (ex.conflicts.single().entity as TestVersionedPerson).firstName shouldBe "InBlockValue"
            } finally {
                try {
                    repo.close()
                } catch (_: Exception) {
                }
                dataSource.close()
            }
        }

        "transaction onError handler receives the exception and suppresses it" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val seedRepo = AudioItemSqlRepository(dataSource)
            seedRepo.add(MutableAudioItem(5, "Another One Bites the Dust", "The Game") as AudioItem)
            seedRepo.close()

            val repo = AudioItemSqlRepository(dataSource)
            try {
                var capturedThrowable: Throwable? = null

                // onError suppresses the exception from the block: no exception propagates.
                transaction(repo, onError = {
                    capturedThrowable = throwable
                }) { r ->
                    (r.findById(5).get() as MutableAudioItem).title = "mutated"
                    throw RuntimeException("simulated block failure")
                }

                // The raw block exception is delivered — wrapping into LirpTransactionException
                // only happens on the no-handler path.
                capturedThrowable.shouldBeInstanceOf<RuntimeException>()
                capturedThrowable!!.message shouldBe "simulated block failure"
                // In-memory state is reverted to the pre-block value after onError fires.
                repo.findById(5).shouldBePresent { it.title shouldBe "Another One Bites the Dust" }
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "deferred events collapse null-to-Rock-to-Jazz into a single null-to-Jazz PropertyChanged" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val seedRepo = AudioItemSqlRepository(dataSource)
            seedRepo.add(MutableAudioItem(3, "", "") as AudioItem)
            seedRepo.close()

            val repo = AudioItemSqlRepository(dataSource)
            try {
                val events = mutableListOf<MutationEvent<Int, AudioItem>>()
                repo.findById(3).get().subscribe { events.add(it) }

                transaction(repo) { r ->
                    val e = r.findById(3).get() as MutableAudioItem
                    e.title = "Rock"
                    e.title = "Jazz"
                }

                events shouldHaveSize 1
                @Suppress("UNCHECKED_CAST")
                val changed = events.single() as PropertyChanged<Int, AudioItem, String>
                changed.oldValue shouldBe ""
                changed.newValue shouldBe "Jazz"
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "commitTransactionBuffer with cascade child on same DataSource completes without LirpTransactionException" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            // Named subclasses register entity types in LirpContext so validateCascadeTargets
            // can resolve the SqlTestTrack target registry.
            val trackRepo = TxSqlTestTrackRepo(dataSource)
            val playlistRepo = TxMutablePlaylistRepo(dataSource)
            try {
                val playlist = MutablePlaylistSql(1L)
                playlist.name = "My Playlist"
                playlistRepo.add(playlist)
                transaction(playlistRepo) { _ -> }

                // Directly call commitTransactionBuffer with a PendingUpdate for the playlist entity.
                // validateCascadeTargets sees MutablePlaylistSql → SqlTestTrack (cascade target) →
                // TxSqlTestTrackRepo uses same dataSource → no exception.
                @Suppress("UNCHECKED_CAST")
                (playlist as ReactiveEntityBase<Long, MutablePlaylistSql>).withEventsDisabled {
                    playlist.name = "Updated Playlist"
                }
                val buffer = TransactionBuffer(playlistRepo)
                buffer.updates.add(PendingUpdate(playlist, expectedVersion = null))

                // No LirpTransactionException because both repos share the same DataSource.
                playlistRepo.commitTransactionBuffer(buffer)

                playlistRepo.findById(1L).shouldBePresent { it.name shouldBe "Updated Playlist" }
            } finally {
                try {
                    playlistRepo.close()
                } catch (_: Exception) {
                }
                try {
                    trackRepo.close()
                } catch (_: Exception) {
                }
                dataSource.close()
            }
        }

        "commitTransactionBuffer with cascade child on a different DataSource but CascadeAction.NONE completes without LirpTransactionException" {
            val dataSourceA = H2ContainerSupport.buildH2DataSource()
            val dataSourceB = H2ContainerSupport.buildH2DataSource()
            // Playlist on dataSourceA; track repo registered on dataSourceB.
            // MutablePlaylistSql.tracks has CascadeAction.NONE, so the different DataSource is ignored
            // and the transaction proceeds normally.
            val trackRepo = TxSqlTestTrackRepo(dataSourceB)
            val playlistRepo = TxMutablePlaylistRepo(dataSourceA)
            try {
                val playlist = MutablePlaylistSql(2L)
                playlist.name = "Cross-DS Playlist"
                playlistRepo.add(playlist)
                transaction(playlistRepo) { _ -> }

                @Suppress("UNCHECKED_CAST")
                (playlist as ReactiveEntityBase<Long, MutablePlaylistSql>).withEventsDisabled {
                    playlist.name = "should-commit"
                }
                val buffer = TransactionBuffer(playlistRepo)
                buffer.updates.add(PendingUpdate(playlist, expectedVersion = null))

                // No LirpTransactionException: CascadeAction.NONE means validation skips the cross-DS target.
                playlistRepo.commitTransactionBuffer(buffer)

                playlistRepo.findById(2L).shouldBePresent { it.name shouldBe "should-commit" }
            } finally {
                try {
                    playlistRepo.close()
                } catch (_: Exception) {
                }
                try {
                    trackRepo.close()
                } catch (_: Exception) {
                }
                dataSourceA.close()
                dataSourceB.close()
            }
        }

        "transaction rollback discards buffered events and they are never delivered to subscribers" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val seedRepo = AudioItemSqlRepository(dataSource)
            seedRepo.add(MutableAudioItem(4, "Radio Ga Ga", "The Works") as AudioItem)
            seedRepo.close()

            val repo = AudioItemSqlRepository(dataSource)
            try {
                val events = mutableListOf<MutationEvent<Int, AudioItem>>()
                repo.findById(4).get().subscribe { events.add(it) }

                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        (r.findById(4).get() as MutableAudioItem).title = "changed"
                        throw RuntimeException("abort")
                    }
                }

                events.shouldBeEmpty()
                repo.findById(4).shouldBePresent { it.title shouldBe "Radio Ga Ga" }
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "delete-only @Version conflict surfaces as TransactionConflictException, not IllegalStateException" {
            // Verifies that preRollbackSnapshots covers buffer.deletes so a delete-only
            // version conflict can build ConflictInfo without a findById() fallback that
            // would throw IllegalStateException on a row that no longer exists in DB.
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
            try {
                val entity = TestVersionedPerson(99).apply { firstName = "George" }
                repo.add(entity)
                // Flush INSERT to DB via a no-op transaction (pre-flush writes the row).
                transaction(repo) { _ -> }

                // Bump the DB row's version externally. In-memory entity keeps version=0.
                DatabaseTestSupport.rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    update({ (columns.first { it.name == "id" } as Column<Int>) eq 99 }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                // Assemble a buffer with only a delete — no updates, no inserts. The conflict snapshot
                // is built from buffer.deletes (which carries the entity), so no entitySnapshots seed
                // is needed for the delete-only path.
                val buffer = TransactionBuffer(repo)
                buffer.deletes.add(
                    net.transgressoft.lirp.persistence.PendingDelete(entity.id, entity, expectedVersion = 0L)
                )

                // Must throw TransactionConflictException, not IllegalStateException.
                val ex =
                    shouldThrow<TransactionConflictException> {
                        repo.commitTransactionBuffer(buffer)
                    }
                ex.conflicts shouldHaveSize 1
            } finally {
                try {
                    repo.close()
                } catch (_: Exception) {
                }
                dataSource.close()
            }
        }

        "transaction remove-then-add same pre-existing versioned id updates the row without duplicate-key failure" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val seedRepo = AudioItemSqlRepository(dataSource)
            seedRepo.add(MutableAudioItem(60, "Killer Queen", "Sheer Heart Attack") as AudioItem)
            seedRepo.close()

            val repo = AudioItemSqlRepository(dataSource)
            try {
                transaction(repo) { r ->
                    val existing = r.findById(60).get()
                    r.remove(existing)
                    r.add(MutableAudioItem(60, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem)
                }

                // In-memory: re-added value present.
                repo.findById(60).shouldBePresent { it.title shouldBe "Bohemian Rhapsody" }

                // DB-level: exactly one row with the updated title.
                val titleInDb =
                    DatabaseTestSupport.rawTransaction(dataSource, AudioItemSqlTableDef) {
                        selectAll().where { audioItemIdColumn() eq 60 }.singleOrNull()
                            ?.let { it[audioItemTitleColumn()] }
                    }
                titleInDb shouldBe "Bohemian Rhapsody"
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "transaction add-then-remove same new id is a no-op — row absent from DB after commit" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = AudioItemSqlRepository(dataSource)
            try {
                transaction(repo) { r ->
                    r.add(MutableAudioItem(61, "We Will Rock You", "News of the World") as AudioItem)
                    r.remove(r.findById(61).get())
                }

                // In-memory: no entity.
                repo.findById(61).isPresent shouldBe false

                // DB-level: no row written.
                val rowInDb =
                    DatabaseTestSupport.rawTransaction(dataSource, AudioItemSqlTableDef) {
                        selectAll().where { audioItemIdColumn() eq 61 }.singleOrNull()
                    }
                rowInDb shouldBe null
            } finally {
                repo.close()
                dataSource.close()
            }
        }
    }
}

/**
 * Named [SqlRepository] subclass for [SqlTestTrack] accepting an existing [DataSource].
 *
 * A named subclass is required so that [RegistryBase] can find the corresponding
 * `_LirpRegistryInfo` companion by convention and register [SqlTestTrack] in
 * [net.transgressoft.lirp.persistence.LirpContext]. Anonymous `SqlRepository<Int, SqlTestTrack>`
 * instances have no `_LirpRegistryInfo` and are not registered, causing
 * [SqlRepository.commitTransactionBuffer]'s cascade-datasource check to be silently skipped.
 */
internal class TxSqlTestTrackRepo(dataSource: DataSource) :
    SqlRepository<Int, SqlTestTrack>(dataSource, SqlTestTrackTableDef)

@Suppress("ClassName")
internal class `TxSqlTestTrackRepo_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = SqlTestTrack::class.java
}

/**
 * Named [SqlRepository] subclass for [MutablePlaylistSql] accepting an existing [DataSource].
 *
 * Same rationale as [TxSqlTestTrackRepo]: requires a named subclass so [LirpContext] registers
 * [MutablePlaylistSql] and the KSP-generated [MutablePlaylistSql_LirpRefAccessor] is reachable
 * via [RegistryBase.publicRefAccessorFor].
 */
internal class TxMutablePlaylistRepo(dataSource: DataSource) :
    SqlRepository<Long, MutablePlaylistSql>(dataSource, MutablePlaylistSqlTableDef)

@Suppress("ClassName")
internal class `TxMutablePlaylistRepo_LirpRegistryInfo` : LirpRegistryInfo {
    override val entityClass: Class<*> = MutablePlaylistSql::class.java
}
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

package net.transgressoft.lirp.persistence.sql;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java interoperability integration tests for {@link SqlRepository}, verifying that the lirp-sql
 * public API is ergonomic and fully usable from pure Java code against a real PostgreSQL instance
 * provisioned by Testcontainers.
 *
 * <p>Covers create, read, update, delete, and full lifecycle operations using only Java syntax —
 * no Kotlin-specific constructs.
 */
@DisplayName("SqlRepository Java Interop Integration")
class SqlJavaInteropIntegrationTest {

    static HikariDataSource dataSource;

    @BeforeAll
    static void setupDatabase() {
        dataSource = PostgresContainerSupport.INSTANCE.buildDataSource();
        DatabaseTestSupport.INSTANCE.dropTable(dataSource, TestPersonTableDef.INSTANCE);
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        DatabaseTestSupport.INSTANCE.dropTable(dataSource, TestPersonTableDef.INSTANCE);
    }

    SqlRepository<Integer, TestPerson> newRepo() {
        return new SqlRepository<Integer, TestPerson>((DataSource) dataSource, TestPersonTableDef.INSTANCE, true);
    }

    @Test
    @DisplayName("create adds entity and findById returns it")
    void createAddsEntityAndFindByIdReturnsIt() {
        var repo = newRepo();
        try {
            var person = new TestPerson(1);
            person.setFirstName("Alice");
            person.setLastName("Smith");
            person.setAge(30);
            repo.add(person);

            var found = repo.findById(1);
            assertTrue(found.isPresent());
            assertEquals("Alice", found.get().getFirstName());
            assertEquals("Smith", found.get().getLastName());
            assertEquals(30, found.get().getAge());
        } finally {
            repo.close();
        }
    }

    @Test
    @DisplayName("update persists mutated entity to database")
    void updatePersistsMutatedEntityToDatabase() {
        var repo = newRepo();
        try {
            var person = new TestPerson(2);
            person.setFirstName("Bob");
            person.setLastName("Jones");
            person.setAge(25);
            repo.add(person);

            var found = repo.findById(2);
            assertTrue(found.isPresent());
            found.get().setFirstName("Robert");
        } finally {
            // close() flushes pending ops; a fresh repo verifies DB persistence
            repo.close();
        }

        var repo2 = newRepo();
        try {
            var updated = repo2.findById(2);
            assertTrue(updated.isPresent());
            assertEquals("Robert", updated.get().getFirstName());
        } finally {
            repo2.close();
        }
    }

    @Test
    @DisplayName("remove deletes entity from database")
    void removeDeletesEntityFromDatabase() {
        var repo = newRepo();
        try {
            var person = new TestPerson(3);
            person.setFirstName("Carol");
            repo.add(person);
            repo.remove(person);
        } finally {
            // close() flushes pending ops before opening the verification repo
            repo.close();
        }

        var repo2 = newRepo();
        try {
            assertEquals(0, repo2.size());
        } finally {
            repo2.close();
        }
    }

    @Test
    @DisplayName("clear removes all entities from database")
    void clearRemovesAllEntitiesFromDatabase() {
        var repo = newRepo();
        try {
            var p10 = new TestPerson(10);
            p10.setFirstName("Dave");
            var p11 = new TestPerson(11);
            p11.setFirstName("Eve");
            var p12 = new TestPerson(12);
            p12.setFirstName("Frank");
            repo.add(p10);
            repo.add(p11);
            repo.add(p12);
            repo.clear();
        } finally {
            // close() flushes pending ops before opening the verification repo
            repo.close();
        }

        var repo2 = newRepo();
        try {
            assertEquals(0, repo2.size());
        } finally {
            repo2.close();
        }
    }

    @Test
    @DisplayName("full CRUD lifecycle: create, read, update, delete")
    void fullCrudLifecycle() {
        var repo = newRepo();
        try {
            var person = new TestPerson(100);
            person.setFirstName("Lifecycle");
            person.setLastName("Test");
            person.setAge(42);
            repo.add(person);

            var found = repo.findById(100);
            assertTrue(found.isPresent());
            assertEquals("Lifecycle", found.get().getFirstName());

            found.get().setFirstName("Updated");
        } finally {
            // close() flushes the pending UPDATE; a fresh repo verifies DB persistence
            repo.close();
        }

        var repo2 = newRepo();
        try {
            var updated = repo2.findById(100);
            assertTrue(updated.isPresent());
            assertEquals("Updated", updated.get().getFirstName());

            repo2.remove(updated.get());
        } finally {
            // close() flushes the pending DELETE before opening repo3
            repo2.close();
        }

        var repo3 = newRepo();
        try {
            assertEquals(0, repo3.size());
        } finally {
            repo3.close();
        }
    }
}

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

package net.transgressoft.lirp.persistence.query

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.testing.reactiveScope
import net.transgressoft.lirp.testing.record
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reflection-free verification, event emission correctness, and Java interop documentation
 * for the Query DSL.
 */
@DisplayName("Query DSL Reflection-Free")
internal class ReflectionFreeQueryDslTest : FunSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext
    lateinit var repo: ProductVolatileRepo

    beforeTest {
        ctx = LirpContext()
        repo = ProductVolatileRepo(ctx)
        repo.create(1, "books", 10.0, 5, "Book A")
        repo.create(2, "electronics", 100.0, 10, "Gadget")
    }

    afterTest {
        ctx.close()
    }

    test("KProperty1 get and Product::name resolve without kotlin-reflect") {
        val prop = Product::category
        val product = Product(1, "books", 10.0, 5, "Book")
        prop.get(product) shouldBe "books"
        prop.name shouldBe "category"
    }

    test("Query DSL source does not import kotlin.reflect.full") {
        val queryPackageDir =
            listOf(
                File("src/main/kotlin/net/transgressoft/lirp/persistence/query"),
                File("lirp-core/src/main/kotlin/net/transgressoft/lirp/persistence/query")
            ).firstOrNull { it.exists() && it.isDirectory }
                ?: error("Query DSL source directory not found from working dir: ${File(".").absolutePath}")

        val importRegex = Regex("^\\s*import\\s+kotlin\\.reflect\\.full\\b")
        val fullyQualifiedRegex = Regex("\\bkotlin\\.reflect\\.full\\.\\w+")

        fun String.stripKotlinComments(): String {
            // Remove /* ... */ blocks and // ... comments
            return replace("""/\*[\s\S]*?\*/""".toRegex(), " ")
                .replace("""//.*""".toRegex(), "")
        }

        val violations =
            queryPackageDir.walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { idx, line ->
                        val codeOnly = line.stripKotlinComments()
                        when {
                            importRegex.containsMatchIn(codeOnly) -> "${file.name}:${idx + 1} import: $line"
                            fullyQualifiedRegex.containsMatchIn(codeOnly) -> "${file.name}:${idx + 1} FQ usage: $line"
                            else -> null
                        }
                    }
                }
                .toList()

        violations.shouldBeEmpty()
    }

    test("query is silent by default — no READ events emitted") {
        val readEventEmitted = AtomicBoolean(false)
        repo.subscribe(CrudEvent.Type.READ) { readEventEmitted.set(true) }

        repo.query { where { Product::category eq "books" } }.toList()

        reactive.advance()

        readEventEmitted.get().shouldBeFalse()
    }

    test("query emits READ on terminal operation when READ events enabled") {
        repo.activateEvents(CrudEvent.Type.READ)
        val readEvents = repo.record(CrudEvent.Type.READ)

        repo.query { where { Product::category eq "books" } }.toList()

        reactive.advance()

        readEvents.count shouldBeGreaterThan 0
    }

    test("query firstOrNull emits READ when READ events enabled") {
        repo.activateEvents(CrudEvent.Type.READ)
        val readEvents = repo.record(CrudEvent.Type.READ)

        repo.query { where { Product::category eq "books" } }.firstOrNull()

        reactive.advance()

        readEvents.count shouldBeGreaterThan 0
    }

    test("multiple terminal operations emit READ only on first consumption") {
        repo.activateEvents(CrudEvent.Type.READ)
        val readEvents = repo.record(CrudEvent.Type.READ)

        val seq = repo.query { where { Product::category eq "books" } }
        seq.toList()
        seq.toList()

        reactive.advance()

        readEvents.count shouldBe 1
    }

    test("query with no matches and READ enabled emits empty READ event") {
        val readEventEmitted = AtomicBoolean(false)
        repo.activateEvents(CrudEvent.Type.READ)
        repo.subscribe(CrudEvent.Type.READ) { readEventEmitted.set(true) }

        repo.query { where { Product::category eq "toys" } }.toList()

        reactive.advance()

        // Empty results still trigger the event wrapper, but the emitted set is empty
        readEventEmitted.get().shouldBeTrue()
    }

    /*
     * Java Interop Decision (DSL-04)
     *
     * The Kotlin Query DSL uses KProperty1 references which are not ergonomic for Java consumers.
     * The decided path for future Java interop is KSP-generated typed accessors with JPA-style
     * naming (e.g., Product_.CATEGORY), exposing ALL properties (not just @Indexed).
     *
     * Java consumers in this release continue using the existing APIs:
     *   - search(Predicate)
     *   - lazySearch(Predicate)
     *   - searchStream(Predicate)
     *   - findByIndex(String, Object)
     *
     * No string-keyed facade is provided. Implementation of KSP-generated accessors is deferred
     * to a future phase per locked decision D-01.
     */
})
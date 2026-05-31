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

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

internal data class DepthA(
    override val id: Int,
    val refB: Int?,
    override val uniqueId: String = "a-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

internal data class DepthB(
    override val id: Int,
    val refC: Int?,
    override val uniqueId: String = "b-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

internal data class DepthC(
    override val id: Int,
    val refD: Int?,
    override val uniqueId: String = "c-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

internal data class DepthD(
    override val id: Int,
    val refE: Int?,
    override val uniqueId: String = "d-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

internal data class DepthE(
    override val id: Int,
    val value: Int,
    override val uniqueId: String = "e-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

internal class RepoA : VolatileRepository<Int, DepthA>(LirpContext.default, "DepthA")

internal class RepoB : VolatileRepository<Int, DepthB>(LirpContext.default, "DepthB")

internal class RepoC : VolatileRepository<Int, DepthC>(LirpContext.default, "DepthC")

internal class RepoD : VolatileRepository<Int, DepthD>(LirpContext.default, "DepthD")

internal class RepoE : VolatileRepository<Int, DepthE>(LirpContext.default, "DepthE")

/**
 * Verifies the depth-3 limit enforced at `Via*` construction.
 */
@DisplayName("Via depth limit")
internal class ViaDepthLimitTest : FunSpec({

    val repoB = RepoB()
    val repoC = RepoC()
    val repoD = RepoD()
    val repoE = RepoE()

    test("depth-3 chain constructs successfully") {
        val depthThree: Predicate<DepthA> =
            DepthA::refB via repoB where {
                DepthB::refC via repoC where {
                    DepthC::refD via repoD where { DepthD::refE eq 7 }
                }
            }
        depthThree::class.simpleName shouldContain "Via"
    }

    test("depth-4 chain raises IllegalStateException naming the chain") {
        val ex =
            shouldThrow<IllegalStateException> {
                DepthA::refB via repoB where {
                    DepthB::refC via repoC where {
                        DepthC::refD via repoD where {
                            DepthD::refE via repoE where { DepthE::value eq 7 }
                        }
                    }
                }
            }
        val message = ex.message ?: ""
        message shouldContain "refB"
        message shouldContain "refC"
        message shouldContain "refD"
        message shouldContain "refE"
        message shouldContain "depth 3"
    }

    test("depth-4 diagnostic mentions Phase 54 CONTEXT.md") {
        val ex =
            shouldThrow<IllegalStateException> {
                DepthA::refB via repoB where {
                    DepthB::refC via repoC where {
                        DepthC::refD via repoD where {
                            DepthD::refE via repoE where { DepthE::value eq 7 }
                        }
                    }
                }
            }
        (ex.message ?: "") shouldContain "Phase 54 CONTEXT.md"
    }
})
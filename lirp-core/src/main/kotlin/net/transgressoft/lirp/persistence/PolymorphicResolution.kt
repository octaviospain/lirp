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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.IdentifiableEntity

/**
 * Runtime result handle for a [PolymorphicAggregateDelegate], enforcing the exactly-one-non-null
 * referential invariant before any resolution is returned.
 *
 * Every resolution method fires `check(exactly one non-null arm)` before returning. This makes
 * the invariant a bug-detector: a both-set or none-set state surfaces as [IllegalStateException]
 * rather than silently returning an incorrect result.
 *
 * The phantom type parameter `A` disambiguates the KSP-generated `activeArm()` extension
 * functions when multiple polymorphic properties coexist on the same entity. At runtime `A`
 * is erased and has no effect on resolution logic.
 *
 * @param A phantom type for KSP-generated typed accessor dispatch; not used at runtime
 */
class PolymorphicResolution<A>(private val arms: List<PolymorphicArmDelegate<*, *>>) {

    /**
     * Validates the exactly-one-non-null invariant and returns the label of the single non-null arm.
     *
     * @throws IllegalStateException when the number of non-null arms is not exactly one
     */
    fun resolveActiveLabel(): String {
        val nonNullCount = arms.count { it.hasValue() }
        check(nonNullCount == 1) {
            "Exactly one polymorphic arm must be non-null; found $nonNullCount non-null arm(s)"
        }
        return arms.first { it.hasValue() }.label
    }

    /**
     * Validates the exactly-one-non-null invariant and returns the resolved entity for the arm
     * identified by [label].
     *
     * The invariant check fires before the arm is resolved — a both-set or none-set state throws
     * before any resolution is attempted. The arm is bound eagerly when the owning entity is added
     * to a repository, so resolution reflects the live state of the target registry: an arm whose
     * referenced entity is not yet (or no longer) in the registry throws [IllegalStateException].
     *
     * @param label the arm label to resolve
     * @throws IllegalStateException when the number of non-null arms is not exactly one, or when the
     *   referenced entity for [label] cannot be resolved from its registry
     * @throws NoSuchElementException when no arm with the given [label] exists
     */
    fun resolveArm(label: String): IdentifiableEntity<*> {
        val nonNullCount = arms.count { it.hasValue() }
        check(nonNullCount == 1) {
            "Exactly one polymorphic arm must be non-null; found $nonNullCount non-null arm(s)"
        }
        val arm = arms.first { it.label == label }
        return arm.innerDelegate.resolve().orElseThrow {
            IllegalStateException("Polymorphic arm '$label' references an entity not present in its registry")
        }
    }

    /**
     * Validates the exactly-one-non-null invariant and returns the resolved entity of the active arm.
     *
     * The arm is bound eagerly at repository-add time, so resolution reflects the live registry state.
     *
     * @throws IllegalStateException when the number of non-null arms is not exactly one, or when the
     *   active arm references an entity not present in its registry
     */
    fun resolve(): IdentifiableEntity<*> = resolveActive().second

    /**
     * Atomically resolves the single active arm against one invariant scan, returning both its label
     * and resolved entity. Computing the active label and resolving its entity in a single pass closes
     * the time-of-check-to-time-of-use window that a separate [resolveActiveLabel] + [resolveArm] pair
     * would expose: a concurrent arm-scalar mutation between the two calls could otherwise shift which
     * arm is active and surface as a cast failure at the call site.
     *
     * @return the active arm's label paired with its resolved entity
     * @throws IllegalStateException when the number of non-null arms is not exactly one, or when the
     *   active arm references an entity not present in its registry
     */
    fun resolveActive(): Pair<String, IdentifiableEntity<*>> {
        val nonNull = arms.filter { it.hasValue() }
        check(nonNull.size == 1) {
            "Exactly one polymorphic arm must be non-null; found ${nonNull.size} non-null arm(s)"
        }
        val arm = nonNull.first()
        val entity =
            arm.innerDelegate.resolve().orElseThrow {
                IllegalStateException("Polymorphic arm '${arm.label}' references an entity not present in its registry")
            }
        return arm.label to entity
    }
}
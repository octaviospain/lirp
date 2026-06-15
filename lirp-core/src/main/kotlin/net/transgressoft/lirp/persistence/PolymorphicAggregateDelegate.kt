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
import net.transgressoft.lirp.entity.ReactiveEntityBase
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Property delegate that groups multiple named [PolymorphicArmDelegate] instances and enforces
 * the exactly-one-non-null referential invariant through lazy resolution.
 *
 * Returned by [polymorphicAggregate] and bound to the owning entity via the [provideDelegate]
 * operator, which registers this instance so the entity can validate all polymorphic delegates
 * before persistence.
 *
 * Implements [ReadOnlyProperty] so that `val target by polymorphicAggregate(...)` compiles.
 * [getValue] returns `this`, allowing callers to invoke [resolve], [resolution], and [armDelegate]
 * directly on the delegate object — mirroring the `AggregateRefDelegate.getValue() = this` pattern.
 *
 * The exactly-one invariant is lazy: it fires only when [resolve] or [PolymorphicResolution.resolveActiveLabel]
 * is called, not when the property is read or when arm scalars are assigned independently.
 *
 * @param arms the list of arms composing this polymorphic reference
 */
class PolymorphicAggregateDelegate(
    private val arms: List<PolymorphicArmDelegate<*, *>>
) : ReadOnlyProperty<ReactiveEntityBase<*, *>, PolymorphicAggregateDelegate> {

    init {
        require(arms.isNotEmpty()) { "polymorphicAggregate requires at least one arm" }
        val labels = arms.map { it.label }
        require(labels.all { it.isNotBlank() }) { "Polymorphic arm labels must be non-blank: $labels" }
        require(labels.size == labels.toSet().size) { "Polymorphic arm labels must be unique: $labels" }
    }

    /**
     * Called by Kotlin's property delegation mechanism when the delegated property is initialized
     * (at entity construction).
     * Registers this delegate with the owning entity so that [ReactiveEntityBase.validatePolymorphicDelegates]
     * can verify the exactly-one state before persistence.
     */
    operator fun provideDelegate(
        thisRef: ReactiveEntityBase<*, *>,
        prop: KProperty<*>
    ): PolymorphicAggregateDelegate {
        thisRef.registerPolymorphicDelegate(this)
        return this
    }

    /**
     * Returns `this` so the delegate object serves as the resolution handle.
     * Callers write `entity.target.resolve()` with no unwrapping step.
     */
    override fun getValue(
        thisRef: ReactiveEntityBase<*, *>,
        property: KProperty<*>
    ): PolymorphicAggregateDelegate = this

    /**
     * Returns a [PolymorphicResolution] wrapping the arms of this delegate.
     *
     * Used by the KSP-generated typed accessor extension and by callers that need access to
     * [PolymorphicResolution.resolveActiveLabel] or [PolymorphicResolution.resolveArm] directly.
     */
    fun resolution(): PolymorphicResolution<*> = PolymorphicResolution<Any>(arms)

    /**
     * Validates the exactly-one-non-null invariant and returns the resolved entity of the active arm.
     *
     * Convenience delegation to [PolymorphicResolution.resolve] — equivalent to calling
     * `resolution().resolve()` but avoids an extra object allocation at simple call sites.
     *
     * @throws IllegalStateException when the number of non-null arms is not exactly one
     */
    fun resolve(): IdentifiableEntity<*> = resolution().resolve()

    /**
     * Returns the [AggregateRefDelegate] owned by the arm identified by [label].
     *
     * Used by KSP-generated `_LirpRefAccessor` entries so that cascade and bubble-up wiring
     * reach the underlying delegate without any unchecked casts.
     *
     * @param label the arm label to look up
     * @throws NoSuchElementException when no arm with the given [label] exists
     */
    fun armDelegate(label: String): AggregateRefDelegate<*, *> =
        arms.first { it.label == label }.innerDelegate

    /**
     * Validates the exactly-one-non-null invariant. Called by [ReactiveEntityBase.validatePolymorphicDelegates]
     * before an entity is inserted into a persistent repository.
     *
     * @throws IllegalStateException when the number of non-null arms is not exactly one
     */
    fun validateBeforePersist() {
        val nonNullCount = arms.count { it.hasValue() }
        check(nonNullCount == 1) {
            "Exactly one polymorphic arm must be set before persistence; $nonNullCount arm(s) are non-null"
        }
    }

    /**
     * Binds each arm's inner [AggregateRefDelegate] to the registry for its target entity type
     * within [context]. Arms whose target type is not yet registered are silently skipped and
     * will resolve as `Optional.empty` until the registry is registered.
     *
     * Called by [ReactiveEntityBase.bindPolymorphicArms] when the owning entity is added to
     * a repository.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun bindArms(context: LirpContext) {
        for (arm in arms) {
            val registry = context.registryFor(arm.referencedClass) ?: continue
            (arm.innerDelegate as AggregateRefDelegate<Comparable<Any>, IdentifiableEntity<Comparable<Any>>>)
                .bindRegistry(
                    registry as Registry<Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
                    context
                )
        }
    }
}

/**
 * Creates a property delegate that declares a polymorphic aggregate reference over the given [arms].
 *
 * Each arm targets a different entity type and carries its own cascade semantics. Exactly one arm
 * must be non-null at resolution time and before persistence — the invariant is enforced lazily
 * at [PolymorphicAggregateDelegate.resolve] and at repository add time.
 *
 * Example:
 * ```kotlin
 * val target by polymorphicAggregate(
 *     arm<UUID, Person>("person") { personId },
 *     arm<UUID, Company>("company") { companyId }
 * )
 * ```
 *
 * @param arms one or more [PolymorphicArmDelegate] instances created via [arm]
 */
fun polymorphicAggregate(
    vararg arms: PolymorphicArmDelegate<*, *>
): PolymorphicAggregateDelegate = PolymorphicAggregateDelegate(arms.toList())
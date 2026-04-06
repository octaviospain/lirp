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

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.IdentifiableEntity
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty

/**
 * Abstract base for collection-typed aggregate reference delegates that lazily resolve a group of
 * entities from a bound [Registry].
 *
 * Centralizes all shared behavior between ordered ([AggregateListRefDelegate]) and unique-set
 * ([AggregateSetRefDelegate]) collection references: registry binding, cascade execution
 * (CASCADE/RESTRICT/DETACH/NONE), and the [ReadOnlyProperty] contract.
 *
 * Subclasses provide the concrete collection semantics by implementing [provideIds] and
 * [resolveAll], and by narrowing the [referenceIds] return type.
 *
 * **No bubble-up:** Unlike [AggregateRefDelegate], collection delegates do not support
 * bubble-up event propagation. Collection membership changes are not tracked for event forwarding.
 *
 * **Thread safety:** [registryRef] uses [AtomicReference] for lock-free visibility. [bindRegistry]
 * is called once at entity add time by [RegistryBase] and is visible to all subsequent reads.
 *
 * @param K the type of the referenced entity's ID
 * @param E the referenced entity type
 */
abstract class AbstractAggregateCollectionRefDelegate<K : Comparable<K>, E : IdentifiableEntity<K>> :
    AggregateCollectionRef<K, E> {

    private val log = KotlinLogging.logger {}

    private val registryRef = AtomicReference<Registry<K, E>?>(null)

    @Volatile
    private var context: LirpContext? = null

    /**
     * Returns the current collection of referenced entity IDs.
     * Subclasses return their backing ID collection.
     */
    protected abstract fun provideIds(): Collection<K>

    /**
     * Returns the bound registry, or `null` if [bindRegistry] has not yet been called.
     * Used by subclasses in their [resolveAll] implementations.
     */
    protected fun boundRegistry(): Registry<K, E>? = registryRef.get()

    /**
     * Binds this delegate to the registry that holds the referenced entity type, and associates
     * it with the [LirpContext] of the owning repository. Called automatically by
     * [RegistryBase.bindEntityRefs] when the owning entity is added to a repository.
     */
    internal fun bindRegistry(registry: Registry<K, E>, context: LirpContext) {
        registryRef.set(registry)
        this.context = context
    }

    /**
     * Executes the cascade action for this collection reference when the parent entity is removed.
     *
     * - [CascadeAction.CASCADE]: iterates all referenced IDs and removes each entity from its registry.
     *   If the registry is read-only, logs a warning and skips. Already-absent entities produce a warning.
     * - [CascadeAction.RESTRICT]: scans all registries for entities that reference any of the same target
     *   IDs. Throws [IllegalStateException] if any entity other than [owningEntity] still references them.
     * - [CascadeAction.DETACH]: no-op -- collection delegates have no bubble-up subscription to cancel.
     * - [CascadeAction.NONE]: intentional no-op.
     */
    fun executeCascade(cascadeAction: CascadeAction, owningEntity: Any) {
        when (cascadeAction) {
            CascadeAction.CASCADE -> doCascade()
            CascadeAction.RESTRICT -> doRestrict(owningEntity)
            CascadeAction.DETACH -> { /* no-op — collection delegates have no bubble-up to cancel */ }
            CascadeAction.NONE -> { /* intentional no-op */ }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun doCascade() {
        val repo = registryRef.get()
        if (repo !is Repository<*, *>) {
            log.warn { "Cannot execute CASCADE: bound registry is not a Repository for ids ${provideIds()}" }
            return
        }
        for (id in provideIds().toSet()) {
            val entity = repo.findById(id).orElse(null)
            if (entity == null) {
                log.warn { "Entity(id=$id) already removed by prior cascade" }
                continue
            }
            (repo as Repository<K, E>).remove(entity)
        }
    }

    private fun doRestrict(owningEntity: Any) {
        val ctx = context ?: return
        val targetIds = provideIds().toSet()
        if (targetIds.isEmpty()) return

        val targetClass =
            ctx.registriesSnapshot().entries
                .firstOrNull { (_, reg) -> reg === registryRef.get() }
                ?.key
                ?: return

        @Suppress("UNCHECKED_CAST")
        for ((entityClass, otherRegistry) in ctx.registriesSnapshot()) {
            val accessor = RegistryBase.refAccessorFor(entityClass) ?: continue
            for (entity in otherRegistry) {
                if (entity === owningEntity) continue
                checkSingleRefsNotReferencing(accessor, entity, targetClass, targetIds)
                checkCollectionRefsNotReferencing(accessor, entity, targetClass, targetIds)
            }
        }
    }

    private fun checkSingleRefsNotReferencing(accessor: LirpRefAccessor<*>, entity: Any, targetClass: Class<*>, targetIds: Set<K>) {
        @Suppress("UNCHECKED_CAST")
        for (entry in (accessor as LirpRefAccessor<Any>).entries) {
            val refId = entry.idGetter(entity)
            check(!(entry.referencedClass == targetClass && refId in targetIds)) {
                "Cannot cascade-delete ${targetClass.simpleName}(id=$refId): still referenced by other entities"
            }
        }
    }

    private fun checkCollectionRefsNotReferencing(accessor: LirpRefAccessor<*>, entity: Any, targetClass: Class<*>, targetIds: Set<K>) {
        @Suppress("UNCHECKED_CAST")
        for (collEntry in (accessor as LirpRefAccessor<Any>).collectionEntries) {
            if (collEntry.referencedClass != targetClass) continue
            val ids = collEntry.idsGetter(entity)
            for (id in ids) {
                check(id !in targetIds) {
                    "Cannot cascade-delete ${targetClass.simpleName}(id=$id): still referenced by other entities"
                }
            }
        }
    }

    /**
     * Returns `this` so that the delegate object itself serves as the [AggregateCollectionRef]
     * handle — callers write `entity.collectionProp.resolveAll()` with no extra unwrapping.
     */
    override fun getValue(thisRef: Any?, property: KProperty<*>): AggregateCollectionRef<K, E> = this
}
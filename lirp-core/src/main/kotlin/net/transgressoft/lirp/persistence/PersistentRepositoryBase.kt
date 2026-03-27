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

import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstract foundation for persistent repositories providing entity mutation subscription management,
 * closeable lifecycle, and dirty tracking via the [onDirty] hook.
 *
 * Extends [VolatileRepository] and implements [PersistentRepository], sitting between the in-memory
 * base and concrete storage implementations (JSON, SQL, etc.). Subclasses implement [onDirty] to
 * trigger their specific persistence mechanism whenever the repository state changes.
 *
 * Lifecycle guarantees:
 * - All mutating operations ([add], [remove], [removeAll], [clear]) throw [IllegalStateException]
 *   after the repository is closed.
 * - [close] is idempotent: subsequent calls after the first are safe no-ops.
 * - Entity mutation subscriptions are automatically cancelled on removal or close.
 *
 * @param K The type of entity identifier, must be [Comparable]
 * @param R The type of reactive entity stored in this repository
 */
abstract class PersistentRepositoryBase<K : Comparable<K>, R : ReactiveEntity<K, R>>
    internal constructor(
        context: LirpContext,
        name: String,
        initialEntities: MutableMap<K, R>
    ) : VolatileRepository<K, R>(context, name, initialEntities), PersistentRepository<K, R> {

        /**
         * Public constructor for external subclasses (e.g. in separate modules) that do not
         * have direct access to [LirpContext].
         *
         * Uses [LirpContext.default] for registration and a [java.util.concurrent.ConcurrentHashMap]
         * for in-memory storage.
         *
         * @param name A descriptive name for this repository, used in logging and identification.
         */
        constructor(name: String) : this(LirpContext.default, name, ConcurrentHashMap())

        private val log = KotlinLogging.logger(javaClass.name)

        private val subscriptionsMap: MutableMap<K, LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>>> = ConcurrentHashMap()

        val dirty = AtomicBoolean(false)

        @Volatile
        var closed = false
            private set

        /**
         * Called whenever the repository state has changed and persistence should be triggered.
         *
         * Implementations use this hook to schedule or perform the appropriate storage operation,
         * such as writing to a JSON file or queuing a SQL transaction.
         */
        protected abstract fun onDirty()

        /**
         * Subscribes to mutation events from [entity] and registers the subscription for lifecycle management.
         *
         * The subscription callback guards against post-close invocations to prevent dirty-marking
         * after the repository has been closed and subscriptions are being cancelled.
         */
        protected fun subscribeEntity(entity: R) {
            val subscription =
                entity.subscribe { mutationEvent ->
                    if (!closed) {
                        dirty.set(true)
                        onDirty()
                        onEntityMutated(mutationEvent)
                    }
                }
            subscriptionsMap[entity.id] = subscription
        }

        /**
         * Called after [onDirty] whenever an entity mutation is detected.
         *
         * Subclasses may override this method to react to entity-level mutations with additional
         * logic, such as emitting repository-level [CrudEvent] UPDATE events. The default
         * implementation is a no-op.
         *
         * @param event The [MutationEvent] carrying the entity's previous and current state.
         */
        protected open fun onEntityMutated(event: MutationEvent<K, R>) {
            // Default: no-op. Override to emit repository-level UPDATE events.
        }

        override fun add(entity: R): Boolean {
            check(!closed) { "PersistentRepositoryBase is closed" }
            val added = super.add(entity)
            if (added) {
                subscribeEntity(entity)
                dirty.set(true)
                onDirty()
            }
            return added
        }

        override fun remove(entity: R): Boolean {
            check(!closed) { "PersistentRepositoryBase is closed" }
            return super.remove(entity).also { removed ->
                if (removed) {
                    dirty.set(true)
                    onDirty()
                    val subscription =
                        subscriptionsMap.remove(entity.id)
                            ?: error("Repository should contain a subscription for $entity")
                    subscription.cancel()
                }
            }
        }

        override fun removeAll(entities: Collection<R>): Boolean {
            check(!closed) { "PersistentRepositoryBase is closed" }
            return super.removeAll(entities).also { removed ->
                if (removed) {
                    dirty.set(true)
                    onDirty()
                    entities.forEach {
                        val subscription =
                            subscriptionsMap.remove(it.id)
                                ?: error("Repository should contain a subscription for $it")
                        subscription.cancel()
                    }
                }
            }
        }

        override fun clear() {
            check(!closed) { "PersistentRepositoryBase is closed" }
            super.clear()
            dirty.set(true)
            onDirty()
            subscriptionsMap.forEach { (_, sub) -> sub.cancel() }
            subscriptionsMap.clear()
        }

        override fun close() {
            if (closed) return
            closed = true
            subscriptionsMap.forEach { (_, sub) -> sub.cancel() }
            subscriptionsMap.clear()
            super.close()
        }
    }
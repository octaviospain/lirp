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
import net.transgressoft.lirp.entity.ReactiveEntityBase
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Test entity representing a customer aggregate root.
 *
 * Used as the target of aggregate references in [AggregateRefDeclarationTest] and [AggregateRefResolutionTest].
 */
@Serializable
data class Customer(
    override val id: Int,
    val initialName: String
) : ReactiveEntityBase<Int, Customer>() {
    var name: String by reactiveProperty(initialName)

    override val uniqueId: String get() = "customer-$id"

    override fun clone(): Customer = Customer(id, name)

    fun updateName(newName: String) {
        name = newName
    }

    fun bulkUpdate(newName: String) = mutateAndPublish { name = newName }

    fun suppressEvents() = disableEvents()

    fun restoreEvents() = enableEvents()

    fun <T> silently(action: () -> T): T = withEventsDisabled(action)
}

/**
 * Test entity representing an order that holds a typed reference to a [Customer].
 *
 * The [customer] property demonstrates KSP-generated reference wiring: the [Aggregate]
 * annotation triggers generation of [Order_LirpRefAccessor] at compile time. The [Transient]
 * annotation prevents kotlinx-serialization from attempting to serialize the delegate object.
 *
 * Example usage after adding to a repository:
 * ```kotlin
 * val customer: Optional<Customer> = order.customer.resolve()
 * ```
 */
@Serializable
data class Order(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, Order>() {
    override val uniqueId: String get() = "order-$id"

    @Aggregate(bubbleUp = false)
    val customer by aggregate<Int, Customer> { customerId }

    override fun clone(): Order = copy()
}

/**
 * Test entity representing an order that enables bubble-up propagation from [Customer] mutations.
 *
 * Used in [net.transgressoft.lirp.event.AggregateBubbleUpTest] to verify that mutations on the
 * referenced [Customer] are forwarded to this entity's subscribers as [net.transgressoft.lirp.event.AggregateMutationEvent].
 */
@Serializable
data class BubbleUpOrder(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, BubbleUpOrder>() {
    override val uniqueId: String get() = "bubble-up-order-$id"

    @Aggregate(bubbleUp = true)
    val customer by aggregate<Int, Customer> { customerId }

    override fun clone(): BubbleUpOrder = copy()
}

/**
 * Test entity for transitive non-propagation: B references A with bubble-up=true.
 *
 * Used in [net.transgressoft.lirp.event.AggregateBubbleUpTest] to verify single-level bubble-up.
 * When A mutates, B receives the event. When B re-emits, its own parent should NOT receive it
 * as a further bubble-up (single-level only).
 */
@Serializable
data class EntityA(
    override val id: Int,
    val initialValue: String
) : ReactiveEntityBase<Int, EntityA>() {
    var value: String by reactiveProperty(initialValue)

    override val uniqueId: String get() = "entity-a-$id"

    override fun clone(): EntityA = EntityA(id, value)

    fun updateValue(newValue: String) {
        value = newValue
    }
}

/**
 * Middle entity in transitive-propagation test: references [EntityA] with bubble-up=true.
 */
@Serializable
data class EntityB(
    override val id: Int,
    var entityAId: Int
) : ReactiveEntityBase<Int, EntityB>() {
    override val uniqueId: String get() = "entity-b-$id"

    @Aggregate(bubbleUp = true)
    val refA by aggregate<Int, EntityA> { entityAId }

    override fun clone(): EntityB = copy()
}

/**
 * Top entity in transitive-propagation test: references [EntityB] with bubble-up=true.
 *
 * A mutation in [EntityA] should reach [EntityB]'s subscribers, but NOT [EntityC]'s subscribers —
 * propagation is single-level.
 */
@Serializable
data class EntityC(
    override val id: Int,
    var entityBId: Int
) : ReactiveEntityBase<Int, EntityC>() {
    override val uniqueId: String get() = "entity-c-$id"

    @Aggregate(bubbleUp = true)
    val refB by aggregate<Int, EntityB> { entityBId }

    override fun clone(): EntityC = copy()
}

/**
 * Test entity with CASCADE delete action: removing this entity from its repository also removes
 * the referenced [Customer] from the customer repository.
 */
@Serializable
data class CascadeOrder(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, CascadeOrder>() {
    override val uniqueId: String get() = "cascade-order-$id"

    @Aggregate(onDelete = CascadeAction.CASCADE)
    val customer by aggregate<Int, Customer> { customerId }

    override fun clone(): CascadeOrder = copy()
}

/**
 * Test entity with DETACH delete action and bubble-up enabled: removing this entity cancels the
 * bubble-up subscription but leaves the referenced [Customer] in its repository.
 */
@Serializable
data class DetachOrder(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, DetachOrder>() {
    override val uniqueId: String get() = "detach-order-$id"

    @Aggregate(bubbleUp = true, onDelete = CascadeAction.DETACH)
    val customer by aggregate<Int, Customer> { customerId }

    override fun clone(): DetachOrder = copy()
}

/**
 * Test entity with NONE delete action: removing this entity does nothing to the referenced [Customer].
 */
@Serializable
data class NoneOrder(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, NoneOrder>() {
    override val uniqueId: String get() = "none-order-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    val customer by aggregate<Int, Customer> { customerId }

    override fun clone(): NoneOrder = copy()
}

/**
 * Test repository subclass for [Customer] entities.
 *
 * Annotated with [@LirpRepository][LirpRepository] so the KSP processor generates
 * [CustomerVolatileRepo_LirpRegistryInfo] which triggers auto-registration in
 * the provided context at construction time.
 */
@LirpRepository
class CustomerVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Int, Customer>(context, "Customers") {
    constructor() : this(LirpContext.default)

    fun create(id: Int, name: String): Customer = Customer(id, name).also { add(it) }
}

/**
 * Test repository subclass for [Order] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class OrderVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, Order>(context, "Orders") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, customerId: Int): Order = Order(id, customerId).also { add(it) }
}

/**
 * Test repository subclass for [BubbleUpOrder] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class BubbleUpOrderVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, BubbleUpOrder>(context, "BubbleUpOrders") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, customerId: Int): BubbleUpOrder = BubbleUpOrder(id, customerId).also { add(it) }
}

/**
 * Test repository subclass for [CascadeOrder] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class CascadeOrderVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, CascadeOrder>(context, "CascadeOrders") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, customerId: Int): CascadeOrder = CascadeOrder(id, customerId).also { add(it) }
}

/**
 * Test repository subclass for [DetachOrder] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class DetachOrderVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, DetachOrder>(context, "DetachOrders") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, customerId: Int): DetachOrder = DetachOrder(id, customerId).also { add(it) }
}

/**
 * Test repository subclass for [NoneOrder] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class NoneOrderVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, NoneOrder>(context, "NoneOrders") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, customerId: Int): NoneOrder = NoneOrder(id, customerId).also { add(it) }
}

/**
 * Test repository subclass for [EntityA] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class EntityAVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Int, EntityA>(context, "EntityAs") {
    constructor() : this(LirpContext.default)

    fun create(id: Int, value: String): EntityA = EntityA(id, value).also { add(it) }
}

/**
 * Test repository subclass for [EntityB] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class EntityBVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Int, EntityB>(context, "EntityBs") {
    constructor() : this(LirpContext.default)

    fun create(id: Int, entityAId: Int): EntityB = EntityB(id, entityAId).also { add(it) }
}

/**
 * Test repository subclass for [EntityC] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class EntityCVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Int, EntityC>(context, "EntityCs") {
    constructor() : this(LirpContext.default)

    fun create(id: Int, entityBId: Int): EntityC = EntityC(id, entityBId).also { add(it) }
}

/**
 * Test entity with RESTRICT delete action: removing this entity from its repository is prevented
 * if the referenced [Customer] is still referenced by other entities. Only the owner itself
 * is excluded from the reference check.
 */
@Serializable
data class RestrictOrder(
    override val id: Long,
    var customerId: Int
) : ReactiveEntityBase<Long, RestrictOrder>() {
    override val uniqueId: String get() = "restrict-order-$id"

    @Aggregate(onDelete = CascadeAction.RESTRICT)
    val customer by aggregate<Int, Customer> { customerId }

    override fun clone(): RestrictOrder = copy()
}

/**
 * Test entity forming a cyclic graph: CyclicParent references CyclicChild with CASCADE, and
 * CyclicChild references CyclicParent with CASCADE. Used to test cycle detection.
 */
@Serializable
data class CyclicParent(
    override val id: Long,
    var childId: Long
) : ReactiveEntityBase<Long, CyclicParent>() {
    override val uniqueId: String get() = "cyclic-parent-$id"

    @Aggregate(onDelete = CascadeAction.CASCADE)
    val child by aggregate<Long, CyclicChild> { childId }

    override fun clone(): CyclicParent = copy()
}

/**
 * Test entity forming a cyclic graph: CyclicChild references CyclicParent with CASCADE.
 * Used to test cycle detection in cascade deletion.
 */
@Serializable
data class CyclicChild(
    override val id: Long,
    var parentId: Long
) : ReactiveEntityBase<Long, CyclicChild>() {
    override val uniqueId: String get() = "cyclic-child-$id"

    @Aggregate(onDelete = CascadeAction.CASCADE)
    val parent by aggregate<Long, CyclicParent> { parentId }

    override fun clone(): CyclicChild = copy()
}

/**
 * Test repository subclass for [RestrictOrder] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class RestrictOrderVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, RestrictOrder>(context, "RestrictOrders") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, customerId: Int): RestrictOrder = RestrictOrder(id, customerId).also { add(it) }
}

/**
 * Test repository subclass for [CyclicParent] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class CyclicParentVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, CyclicParent>(context, "CyclicParents") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, childId: Long): CyclicParent = CyclicParent(id, childId).also { add(it) }
}

/**
 * Test repository subclass for [CyclicChild] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class CyclicChildVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, CyclicChild>(context, "CyclicChildren") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, parentId: Long): CyclicChild = CyclicChild(id, parentId).also { add(it) }
}

/**
 * Test entity representing an order whose customer reference can be changed via [changeCustomer].
 *
 * Used in [net.transgressoft.lirp.event.AggregateBubbleUpTest] to verify that bubble-up subscriptions
 * are re-wired to the new referenced entity after the reference ID changes via [mutateAndPublish].
 */
@Serializable
data class MutableRefOrder(
    override val id: Long,
    val initialCustomerId: Int
) : ReactiveEntityBase<Long, MutableRefOrder>() {
    var customerId: Int by reactiveProperty(initialCustomerId)

    override val uniqueId: String get() = "mutable-ref-order-$id"

    @Aggregate(bubbleUp = true)
    val customer by aggregate<Int, Customer> { customerId }

    override fun clone(): MutableRefOrder = MutableRefOrder(id, customerId)

    fun changeCustomer(newId: Int) {
        customerId = newId
    }
}

/**
 * Test repository subclass for [MutableRefOrder] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class MutableRefOrderVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, MutableRefOrder>(context, "MutableRefOrders") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, customerId: Int): MutableRefOrder = MutableRefOrder(id, customerId).also { add(it) }
}

/**
 * Minimal test entity representing a track in a playlist.
 *
 * Used as the referenced entity type in [Playlist] collection reference tests.
 */
@Serializable
data class TestTrack(
    override val id: Int,
    val title: String
) : ReactiveEntityBase<Int, TestTrack>() {
    override val uniqueId: String get() = "track-$id"

    override fun clone(): TestTrack = copy()
}

/**
 * Test entity representing a playlist with an ordered list of track references.
 *
 * The [items] property demonstrates a collection aggregate reference using [aggregateList]:
 * the accessor for collection entries is provided by [Playlist_LirpRefAccessor].
 */
@Serializable
data class Playlist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, Playlist>() {
    override val uniqueId: String get() = "playlist-$id"

    @Aggregate
    @Transient
    val items by aggregateList<Int, TestTrack> { itemIds }

    override fun clone(): Playlist = copy()
}

/**
 * Test entity representing a group of playlists using a set of playlist references.
 *
 * The [playlists] property demonstrates a collection aggregate reference using [aggregateSet]:
 * the accessor for collection entries is provided by [PlaylistGroup_LirpRefAccessor].
 */
@Serializable
data class PlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, PlaylistGroup>() {
    override val uniqueId: String get() = "playlist-group-$id"

    @Aggregate
    @Transient
    val playlists by aggregateSet<Long, Playlist> { playlistIds }

    override fun clone(): PlaylistGroup = copy()
}

/**
 * Test entity with CASCADE delete action on a collection: removing this entity from its repository
 * also removes all referenced [TestTrack] entities.
 */
@Serializable
data class CascadePlaylist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, CascadePlaylist>() {
    override val uniqueId: String get() = "cascade-playlist-$id"

    @Aggregate(onDelete = CascadeAction.CASCADE)
    @Transient
    val items by aggregateList<Int, TestTrack> { itemIds }

    override fun clone(): CascadePlaylist = copy()
}

/**
 * Test entity with RESTRICT delete action on a collection: removing this entity is blocked
 * if any of the referenced [TestTrack] entities are still referenced by other entities.
 */
@Serializable
data class RestrictPlaylist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, RestrictPlaylist>() {
    override val uniqueId: String get() = "restrict-playlist-$id"

    @Aggregate(onDelete = CascadeAction.RESTRICT)
    @Transient
    val items by aggregateList<Int, TestTrack> { itemIds }

    override fun clone(): RestrictPlaylist = copy()
}

/**
 * Test entity with DETACH delete action on a collection: removing this entity does nothing
 * to the referenced [TestTrack] entities (no bubble-up to cancel, just a no-op).
 */
@Serializable
data class DetachPlaylist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, DetachPlaylist>() {
    override val uniqueId: String get() = "detach-playlist-$id"

    @Aggregate(onDelete = CascadeAction.DETACH)
    @Transient
    val items by aggregateList<Int, TestTrack> { itemIds }

    override fun clone(): DetachPlaylist = copy()
}

/**
 * Test entity with NONE delete action on a collection (default): removing this entity does nothing
 * to the referenced [TestTrack] entities.
 */
@Serializable
data class NonePlaylist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, NonePlaylist>() {
    override val uniqueId: String get() = "none-playlist-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    @Transient
    val items by aggregateList<Int, TestTrack> { itemIds }

    override fun clone(): NonePlaylist = copy()
}

/**
 * Test repository for [TestTrack] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class TestTrackVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Int, TestTrack>(context, "TestTracks") {
    constructor() : this(LirpContext.default)

    fun create(id: Int, title: String): TestTrack = TestTrack(id, title).also { add(it) }
}

/**
 * Test repository for [Playlist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class PlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, Playlist>(context, "Playlists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): Playlist = Playlist(id, name, itemIds).also { add(it) }
}

/**
 * Test repository for [PlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class PlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, PlaylistGroup>(context, "PlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): PlaylistGroup = PlaylistGroup(id, playlistIds).also { add(it) }
}

/**
 * Test repository for [CascadePlaylist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class CascadePlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, CascadePlaylist>(context, "CascadePlaylists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): CascadePlaylist = CascadePlaylist(id, name, itemIds).also { add(it) }
}

/**
 * Test repository for [RestrictPlaylist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class RestrictPlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, RestrictPlaylist>(context, "RestrictPlaylists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): RestrictPlaylist = RestrictPlaylist(id, name, itemIds).also { add(it) }
}

/**
 * Test repository for [DetachPlaylist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class DetachPlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, DetachPlaylist>(context, "DetachPlaylists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): DetachPlaylist = DetachPlaylist(id, name, itemIds).also { add(it) }
}

/**
 * Test repository for [NonePlaylist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class NonePlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, NonePlaylist>(context, "NonePlaylists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): NonePlaylist = NonePlaylist(id, name, itemIds).also { add(it) }
}

// --- Set-based cascade entities ---

/**
 * Test entity with CASCADE delete action on a set reference: removing this entity also
 * removes all referenced [Playlist] entities.
 */
@Serializable
data class CascadePlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, CascadePlaylistGroup>() {
    override val uniqueId: String get() = "cascade-group-$id"

    @Aggregate(onDelete = CascadeAction.CASCADE)
    @Transient
    val playlists by aggregateSet<Long, Playlist> { playlistIds }

    override fun clone(): CascadePlaylistGroup = copy()
}

/**
 * Test entity with RESTRICT delete action on a set reference: removing this entity is blocked
 * if any referenced [Playlist] is still referenced by other entities.
 */
@Serializable
data class RestrictPlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, RestrictPlaylistGroup>() {
    override val uniqueId: String get() = "restrict-group-$id"

    @Aggregate(onDelete = CascadeAction.RESTRICT)
    @Transient
    val playlists by aggregateSet<Long, Playlist> { playlistIds }

    override fun clone(): RestrictPlaylistGroup = copy()
}

/**
 * Test entity with DETACH delete action on a set reference.
 */
@Serializable
data class DetachPlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, DetachPlaylistGroup>() {
    override val uniqueId: String get() = "detach-group-$id"

    @Aggregate(onDelete = CascadeAction.DETACH)
    @Transient
    val playlists by aggregateSet<Long, Playlist> { playlistIds }

    override fun clone(): DetachPlaylistGroup = copy()
}

/**
 * Test entity with NONE delete action on a set reference (default).
 */
@Serializable
data class NonePlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, NonePlaylistGroup>() {
    override val uniqueId: String get() = "none-group-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    @Transient
    val playlists by aggregateSet<Long, Playlist> { playlistIds }

    override fun clone(): NonePlaylistGroup = copy()
}

/**
 * Test repository for [CascadePlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class CascadePlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, CascadePlaylistGroup>(context, "CascadePlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): CascadePlaylistGroup = CascadePlaylistGroup(id, playlistIds).also { add(it) }
}

/**
 * Test repository for [RestrictPlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class RestrictPlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, RestrictPlaylistGroup>(context, "RestrictPlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): RestrictPlaylistGroup = RestrictPlaylistGroup(id, playlistIds).also { add(it) }
}

/**
 * Test repository for [DetachPlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class DetachPlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, DetachPlaylistGroup>(context, "DetachPlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): DetachPlaylistGroup = DetachPlaylistGroup(id, playlistIds).also { add(it) }
}

/**
 * Test repository for [NonePlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class NonePlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, NonePlaylistGroup>(context, "NonePlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): NonePlaylistGroup = NonePlaylistGroup(id, playlistIds).also { add(it) }
}
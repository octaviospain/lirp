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

package net.transgressoft.lirp.entity

import net.transgressoft.lirp.event.AggregateMutationEvent
import net.transgressoft.lirp.event.BatchChanged
import net.transgressoft.lirp.event.FieldChange
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.PropertyChanged
import kotlin.reflect.KProperty1

// Extension functions providing statically-typed, per-property observation over the reactive entity
// event stream. All functions delegate to the existing subscribe/event infrastructure and add no
// new publishing, threading, or coroutine machinery.
//
// - subscribeToProperty (value form) — fires typed (old, new) callback for a single named property
// - subscribeToProperty (event form)  — fires a full typed PropertyChanged event for a single named property
// - BatchChanged.changesOf            — returns a typed List<FieldChange<R, V>> for a single named property
// - AggregateMutationEvent.childPropertyChanged — unwraps a child's typed PropertyChanged from a parent's bubble-up event

/**
 * Subscribes to changes of a single reactive property on this entity, invoking [action] with the
 * typed old and new values whenever that property is assigned a new value.
 *
 * The subscription is a filter-then-forward delegate over [ReactiveEntity.subscribe]: internally
 * it subscribes once to all entity events, forwards only [PropertyChanged] events whose
 * [PropertyChanged.property] name matches [property], and casts the values to [V].
 *
 * Property matching uses `.name` comparison rather than reference equality. This is necessary
 * because the Kotlin delegate machinery emits a concrete-class [KProperty1] (e.g.
 * `MutableAudioItem::title`), while callers typically pass an interface-level reference (e.g.
 * `AudioItem::title`). The two are not reference-equal but share the same name.
 *
 * The [check(!isClosed)][ReactiveEntity] guard is inherited from the delegated [subscribe] call —
 * a closed entity throws [IllegalStateException] before the subscription is registered.
 *
 * ```kotlin
 * val sub = audioItem.subscribeToProperty(AudioItem::title) { old, new ->
 *     println("title: $old -> $new")
 * }
 * sub.cancel()
 * ```
 *
 * @param K the entity key type
 * @param R the entity type
 * @param V the property value type, inferred from [property]
 * @param property the property to observe
 * @param action the suspend callback invoked with the old and new typed values
 * @return a subscription handle that can be [cancelled][LirpEventSubscription.cancel] to stop delivery
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>, V> ReactiveEntity<K, R>.subscribeToProperty(
    property: KProperty1<R, V>,
    action: suspend (old: V, new: V) -> Unit
): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
    subscribe { event ->
        if (event is PropertyChanged<*, *, *> && event.property.name == property.name) {
            @Suppress("UNCHECKED_CAST") // Safe: name check ensures only events for this property reach the cast
            action(event.oldValue as V, event.newValue as V)
        }
    }

/**
 * Subscribes to changes of a single reactive property on this entity, invoking [action] with the
 * full typed [PropertyChanged] event whenever that property is assigned a new value.
 *
 * Use this overload when you need metadata carried by the event beyond the old and new values,
 * such as [PropertyChanged.versionAtMutation] or the index-key fields.
 *
 * Property matching uses `.name` comparison (see [subscribeToProperty] value form for rationale).
 * The closed-entity guard is inherited from the delegated [subscribe] call.
 *
 * ```kotlin
 * val sub = audioItem.subscribeToProperty(AudioItem::title) { event ->
 *     println("title changed at version ${event.versionAtMutation}: ${event.oldValue} -> ${event.newValue}")
 * }
 * sub.cancel()
 * ```
 *
 * @param K the entity key type
 * @param R the entity type
 * @param V the property value type, inferred from [property]
 * @param property the property to observe
 * @param action the suspend callback invoked with the full typed [PropertyChanged] event
 * @return a subscription handle that can be [cancelled][LirpEventSubscription.cancel] to stop delivery
 */
@JvmName("subscribeToPropertyEvent")
fun <K : Comparable<K>, R : ReactiveEntity<K, R>, V> ReactiveEntity<K, R>.subscribeToProperty(
    property: KProperty1<R, V>,
    action: suspend (PropertyChanged<K, R, V>) -> Unit
): LirpEventSubscription<in R, MutationEvent.Type, MutationEvent<K, R>> =
    subscribe { event ->
        if (event is PropertyChanged<*, *, *> && event.property.name == property.name) {
            @Suppress("UNCHECKED_CAST") // Safe: name check ensures only events for this property reach the cast
            action(event as PropertyChanged<K, R, V>)
        }
    }

/**
 * Returns a typed list of [FieldChange] entries for [property] from this batch event.
 *
 * A property may appear more than once in a single [BatchChanged] when it is reassigned
 * multiple times within a `mutateAndPublish` block — each reassignment produces its own entry.
 * An empty list is returned when [property] was not touched during the block; this is a correct
 * and expected result, not an error.
 *
 * Property matching uses `.name` comparison for the same reason as [subscribeToProperty].
 *
 * ```kotlin
 * audioItem.subscribe { event ->
 *     if (event is BatchChanged<*, *>) {
 *         @Suppress("UNCHECKED_CAST")
 *         val batch = event as BatchChanged<Int, AudioItem>
 *         val titleChanges = batch.changesOf(AudioItem::title)
 *         titleChanges.forEach { println("title: ${it.oldValue} -> ${it.newValue}") }
 *     }
 * }
 * ```
 *
 * @param R the entity type
 * @param V the property value type, inferred from [property]
 * @param property the property whose changes to retrieve
 * @return a list of typed field changes for [property]; empty when the property was not mutated
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>, V> BatchChanged<K, R>.changesOf(
    property: KProperty1<R, V>
): List<FieldChange<R, V>> =
    changes
        .filter { it.property.name == property.name }
        .map {
            @Suppress("UNCHECKED_CAST") // Safe: name check ensures only FieldChange for this property are cast
            it as FieldChange<R, V>
        }

/**
 * Unwraps a child entity's typed [PropertyChanged] event from this aggregate bubble-up event.
 *
 * Returns `null` when [childEvent][AggregateMutationEvent.childEvent] is not a [PropertyChanged]
 * or when its property name does not match [property]. This avoids requiring callers to
 * `is`-cast [childEvent] themselves.
 *
 * Note: this accessor only unwraps an already-emitted `AggregateMutationEvent`; it does not
 * traverse aggregate references to locate a child entity. To receive typed callbacks from a
 * child directly, call [subscribeToProperty] on the child entity itself.
 *
 * ```kotlin
 * playlist.subscribe { event ->
 *     if (event is AggregateMutationEvent<*, *>) {
 *         @Suppress("UNCHECKED_CAST")
 *         val agg = event as AggregateMutationEvent<Int, BubbleAudioPlaylist>
 *         agg.childPropertyChanged(BubbleAudioTrack::trackName)
 *             ?.let { println("track name: ${it.oldValue} -> ${it.newValue}") }
 *     }
 * }
 * ```
 *
 * @param K the parent entity key type
 * @param R the parent entity type
 * @param CK the child entity key type
 * @param C the child entity type
 * @param V the property value type, inferred from [property]
 * @param property the child property to match
 * @return the typed [PropertyChanged] for [property] if present, or `null`
 */
fun <K : Comparable<K>, R : ReactiveEntity<K, R>, CK : Comparable<CK>, C : ReactiveEntity<CK, C>, V> AggregateMutationEvent<K, R>.childPropertyChanged(
    property: KProperty1<C, V>
): PropertyChanged<CK, C, V>? {
    val child = childEvent
    if (child !is PropertyChanged<*, *, *>) return null
    if (child.property.name != property.name) return null
    @Suppress("UNCHECKED_CAST") // Safe: PropertyChanged type and name check above
    return child as PropertyChanged<CK, C, V>
}
package com.kiva.common.event

import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.event.valueobject.EventSequence

/**
 * Base of an event-sourced aggregate: its state is never set directly, only by [apply]ing events.
 *
 * Two ways in, both through [apply]:
 * - **Something new happens.** A method of the aggregate checks its rules, then calls [raiseEvent] with an event
 *   numbered [nextSequence]. The event is applied and recorded; the repository stores [getRecordedEvents].
 * - **Loading.** The repository reads the stored events and the aggregate replays them with [applyEventStream].
 *   Replayed events are history, not news, so they are not recorded again.
 *
 * ```kotlin
 * class Product private constructor() : EventSourceEntity<ProductDomainEvent>() {
 *     constructor(events: List<ProductDomainEvent>) : this() { applyEventStream(events) }   // loading
 *
 *     fun archive(by: UserId, now: Instant): Result<Product, ArchiveFailure> {
 *         if (status == ARCHIVED) return Result.failure(ArchiveFailure.AlreadyArchived)    // rules first
 *         raiseEvent(ProductArchived(sequence = nextSequence(), ...))                       // then the fact
 *         return Result.success(this)
 *     }
 * }
 * ```
 *
 * Unlike the company base class, the sequence moves on with every raised event. So one command may raise several
 * events (a product created with three colors) and they are numbered 1, 2, 3 instead of all getting the same number,
 * which the event store would refuse.
 */
abstract class EventSourceEntity<E : DomainEvent> {

    private var lastSequence: EventSequence? = null
    private var stream: Pair<EventAggregateName, EventAggregateId>? = null
    private val recordedEvents = mutableListOf<E>()

    /** Events raised since this object was created or loaded, oldest first: what the repository has to store. */
    fun getRecordedEvents(): List<E> = recordedEvents.toList()

    /**
     * The sequence of the last event, stored or just raised: the aggregate's version. A new aggregate not saved yet
     * has the number of events it raised. It moves up by one with every event, so an edit form can say which version
     * it was filled from (`If-Match`) and be refused if it is no longer this one.
     */
    val version: Long get() = lastSequence?.value ?: 0

    /** The sequence the next raised event must carry: one more than the last event, or 1 for a new aggregate. */
    protected fun nextSequence(): EventSequence = lastSequence?.next() ?: EventSequence.FIRST

    /**
     * Changes the state for one event. It runs for new and replayed events alike, so it only sets fields:
     * no rule checks (those come before [raiseEvent]), no clock, no side effects.
     */
    protected abstract fun apply(event: E)

    /** Applies a new event and records it. The rules must already have been checked. */
    protected fun raiseEvent(event: E) {
        requireNext(event)
        apply(event)
        recordedEvents += event
        lastSequence = event.sequence
    }

    /**
     * Rebuilds the state from the stored events. The stream must be the whole history of one aggregate:
     * not empty, numbered 1, 2, 3, ... without gaps. Anything else means broken data, which is a bug: it throws.
     */
    protected fun applyEventStream(events: List<E>) {
        require(events.isNotEmpty()) { "An aggregate can't be rebuilt from an empty stream" }
        require(lastSequence == null) { "applyEventStream is only for rebuilding a new, empty aggregate" }
        events.forEach { event ->
            requireNext(event)
            apply(event)
            lastSequence = event.sequence
        }
    }

    private fun requireNext(event: E) {
        require(event.sequence == nextSequence()) {
            "${event.type.value} has sequence ${event.sequence.value}, expected ${nextSequence().value}"
        }
        val eventStream = event.aggregateName to event.aggregateId
        val current = stream
        if (current == null) {
            stream = eventStream
        } else {
            require(eventStream == current) {
                "${event.type.value} belongs to ${eventStream.first.value}/${eventStream.second.value}, " +
                    "not to ${current.first.value}/${current.second.value}"
            }
        }
    }
}

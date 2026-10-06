package com.kiva.common.event

import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.event.valueobject.EventVersion
import com.kiva.common.valueobject.UserId
import java.time.Instant

/**
 * Something that happened to one aggregate, named in the past tense the way the business says it
 * (`ProductCreated`, `StockReceived`).
 *
 * An event-sourced aggregate is stored as its list of events and rebuilt by replaying them, so an event is a fact:
 * never changed, never deleted. A wrong fact is corrected by a new event, not by editing the old one.
 *
 * Each aggregate has its own sealed base implementing this (`sealed class ProductDomainEvent : DomainEvent`), with one
 * class per event carrying only what changed.
 */
interface DomainEvent {
    val id: EventId
    val type: EventType
    val version: EventVersion
    val aggregateName: EventAggregateName
    val aggregateId: EventAggregateId
    val sequence: EventSequence
    val occurredOn: Instant
    val occurredByUserId: UserId
}

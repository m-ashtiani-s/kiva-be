package com.kiva.infrastructure.persistence.event

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.DomainEvent
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.postgresql.util.PSQLException
import java.time.ZoneOffset
import java.util.UUID

/**
 * How every event-sourced repository reads and writes eventstore_tbl, so each aggregate gets the same two guarantees:
 * - a stream is read by its name and id together, oldest first (product 101 and the stock of variant 101 are
 *   different streams)
 * - a second event with a number already used in its stream becomes [ConcurrentChangeException]
 *
 * Turning rows into events and back is DomainEventSerializer's job; the repository passes the right function in.
 * Runs inside the caller's transaction; it never opens one itself.
 */
object EventStore {

    /** One stream, oldest first, each row made an event by [read]. Empty when nothing was ever stored for it. */
    fun <E : DomainEvent> readStream(
        aggregateName: EventAggregateName,
        aggregateId: EventAggregateId,
        read: (payload: String, payloadVersion: Int, eventType: String) -> E,
    ): List<E> =
        EventStoreTbl.selectAll()
            .where { (EventStoreTbl.aggregateName eq aggregateName.value) and (EventStoreTbl.aggregateId eq aggregateId.value) }
            .orderBy(EventStoreTbl.sequence to SortOrder.ASC)
            .map { read(it[EventStoreTbl.payload], it[EventStoreTbl.payloadVersion], it[EventStoreTbl.eventType]) }

    /**
     * Adds one event, with the [payload] DomainEventSerializer wrote for it. If someone else already saved an event with
     * this number in the stream, the database refuses it and this throws [ConcurrentChangeException]; the transaction
     * then rolls back, so none of the caller's new events stay.
     */
    fun append(event: DomainEvent, payload: String) {
        try {
            EventStoreTbl.insert {
                it[eventId] = UUID.fromString(event.id.value)
                it[aggregateName] = event.aggregateName.value
                it[aggregateId] = event.aggregateId.value
                it[sequence] = event.sequence.value
                it[eventType] = event.type.value
                it[payloadVersion] = event.version.value
                it[EventStoreTbl.payload] = payload
                it[occurredOn] = event.occurredOn.atOffset(ZoneOffset.UTC)
                it[occurredByUserId] = event.occurredByUserId.value
            }
        } catch (e: ExposedSQLException) {
            // Only a repeated number in the stream means "changed by someone else"; any other refusal is a bug.
            if ((e.cause as? PSQLException)?.serverErrorMessage?.constraint == EventStoreTbl.STREAM_SEQUENCE_KEY) {
                throw ConcurrentChangeException(event.aggregateName, event.aggregateId, e)
            }
            throw e
        }
    }
}

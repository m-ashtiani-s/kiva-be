package com.kiva.infrastructure.persistence.event.serializer.payload

import com.kiva.common.event.DomainEvent

/**
 * The stored shape of one kind of event: plain JSON types only (text, numbers, lists), no domain types.
 *
 * It is a separate class from the event so the two can change apart: renaming a field of the event changes nothing
 * stored, while renaming a field here would make every event already stored unreadable. So a field here is never
 * renamed or removed; a real change of shape is a new payload version.
 */
interface EventPayload<out T : DomainEvent> {
    fun toDomainEvent(): T
}

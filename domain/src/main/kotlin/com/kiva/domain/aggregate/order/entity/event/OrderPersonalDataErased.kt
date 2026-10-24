package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.ErasureReason
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import java.time.Instant

/**
 * The order's personal data was erased, for [reason] (roadmap phase 14, decision 2): from here on its address is only
 * its province and city, its messenger only the channel, and it has no customer note and no gift message. Its money,
 * lines, tracking code and dates stay: it is still a record for the books.
 *
 * The events before it are not rewritten (the event store only grows): replaying the stream gives the erased order
 * again, because this event comes last. Its customer doesn't see it: no timeline entry.
 */
data class OrderPersonalDataErased(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val reason: ErasureReason,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderPersonalDataErased")
    }
}

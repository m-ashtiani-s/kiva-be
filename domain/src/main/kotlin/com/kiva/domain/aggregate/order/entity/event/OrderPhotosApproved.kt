package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import java.time.Instant

/**
 * The photos sent are approved: the customer said «همونه!», or ([automatic]) their time to answer ran out and the job
 * approved them for the system. The order stays PHOTO_SENT (the contract has no «approved» status): approval only lets
 * it ship.
 */
data class OrderPhotosApproved(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val automatic: Boolean,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderPhotosApproved")
    }
}

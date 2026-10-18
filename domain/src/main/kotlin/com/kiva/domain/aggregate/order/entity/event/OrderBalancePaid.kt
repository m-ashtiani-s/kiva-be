package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import java.time.Instant

/**
 * What the order owed after a change to a dearer color was paid, by the attempt [paymentId] (purpose BALANCE): nothing
 * is owed any more, and [amount] adds to what was paid. The status doesn't move.
 */
data class OrderBalancePaid(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val paymentId: PaymentPublicId,
    val amount: Money,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderBalancePaid")
    }
}

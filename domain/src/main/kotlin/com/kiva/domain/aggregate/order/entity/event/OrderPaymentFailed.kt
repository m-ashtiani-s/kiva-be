package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import java.time.Instant

/**
 * A payment attempt of an unpaid order failed or was cancelled. One per attempt that failed, so the timeline shows
 * each («پرداخت ناموفق» at 10:02, again at 10:05); the order can still be paid until its `holdUntil`.
 */
data class OrderPaymentFailed(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val paymentId: PaymentPublicId,
    val reason: PaymentFailureReason,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderPaymentFailed")
    }
}

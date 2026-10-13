package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import java.time.Instant

/**
 * The money came in. When it did is [occurredOn].
 *
 * The event says itself what the order became, because that was decided when it was paid, with what was true then
 * (round 2: whether the customer's reservation was still holding). Replaying it later must give the same answer, and
 * replaying never checks a rule.
 *
 * @property paymentId the attempt that paid it; null for an order with nothing to pay, paid the moment it was placed.
 * @property amount exactly the order's payable.
 * @property heldForReservation the order is held with «رزرو ۴ روزه» (RESERVED); otherwise it is being prepared
 *   (PROCESSING).
 * @property reservationGroupId the reservation group it is held in (one it started, or joined); null when it isn't in
 *   one, and in events stored before step 19.
 */
data class OrderPaid(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val paymentId: PaymentPublicId?,
    val amount: Money,
    val heldForReservation: Boolean,
    val reservationGroupId: ReservationGroupId? = null,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderPaid")
    }
}

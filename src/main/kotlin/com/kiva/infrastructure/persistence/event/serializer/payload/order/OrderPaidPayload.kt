package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderPaid
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * [paymentId] is null for an order with nothing to pay; [amount] in toman. [reservationGroupId] came with step 19: an
 * event stored before has none, and reads back as null.
 */
@Serializable
data class OrderPaidPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val paymentId: String?,
    val amount: Long,
    val heldForReservation: Boolean,
    val reservationGroupId: Long? = null,
) : EventPayload<OrderPaid> {

    constructor(event: OrderPaid) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        paymentId = event.paymentId?.value,
        amount = event.amount.toman,
        heldForReservation = event.heldForReservation,
        reservationGroupId = event.reservationGroupId?.value,
    )

    override fun toDomainEvent(): OrderPaid = OrderPaid(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        paymentId = paymentId?.let { PaymentPublicId.constructFromPersisted(it) },
        amount = Money.constructFromPersisted(amount),
        heldForReservation = heldForReservation,
        reservationGroupId = reservationGroupId?.let { ReservationGroupId.constructFromPersisted(it) },
        id = EventId.constructFromPersisted(id),
    )
}

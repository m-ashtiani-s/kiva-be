package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderBalancePaid
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [amount] in toman. */
@Serializable
data class OrderBalancePaidPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val paymentId: String,
    val amount: Long,
) : EventPayload<OrderBalancePaid> {

    constructor(event: OrderBalancePaid) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        paymentId = event.paymentId.value,
        amount = event.amount.toman,
    )

    override fun toDomainEvent(): OrderBalancePaid = OrderBalancePaid(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        paymentId = PaymentPublicId.constructFromPersisted(paymentId),
        amount = Money.constructFromPersisted(amount),
        id = EventId.constructFromPersisted(id),
    )
}

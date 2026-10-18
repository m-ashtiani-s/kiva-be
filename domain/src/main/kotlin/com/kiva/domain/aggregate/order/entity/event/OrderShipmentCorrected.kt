package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import java.time.Instant

/**
 * A mistake in a shipped order's parcel was put right (`PUT …/shipment` again on a SHIPPED order): its tracking code,
 * when it was handed over, or whether it is listed daily. Nothing else changes: no stock, no SMS.
 */
data class OrderShipmentCorrected(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val trackingCode: TrackingCode,
    val shippedOn: Instant,
    val showInDailyList: Boolean,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderShipmentCorrected")
    }
}

package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * After seeing the photos, the customer asked for a change (`POST /me/orders/{code}/media-feedback`, REQUEST_CHANGE):
 * CHANGE_REQUESTED, its photos too, until staff resolve it. What they asked is kept as they asked it (a ChangeRequest).
 *
 * @property lineId the line it is about; null for OTHER.
 * @property desiredVariantId the color wanted instead; only for COLOR and MODEL.
 * @property note what they wrote; personal, never logged.
 */
data class OrderChangeRequested(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val changeType: ChangeType,
    val lineId: Int?,
    val desiredVariantId: VariantId?,
    val note: StatusNote?,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    override fun toString(): String =
        "OrderChangeRequested(orderId=${orderId.value}, sequence=${sequence.value}, changeType=$changeType, lineId=$lineId, desiredVariantId=${desiredVariantId?.value})"

    companion object {
        val TYPE = EventType("OrderChangeRequested")
    }
}

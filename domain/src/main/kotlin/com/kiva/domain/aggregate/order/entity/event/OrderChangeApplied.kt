package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * Staff carried the customer's change request out (after OrderLineReplaced or OrderLineRemoved, or alone for OTHER):
 * back to PROCESSING, its photos WAITING for a new batch. The money of the change is settled here
 * (ChangeRequestPolicy.settle): what the order now owes is [balanceDueAfter]; what is left over goes back as a refund
 * of its own row.
 *
 * @property variantId the color that really took the line's place (COLOR, MODEL); null for the others.
 * @property note for the customer (their timeline, `feedback.resolution.note`); never logged.
 */
data class OrderChangeApplied(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val changeType: ChangeType,
    val variantId: VariantId?,
    val note: StatusNote?,
    val balanceDueAfter: Money,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    override fun toString(): String =
        "OrderChangeApplied(orderId=${orderId.value}, sequence=${sequence.value}, changeType=$changeType, variantId=${variantId?.value}, balanceDueAfter=$balanceDueAfter)"

    companion object {
        val TYPE = EventType("OrderChangeApplied")
    }
}

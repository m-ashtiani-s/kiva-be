package com.kiva.domain.aggregate.order.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.PhotoMessage
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Instant

/**
 * The operator sent the draft to the customer, by hand on their messenger, and recorded it (`…/pre-shipment-media/send`):
 * the draft becomes the batch the customer sees, the order is PHOTO_SENT, and the photos wait for an answer until
 * [feedbackDeadline]. When they were sent is [occurredOn].
 *
 * @property batchNo 1 for the first photos of the order, 2 after a change request (phase 3), …
 * @property channel the messenger actually used: the order's own, unless the operator said otherwise.
 * @property manual always true: there is no messenger bot (roadmap section 1, row 8).
 * @property message what the operator wrote with them; only kept.
 * @property feedbackDeadline decided when sent ([occurredOn] + PreShipmentPolicy.FEEDBACK_HOURS then) and kept, so a later
 *   change of the rule never moves it.
 */
data class OrderPhotosSent(
    override val orderId: OrderId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val batchNo: Int,
    val channel: MessengerChannel,
    val manual: Boolean,
    val message: PhotoMessage?,
    val feedbackDeadline: Instant,
    override val id: EventId = EventId.generate(),
) : OrderDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("OrderPhotosSent")
    }
}

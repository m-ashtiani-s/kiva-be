package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosSent
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.PhotoMessage
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

/** [message] is the operator's, personal: never logged ([toString]). */
@Serializable
data class OrderPhotosSentPayload(
    val id: String,
    val orderId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val batchNo: Int,
    val channel: String,
    val manual: Boolean,
    val message: String?,
    val feedbackDeadline: String,
) : EventPayload<OrderPhotosSent> {

    constructor(event: OrderPhotosSent) : this(
        id = event.id.value,
        orderId = event.orderId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        batchNo = event.batchNo,
        channel = event.channel.name,
        manual = event.manual,
        message = event.message?.value,
        feedbackDeadline = event.feedbackDeadline.toString(),
    )

    override fun toDomainEvent(): OrderPhotosSent = OrderPhotosSent(
        orderId = OrderId.constructFromPersisted(orderId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        batchNo = batchNo,
        channel = MessengerChannel.valueOf(channel),
        manual = manual,
        message = message?.let { PhotoMessage.constructFromPersisted(it) },
        feedbackDeadline = Instant.parse(feedbackDeadline),
        id = EventId.constructFromPersisted(id),
    )

    override fun toString(): String = "OrderPhotosSentPayload(orderId=$orderId, sequence=$sequence, batchNo=$batchNo)"
}

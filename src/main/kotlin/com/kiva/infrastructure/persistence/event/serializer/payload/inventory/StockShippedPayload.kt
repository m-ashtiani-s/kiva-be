package com.kiva.infrastructure.persistence.event.serializer.payload.inventory

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.entity.event.StockShipped
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.serializer.payload.EventPayload
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class StockShippedPayload(
    val id: String,
    val variantId: Long,
    val sequence: Long,
    val occurredOn: String,
    val occurredByUserId: Long,
    val orderCode: String,
    val quantity: Int,
) : EventPayload<StockShipped> {

    constructor(event: StockShipped) : this(
        id = event.id.value,
        variantId = event.variantId.value,
        sequence = event.sequence.value,
        occurredOn = event.occurredOn.toString(),
        occurredByUserId = event.occurredByUserId.value,
        orderCode = event.orderCode.value,
        quantity = event.quantity.value,
    )

    override fun toDomainEvent(): StockShipped = StockShipped(
        variantId = VariantId.constructFromPersisted(variantId),
        sequence = EventSequence.constructFromPersisted(sequence),
        occurredOn = Instant.parse(occurredOn),
        occurredByUserId = UserId.constructFromPersisted(occurredByUserId),
        orderCode = OrderCode.constructFromPersisted(orderCode),
        quantity = Quantity.constructFromPersisted(quantity),
        id = EventId.constructFromPersisted(id),
    )
}

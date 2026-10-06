package com.kiva.domain.aggregate.product.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * A color's gallery was replaced. It carries the whole new list in order (the first is the card photo), because
 * staff edit the gallery as a whole: add, remove and reorder at once.
 */
data class VariantMediaReplaced(
    override val productId: ProductId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val variantId: VariantId,
    val media: List<ProductMedia>,
    override val id: EventId = EventId.generate(),
) : ProductDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("VariantMediaReplaced")
    }
}

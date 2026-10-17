package com.kiva.domain.aggregate.product.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Instant

/**
 * A draft was set to go into the shop at [publishAt] (the job publishes it then). It stays a draft until that moment; a
 * new schedule replaces the one before.
 */
data class ProductPublishScheduled(
    override val productId: ProductId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val publishAt: Instant,
    override val id: EventId = EventId.generate(),
) : ProductDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("ProductPublishScheduled")
    }
}

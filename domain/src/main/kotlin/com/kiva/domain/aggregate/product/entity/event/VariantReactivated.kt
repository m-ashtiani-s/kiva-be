package com.kiva.domain.aggregate.product.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * A deactivated color came back into the shop. Needed because a color can't be added twice: without this a color
 * once deactivated could never be sold again.
 *
 * @property isDefault it became the default, because no other active color was.
 */
data class VariantReactivated(
    override val productId: ProductId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val variantId: VariantId,
    val isDefault: Boolean,
    override val id: EventId = EventId.generate(),
) : ProductDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("VariantReactivated")
    }
}

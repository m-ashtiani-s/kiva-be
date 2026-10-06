package com.kiva.domain.aggregate.product.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * A color was taken out of the shop. It stays on the product (past orders point to it) and can come back with
 * [VariantReactivated].
 *
 * @property newDefaultVariantId the color that became the default because this one was it; null when this color
 *   was not the default or no other active color is left.
 */
data class VariantDeactivated(
    override val productId: ProductId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val variantId: VariantId,
    val newDefaultVariantId: VariantId?,
    override val id: EventId = EventId.generate(),
) : ProductDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("VariantDeactivated")
    }
}

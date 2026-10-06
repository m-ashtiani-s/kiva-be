package com.kiva.domain.aggregate.product.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * Staff changed a color's own price, its position or made it the default.
 *
 * @property isDefault the color is the default after this; when true, the color that was the default is not any more.
 * @property ownPriceTag null when the color follows the product's price tag.
 */
data class VariantChanged(
    override val productId: ProductId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val variantId: VariantId,
    val ownPriceTag: PriceTag?,
    val sortOrder: Int,
    val isDefault: Boolean,
    override val id: EventId = EventId.generate(),
) : ProductDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("VariantChanged")
    }
}

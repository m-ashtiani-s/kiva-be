package com.kiva.domain.aggregate.product.entity.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * A color was added to the product. A new color is always active.
 *
 * @property isDefault this color became the default one; the color that was the default until now is not any more.
 * @property ownPriceTag null when the color simply follows the product's price.
 */
data class VariantAdded(
    override val productId: ProductId,
    override val sequence: EventSequence,
    override val occurredOn: Instant,
    override val occurredByUserId: UserId,
    val variantId: VariantId,
    val colorKey: ColorKey,
    val sku: Sku,
    val ownPriceTag: PriceTag?,
    val isDefault: Boolean,
    val sortOrder: Int,
    val media: List<ProductMedia>,
    override val id: EventId = EventId.generate(),
) : ProductDomainEvent() {
    override val type: EventType get() = TYPE

    companion object {
        val TYPE = EventType("VariantAdded")
    }
}

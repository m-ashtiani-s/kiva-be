package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.OrderItemSnapshot
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.event.serializer.payload.product.PriceTagPayload
import kotlinx.serialization.Serializable

/**
 * An [OrderLine]: what was bought, as it was then.
 *
 * Stored in the order's OrderPlaced event, and in the same shape in the order read model (orderqm_tbl), so both read
 * back through this class. Never renamed: see EventPayload.
 */
@Serializable
data class OrderLinePayload(
    val lineId: Int,
    val productId: Long,
    val variantId: Long,
    val name: String,
    val slug: String,
    val sku: String,
    val colorKey: String,
    val colorName: String,
    val colorHex: String,
    val imageUrl: String?,
    val quantity: Int,
    val priceTag: PriceTagPayload,
    val reservable: Boolean,
    val returnable: Boolean,
) {
    constructor(line: OrderLine) : this(
        lineId = line.lineId,
        productId = line.productId.value,
        variantId = line.variantId.value,
        name = line.item.name.value,
        slug = line.item.slug.value,
        sku = line.item.sku.value,
        colorKey = line.item.color.key.value,
        colorName = line.item.color.name,
        colorHex = line.item.color.hex,
        imageUrl = line.item.imageUrl,
        quantity = line.quantity.value,
        priceTag = PriceTagPayload(line.priceTag),
        reservable = line.reservable,
        returnable = line.returnable,
    )

    fun toDomain(): OrderLine = OrderLine.constructFromPersisted(
        lineId = lineId,
        productId = ProductId.constructFromPersisted(productId),
        variantId = VariantId.constructFromPersisted(variantId),
        item = OrderItemSnapshot(
            name = ProductName.constructFromPersisted(name),
            slug = Slug.constructFromPersisted(slug),
            sku = Sku.constructFromPersisted(sku),
            color = OrderItemSnapshot.Color(ColorKey.constructFromPersisted(colorKey), colorName, colorHex),
            imageUrl = imageUrl,
        ),
        quantity = Quantity.constructFromPersisted(quantity),
        priceTag = priceTag.toDomain(),
        reservable = reservable,
        returnable = returnable,
    )
}

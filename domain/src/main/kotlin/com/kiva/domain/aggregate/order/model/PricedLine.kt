package com.kiva.domain.aggregate.order.model

import com.kiva.common.valueobject.Quantity
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag

/**
 * One line to be priced: a cart line, or a line of an order being placed. OrderPricing's input.
 *
 * @property priceTag what one costs now (the color's own price tag, or the product's), read from the catalog, never
 *   from the client.
 * @property purchasable it can be bought right now (in the shop and in stock). A line that can't stays in the cart and
 *   in the counts, but not in any amount of money.
 */
data class PricedLine(
    val priceTag: PriceTag,
    val quantity: Quantity,
    val purchasable: Boolean,
)

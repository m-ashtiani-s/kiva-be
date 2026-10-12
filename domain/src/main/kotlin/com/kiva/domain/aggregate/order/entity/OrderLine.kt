package com.kiva.domain.aggregate.order.entity

import com.kiva.common.valueobject.Quantity
import com.kiva.domain.aggregate.order.model.PricedLine
import com.kiva.domain.aggregate.order.model.policy.OrderPricing
import com.kiva.domain.aggregate.order.model.valueobject.LineTotals
import com.kiva.domain.aggregate.order.model.valueobject.OrderItemSnapshot
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * One color of a placed [Order], copied when the order was placed: what was bought, how many, and at what price
 * (the contract's OrderItem). Nothing changes a line in this phase. Its identity is [lineId], 1 to n within its order
 * (the contract's `OrderItem.id`): unique only inside the order.
 *
 * @property priceTag what one cost then: the price paid and the crossed-out price shown.
 * @property reservable it could be held by «رزرو ۴ روزه» then (round 2).
 * @property returnable it could be returned then (the returns phase), whatever the product says later.
 */
@ConsistentCopyVisibility
data class OrderLine internal constructor(
    val lineId: Int,
    val productId: ProductId,
    val variantId: VariantId,
    val item: OrderItemSnapshot,
    val quantity: Quantity,
    val priceTag: PriceTag,
    val reservable: Boolean,
    val returnable: Boolean,
) {
    init {
        require(lineId >= 1) { "Invalid order line id $lineId" }
    }

    /** The line's amounts, from the same calculation as the cart's and the checkout's. */
    val totals: LineTotals get() = OrderPricing.lineTotalsOf(PricedLine(priceTag, quantity, purchasable = true))

    override fun toString(): String = "OrderLine(lineId=$lineId, variantId=${variantId.value}, quantity=${quantity.value})"

    companion object {
        fun constructFromPersisted(
            lineId: Int,
            productId: ProductId,
            variantId: VariantId,
            item: OrderItemSnapshot,
            quantity: Quantity,
            priceTag: PriceTag,
            reservable: Boolean,
            returnable: Boolean,
        ): OrderLine = OrderLine(lineId, productId, variantId, item, quantity, priceTag, reservable, returnable)
    }
}

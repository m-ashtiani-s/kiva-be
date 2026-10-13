package com.kiva.domain.aggregate.cart.entity

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * One color in a [Cart]. Lives inside the cart and only the cart changes it, because how many it may hold depends on
 * the product's other colors in the same cart. Immutable: a change is a new copy put in its place.
 *
 * It keeps no price and no stock of its own: those are read from the catalog every time. What it keeps is what the
 * shopper **saw** last, so the next look can tell them what changed (CartIssue).
 *
 * Its identity is [itemId].
 *
 * @property seenUnitPrice the price of one when the shopper last saw the line.
 * @property seenPurchasable it could be bought when the shopper last saw it.
 * @property addedOn when the color came into the cart: lines are listed by it, and the older one keeps its share when
 *   the product's limit has to be cut.
 */
@ConsistentCopyVisibility
data class CartLine internal constructor(
    val itemId: CartItemId,
    val variantId: VariantId,
    val quantity: CartQuantity,
    val seenUnitPrice: Money,
    val seenPurchasable: Boolean,
    val addedOn: Instant,
) {
    override fun equals(other: Any?): Boolean = other is CartLine && other.itemId == itemId

    override fun hashCode(): Int = itemId.hashCode()

    override fun toString(): String = "CartLine(itemId=${itemId.value}, variantId=${variantId.value}, quantity=${quantity.value})"

    companion object {
        fun constructFromPersisted(
            itemId: CartItemId,
            variantId: VariantId,
            quantity: CartQuantity,
            seenUnitPrice: Money,
            seenPurchasable: Boolean,
            addedOn: Instant,
        ): CartLine = CartLine(itemId, variantId, quantity, seenUnitPrice, seenPurchasable, addedOn)
    }
}

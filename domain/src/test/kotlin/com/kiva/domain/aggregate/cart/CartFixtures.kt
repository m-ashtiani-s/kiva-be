package com.kiva.domain.aggregate.cart

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.entity.CartLine
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.cart.model.valueobject.CartToken
import com.kiva.domain.aggregate.cart.repository.CartIdGenerator
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * Ready-made values for the cart tests, with two bags of the prototype:
 * - «کیف دستی آیدا» (product 3): caramel 301, black 302, red 303; 2,790,000 instead of 3,290,000; 3 of each left;
 * - «کراس‌بادی نیلا» (product 2): black 201, olive 205; 1,450,000 instead of 1,750,000; 10 of each left.
 * Both have the default limit of 5 per order.
 */
object CartFixtures {

    val now: Instant = Instant.parse("2026-10-12T08:00:00Z")

    /** [minutes] after [now]: lines added at different times. */
    fun at(minutes: Long): Instant = now.plusSeconds(minutes * 60)

    const val AIDA_CARAMEL = 301L
    const val AIDA_BLACK = 302L
    const val AIDA_RED = 303L
    const val NILA_BLACK = 201L
    const val NILA_OLIVE = 205L

    fun variant(id: Long): VariantId = VariantId.constructFromPersisted(id)

    fun quantity(value: Int): CartQuantity = CartQuantity.constructFromPersisted(value)

    fun itemId(n: Int): CartItemId = CartItemId.constructFromPersisted("ci_" + n.toString().padStart(10, '0'))

    fun toman(amount: Long): Money = Money.constructFromPersisted(amount)

    fun offer(
        variantId: Long,
        productId: Long,
        price: Long,
        compareAt: Long? = null,
        available: Int = 10,
        maxPerOrder: Int = 5,
        inShop: Boolean = true,
        reservable: Boolean = true,
        returnable: Boolean = true,
    ): PurchaseOffer = PurchaseOffer(
        variantId = variant(variantId),
        productId = ProductId.constructFromPersisted(productId),
        categoryId = CategoryId.constructFromPersisted(1),
        priceTag = PriceTag.constructFromPersisted(toman(price), compareAt?.let(::toman)),
        available = available,
        maxPerOrder = MaxPerOrder.constructFromPersisted(maxPerOrder),
        inShop = inShop,
        reservable = reservable,
        returnable = returnable,
    )

    fun aida(color: Long, available: Int = 3, maxPerOrder: Int = 5, price: Long = 2_790_000, inShop: Boolean = true, reservable: Boolean = true) =
        offer(color, productId = 3, price = price, compareAt = 3_290_000, available = available, maxPerOrder = maxPerOrder, inShop = inShop, reservable = reservable)

    fun nila(color: Long, available: Int = 10, maxPerOrder: Int = 5, price: Long = 1_450_000, inShop: Boolean = true) =
        offer(color, productId = 2, price = price, compareAt = 1_750_000, available = available, maxPerOrder = maxPerOrder, inShop = inShop)

    /** The whole shop as these tests know it, with the defaults above; pass changed offers to replace them. */
    fun offers(vararg changed: PurchaseOffer): Map<VariantId, PurchaseOffer> {
        val shop = listOf(aida(AIDA_CARAMEL), aida(AIDA_BLACK), aida(AIDA_RED), nila(NILA_BLACK), nila(NILA_OLIVE))
        return (shop + changed).associateBy { it.variantId }
    }

    fun line(
        itemId: CartItemId,
        variantId: Long,
        quantity: Int,
        seenUnitPrice: Long,
        seenPurchasable: Boolean = true,
        addedOn: Instant = now,
    ): CartLine = CartLine.constructFromPersisted(itemId, variant(variantId), quantity(quantity), toman(seenUnitPrice), seenPurchasable, addedOn)

    fun userCart(vararg lines: CartLine, method: ShippingMethodCode = ShippingMethodCode.POST, reserve: Boolean = false): Cart =
        Cart.constructFromPersisted(
            CartPublicId.constructFromPersisted("cart_user000001"), CartOwner.User(UserId.constructFromPersisted(7)),
            lines.toList(), method, reserve, now, now,
        )

    fun guestCart(vararg lines: CartLine, method: ShippingMethodCode = ShippingMethodCode.POST, reserve: Boolean = false): Cart =
        Cart.constructFromPersisted(
            CartPublicId.constructFromPersisted("cart_guest00001"), CartOwner.Guest(TokenHash(ByteArray(TokenHash.LENGTH))),
            lines.toList(), method, reserve, now, now,
        )

    /** Hands out ci_0000000101, ci_0000000102, … and cart_0000000001, …; tokens are fixed and valid. */
    class SequentialCartIds : CartIdGenerator {
        private var item = 100
        private var cart = 0

        override fun nextCartPublicId(): CartPublicId = CartPublicId.constructFromPersisted("cart_" + (++cart).toString().padStart(10, '0'))

        override fun nextCartItemId(): CartItemId = itemId(++item)

        override fun nextGuestToken(): CartToken = CartToken.makeNew(TOKEN).getOrNull()!!
    }

    /** A well-formed guest token: `gct_` and 43 base64url characters. */
    const val TOKEN = "gct_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE"
}

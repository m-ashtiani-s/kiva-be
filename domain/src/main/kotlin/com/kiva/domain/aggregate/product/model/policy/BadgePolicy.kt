package com.kiva.domain.aggregate.product.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import java.time.Instant

/** Which badges a product card or a color wears. Only the codes; the Persian text is the HTTP layer's job. */
object BadgePolicy {

    /** «جدید» until [newUntil]; never when it is not set. [now] is passed in, so the domain never reads a clock. */
    fun isNew(newUntil: Instant?, now: Instant): Boolean = newUntil != null && now < newUntil

    /**
     * The badges, in the contract's order. Like the prototype's product card, a sold-out bag shows SOLD_OUT and drops
     * the badges that invite buying (sale, free shipping), but keeps the ones that describe it (new, video).
     *
     * The caller decides which color the inputs belong to (the card's color, or the one on the product page).
     */
    fun badgesOf(
        isNew: Boolean,
        priceTag: PriceTag,
        hasVideo: Boolean,
        stock: StockStatus,
        freeShippingThreshold: Money,
    ): List<BadgeCode> {
        val soldOut = stock == StockStatus.OUT_OF_STOCK
        return buildList {
            if (isNew) add(BadgeCode.NEW)
            if (!soldOut && PricePolicy.isOnSale(priceTag)) add(BadgeCode.SALE)
            if (hasVideo) add(BadgeCode.HAS_VIDEO)
            if (!soldOut && PricePolicy.qualifiesForFreeShipping(priceTag, freeShippingThreshold)) add(BadgeCode.FREE_SHIPPING)
            if (soldOut) add(BadgeCode.SOLD_OUT)
            if (stock == StockStatus.LOW_STOCK) add(BadgeCode.LOW_STOCK)
        }
    }
}

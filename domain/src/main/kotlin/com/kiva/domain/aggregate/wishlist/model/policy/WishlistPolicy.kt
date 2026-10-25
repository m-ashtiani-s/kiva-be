package com.kiva.domain.aggregate.wishlist.model.policy

import com.kiva.common.valueobject.Money

/** The wishlist's numbers (roadmap section 14.2, decisions 2 and 8). */
object WishlistPolicy {

    /** At most this many products in one list, hidden drafts counted (WISHLIST_LIMIT_REACHED). */
    const val MAX_ITEMS = 200

    /**
     * «از وقتی ذخیره کردی ۱۵٪ ارزون‌تر شده»: how much cheaper the product is now than when it was saved. Only a drop of
     * at least 1% (floored) counts; the same price, a dearer one or a drop under 1% is none.
     */
    fun priceDropOf(priceAtAdd: Money, current: Money): PriceDrop? {
        if (current >= priceAtAdd) return null
        val amount = priceAtAdd - current
        val percent = (amount.toman * 100 / priceAtAdd.toman).toInt()
        return if (percent >= 1) PriceDrop(amount, percent) else null
    }

    /** @property percent whole percent, floored. */
    data class PriceDrop(val amount: Money, val percent: Int)
}

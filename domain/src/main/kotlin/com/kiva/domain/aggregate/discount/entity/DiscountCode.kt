package com.kiva.domain.aggregate.discount.entity

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Instant

/**
 * A discount code (roadmap section 14, the contract's DiscountCode): what it takes off, from what, for whom and how many
 * times. Kept as a row; until the panel's discount codes are built, codes are made by SQL (the dev seeder makes three).
 * How many times it was used is not here: its redemptions are counted (DiscountRedemption), so the count can't drift.
 *
 * Whether it applies to a cart or an order is DiscountDomainService's, in the contract's order; the money is
 * DiscountPolicy's.
 *
 * @property value the percent (1 to 100) for PERCENT, the toman for FIXED.
 * @property label what the cart shows (`AppliedDiscount.label`), «۱۰٪ تخفیف روی کل سبد».
 * @property minSubtotal the cart's subtotal must be at least this.
 * @property maxDiscount the most a PERCENT code takes off.
 * @property startsAt and [endsAt]: it works from [startsAt] and until (not at) [endsAt]; either may be open.
 * @property usageLimit how many orders may use it in all; [perUserLimit] how many of one customer's. Held uses count.
 * @property firstOrderOnly only for a customer with no paid order yet (WELCOME).
 * @property excludeSaleItems lines already on sale (with a crossed-out price) are not covered.
 * @property categoryIds and [productIds]: when either has something, only the lines of those categories or products are
 *   covered; both empty, every line.
 */
class DiscountCode private constructor(
    val id: DiscountCodeId,
    val code: DiscountCodeText,
    val type: DiscountType,
    val value: Int,
    val label: String,
    val minSubtotal: Money?,
    val maxDiscount: Money?,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val usageLimit: Int?,
    val perUserLimit: Int?,
    val firstOrderOnly: Boolean,
    val requiresLogin: Boolean,
    val excludeSaleItems: Boolean,
    val categoryIds: Set<CategoryId>,
    val productIds: Set<ProductId>,
    val isActive: Boolean,
) {
    // The table's CHECKs say the same: a broken row is a bug.
    init {
        when (type) {
            DiscountType.PERCENT -> require(value in 1..100) { "Code ${code.value}: a percent of $value" }
            DiscountType.FIXED -> require(value > 0) { "Code ${code.value}: a fixed amount of $value" }
        }
        require(label.isNotBlank()) { "Code ${code.value} has no label" }
        require(usageLimit == null || usageLimit > 0) { "Code ${code.value}: usage limit $usageLimit" }
        require(perUserLimit == null || perUserLimit > 0) { "Code ${code.value}: per-customer limit $perUserLimit" }
        require(startsAt == null || endsAt == null || endsAt.isAfter(startsAt)) { "Code ${code.value} ends before it starts" }
    }

    /**
     * Only for someone known: a guest's uses and orders can't be counted, so a code with a per-customer limit, for first
     * orders, or asking for a login is refused to a guest (DISCOUNT_LOGIN_REQUIRED).
     */
    val needsCustomer: Boolean get() = requiresLogin || firstOrderOnly || perUserLimit != null

    /** A line of [offer] counts in what the code takes its discount from. Whether it can be bought is the caller's. */
    fun covers(offer: PurchaseOffer): Boolean {
        if (excludeSaleItems && offer.priceTag.compareAtPrice != null) return false
        if (categoryIds.isEmpty() && productIds.isEmpty()) return true
        return offer.productId in productIds || offer.categoryId in categoryIds
    }

    override fun equals(other: Any?): Boolean = other is DiscountCode && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "DiscountCode(id=${id.value}, code=${code.value}, type=$type, value=$value, isActive=$isActive)"

    companion object {
        fun constructFromPersisted(
            id: DiscountCodeId,
            code: DiscountCodeText,
            type: DiscountType,
            value: Int,
            label: String,
            minSubtotal: Money?,
            maxDiscount: Money?,
            startsAt: Instant?,
            endsAt: Instant?,
            usageLimit: Int?,
            perUserLimit: Int?,
            firstOrderOnly: Boolean,
            requiresLogin: Boolean,
            excludeSaleItems: Boolean,
            categoryIds: Set<CategoryId>,
            productIds: Set<ProductId>,
            isActive: Boolean,
        ): DiscountCode = DiscountCode(
            id, code, type, value, label, minSubtotal, maxDiscount, startsAt, endsAt, usageLimit, perUserLimit, firstOrderOnly,
            requiresLogin, excludeSaleItems, categoryIds, productIds, isActive,
        )
    }
}

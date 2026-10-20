package com.kiva.domain.aggregate.discount.entity

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.discount.model.DiscountTerms
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Instant

/**
 * A discount code (roadmap section 14, the contract's DiscountCode): what it takes off, from what, for whom and how many
 * times. Kept as a row; the panel makes and edits it ([create], [change], [deactivate]; roadmap phase 4, step 4.2), and
 * never deletes one: orders point at it. How many times it was used is not here: its redemptions are counted
 * (DiscountRedemption), so the count can't drift.
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
 * @property version how many times it was saved: a save over another one that came first is refused.
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
    val createdOn: Instant,
    val updatedOn: Instant,
    val version: Long,
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

    /**
     * What the panel set, as the panel's form has it. Its login switch is the one that counts ([needsCustomer]), so a code
     * made by SQL with a per-customer limit and no login reads as the panel would have saved it.
     */
    val terms: DiscountTerms
        get() = DiscountTerms.constructFromPersisted(
            code, type, value, label, minSubtotal, maxDiscount, startsAt, endsAt, usageLimit, perUserLimit, firstOrderOnly, needsCustomer,
            excludeSaleItems, categoryIds.sortedBy { it.value }, productIds.sortedBy { it.value }, isActive,
        )

    /**
     * The code with the panel's new [terms], all of them replacing the old (roadmap phase 4, decision 11: a code already
     * used may change; orders keep what they were given). Null when nothing would change.
     */
    fun change(terms: DiscountTerms, now: Instant): DiscountCode? {
        if (terms.sorted() == this.terms) return null
        return of(id, terms, createdOn, updatedOn = now, version)
    }

    /** Switched off (`DELETE`): never removed, as orders point at it. Null when it already is. */
    fun deactivate(now: Instant): DiscountCode? {
        if (!isActive) return null
        return of(id, terms.copyActive(false), createdOn, updatedOn = now, version)
    }

    override fun equals(other: Any?): Boolean = other is DiscountCode && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "DiscountCode(id=${id.value}, code=${code.value}, type=$type, value=$value, isActive=$isActive)"

    companion object {
        /** A code the panel made [now], with [terms]; [id] is the one the repository gave out. */
        fun create(id: DiscountCodeId, terms: DiscountTerms, now: Instant): DiscountCode = of(id, terms, now, now, version = 0)

        private fun of(id: DiscountCodeId, terms: DiscountTerms, createdOn: Instant, updatedOn: Instant, version: Long): DiscountCode = DiscountCode(
            id, terms.code, terms.type, terms.value, terms.label, terms.minSubtotal, terms.maxDiscount, terms.startsAt, terms.endsAt, terms.usageLimit,
            terms.perUserLimit, terms.firstOrderOnly, terms.requiresLogin, terms.excludeSaleItems, terms.categoryIds.toSet(), terms.productIds.toSet(),
            terms.isActive, createdOn, updatedOn, version,
        )

        private fun DiscountTerms.sorted(): DiscountTerms = DiscountTerms.constructFromPersisted(
            code, type, value, label, minSubtotal, maxDiscount, startsAt, endsAt, usageLimit, perUserLimit, firstOrderOnly, requiresLogin,
            excludeSaleItems, categoryIds.distinct().sortedBy { it.value }, productIds.distinct().sortedBy { it.value }, isActive,
        )

        private fun DiscountTerms.copyActive(active: Boolean): DiscountTerms = DiscountTerms.constructFromPersisted(
            code, type, value, label, minSubtotal, maxDiscount, startsAt, endsAt, usageLimit, perUserLimit, firstOrderOnly, requiresLogin,
            excludeSaleItems, categoryIds, productIds, active,
        )

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
            createdOn: Instant,
            updatedOn: Instant,
            version: Long,
        ): DiscountCode = DiscountCode(
            id, code, type, value, label, minSubtotal, maxDiscount, startsAt, endsAt, usageLimit, perUserLimit, firstOrderOnly,
            requiresLogin, excludeSaleItems, categoryIds, productIds, isActive, createdOn, updatedOn, version,
        )
    }
}

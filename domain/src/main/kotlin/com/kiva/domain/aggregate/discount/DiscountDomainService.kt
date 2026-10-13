package com.kiva.domain.aggregate.discount

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.sum
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.entity.DiscountRedemption
import com.kiva.domain.aggregate.discount.model.CodeDiscount
import com.kiva.domain.aggregate.discount.model.DiscountLine
import com.kiva.domain.aggregate.discount.model.DiscountShopper
import com.kiva.domain.aggregate.discount.model.DiscountUsage
import com.kiva.domain.aggregate.discount.model.policy.DiscountPolicy
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.policy.OrderPricing
import java.time.Instant

/**
 * Whether a discount code applies, the way the business wants it checked (roadmap section 14), so the cart, its every
 * change, and placing an order all decide the same. The shopper hears one thing, the most basic first:
 *
 * | when | code |
 * |---|---|
 * | no such code, or switched off | DISCOUNT_CODE_INVALID |
 * | `startsAt > now` | DISCOUNT_CODE_NOT_STARTED |
 * | `endsAt ≤ now` | DISCOUNT_CODE_EXPIRED |
 * | a guest, and the code needs someone known (login, first order, a per-customer limit) | DISCOUNT_LOGIN_REQUIRED |
 * | first orders only, and the customer has a paid order | DISCOUNT_FIRST_ORDER_ONLY |
 * | held + confirmed uses ≥ `usageLimit`, or the customer's ≥ `perUserLimit` | DISCOUNT_USAGE_LIMIT_REACHED |
 * | `subtotal < minSubtotal` | DISCOUNT_MIN_SUBTOTAL_NOT_MET (`meta.minSubtotal`) |
 * | no line it covers can be bought | DISCOUNT_NOT_APPLICABLE |
 *
 * It never reads or saves: the use case gathers the code, its usage and the customer's paid orders in its transaction
 * (under the code's lock when an order is placed), and saves the redemption.
 */
class DiscountDomainService {

    private val logger = getLogger()

    /**
     * [code] (null when no code has the text) for [lines] bought by [shopper] at [now], its uses [usage]: what it takes
     * off, or why not.
     */
    fun check(code: DiscountCode?, shopper: DiscountShopper, usage: DiscountUsage, lines: List<DiscountLine>, now: Instant): Result<CodeDiscount, Failure> {
        if (code == null || !code.isActive) return Result.failure(Failure.Invalid)
        if (code.startsAt != null && code.startsAt.isAfter(now)) return Result.failure(Failure.NotStarted)
        if (code.endsAt != null && !code.endsAt.isAfter(now)) return Result.failure(Failure.Expired)
        if (shopper is DiscountShopper.Guest && code.needsCustomer) return Result.failure(Failure.LoginRequired)
        if (code.firstOrderOnly && shopper is DiscountShopper.Customer && shopper.hasPaidOrder) return Result.failure(Failure.FirstOrderOnly)
        if (code.usageLimit != null && usage.ofCode >= code.usageLimit) return Result.failure(Failure.UsageLimitReached)
        if (code.perUserLimit != null && usage.ofCustomer >= code.perUserLimit) return Result.failure(Failure.UsageLimitReached)

        val subtotal = lines.filter { it.offer.isPurchasable }.map { OrderPricing.lineTotalsOf(it.priced).total }.sum()
        if (code.minSubtotal != null && subtotal < code.minSubtotal) return Result.failure(Failure.MinSubtotalNotMet(code.minSubtotal))
        if (lines.none { it.offer.isPurchasable && code.covers(it.offer) }) return Result.failure(Failure.NotApplicable)

        return Result.success(CodeDiscount(code, DiscountPolicy.amountOf(code, DiscountPolicy.baseOf(code, lines))))
    }

    /**
     * [order], just placed with [discount], uses its code: held while it waits for its money, confirmed at once when it
     * had nothing to pay (it is paid already).
     */
    fun redeem(discount: CodeDiscount, order: Order, now: Instant): DiscountRedemption {
        require(order.totals.discountCode == discount.code.code && order.totals.codeDiscount == discount.amount) {
            "Order ${order.code.value} was not placed with ${discount.code.code.value} for ${discount.amount}"
        }
        logger.info("redeem code=${discount.code.code.value} order=${order.code.value} amount=${discount.amount.toman} paid=${order.status.isPaid}")
        return DiscountRedemption.of(discount.code.id, order.userId, order.id, discount.amount, paid = order.status.isPaid, now)
    }

    /** Why a code doesn't apply now; all 422, with the contract's codes. */
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.BUSINESS_RULE, meta = meta) {
        data object Invalid : Failure("DISCOUNT_CODE_INVALID")

        data object NotStarted : Failure("DISCOUNT_CODE_NOT_STARTED")

        data object Expired : Failure("DISCOUNT_CODE_EXPIRED")

        data object LoginRequired : Failure("DISCOUNT_LOGIN_REQUIRED")

        data object FirstOrderOnly : Failure("DISCOUNT_FIRST_ORDER_ONLY")

        data object UsageLimitReached : Failure("DISCOUNT_USAGE_LIMIT_REACHED")

        /** «این کد برای خرید بالای ۲٬۰۰۰٬۰۰۰ تومان فعاله», with the amount. */
        data class MinSubtotalNotMet(val minSubtotal: Money) : Failure("DISCOUNT_MIN_SUBTOTAL_NOT_MET", mapOf("minSubtotal" to minSubtotal.toman))

        data object NotApplicable : Failure("DISCOUNT_NOT_APPLICABLE")
    }
}

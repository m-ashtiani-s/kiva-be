package com.kiva.application.usecase.discount

import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.discount.DiscountDomainService
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.model.CodeDiscount
import com.kiva.domain.aggregate.discount.model.DiscountLine
import com.kiva.domain.aggregate.discount.model.DiscountShopper
import com.kiva.domain.aggregate.discount.model.DiscountUsage
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository
import com.kiva.domain.aggregate.discount.repository.DiscountRedemptionRepository
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * Discount codes where the shop uses them (roadmap section 14): applying one to a cart, checking the cart's again after
 * each of its changes, showing what it takes off on the cart and checkout pages, holding its use when an order is
 * placed, and confirming or giving back that use when the order is paid or expires. One place (Rule 15), so they all
 * decide alike; the decisions themselves are DiscountDomainService's. Runs inside the caller's transaction.
 */
class Discounts(
    private val discountCodeRepository: DiscountCodeRepository,
    private val discountRedemptionRepository: DiscountRedemptionRepository,
    private val orderQmRepository: OrderQmRepository,
    private val discountDomainService: DiscountDomainService,
) {

    private val logger = getLogger()

    /** [code] for [lines] bought by [userId] (null: a guest) at [now]: what it takes off, or why not. */
    fun check(code: DiscountCodeText, userId: UserId?, lines: List<DiscountLine>, now: Instant): Result<CodeDiscount, DiscountDomainService.Failure> =
        checkFound(discountCodeRepository.findByCode(code), userId, lines, now)

    /**
     * The same for an order being placed, with the code's row locked until the transaction ends (section 11, lock 5): its
     * uses are counted, and this order's added, under the lock, so two orders never take its last use together.
     */
    fun lockAndCheck(code: DiscountCodeText, userId: UserId, lines: List<DiscountLine>, now: Instant): Result<CodeDiscount, DiscountDomainService.Failure> =
        checkFound(discountCodeRepository.lockByCode(code), userId, lines, now)

    /**
     * What [cart]'s code takes off now, for its page and the checkout's; null without a code, or when it doesn't apply
     * any more (the cart's next command takes it off and says so).
     */
    fun appliedTo(cart: Cart, offers: Map<VariantId, PurchaseOffer>, now: Instant): CodeDiscount? {
        val code = cart.discountCode ?: return null
        return check(code, cart.owner.userId(), linesOf(cart, offers), now).getOrNull()
    }

    /** After a change of [cart]: its code, when it no longer applies, goes, and the shopper is told (DISCOUNT_REMOVED). */
    fun recheck(cart: Cart, offers: Map<VariantId, PurchaseOffer>, now: Instant) {
        val code = cart.discountCode ?: return
        val failure = check(code, cart.owner.userId(), linesOf(cart, offers), now).failureOrNull() ?: return
        logger.info("Discounts: code ${code.value} no longer applies to cart ${cart.publicId.value} (${failure.code}); it is taken off")
        cart.dropDiscountCode(now)
    }

    /** [order], placed with [discount], holds a use of its code (confirmed at once when it was paid at once). */
    fun redeem(discount: CodeDiscount, order: Order, now: Instant) {
        discountRedemptionRepository.add(discountDomainService.redeem(discount, order, now))
    }

    /** [orderId] was paid: its use of a code, if any, is confirmed. */
    fun confirmFor(orderId: OrderId, now: Instant) {
        val redemption = discountRedemptionRepository.findOfOrder(orderId) ?: return
        if (redemption.confirm(now)) {
            discountRedemptionRepository.save(redemption)
        } else {
            logger.warn("Discounts: the code use of order ${orderId.value} is ${redemption.status}, not held; its payment leaves it so")
        }
    }

    /** [orderId] expired: its use of a code, if any, goes back to the code. */
    fun releaseFor(orderId: OrderId, now: Instant) {
        val redemption = discountRedemptionRepository.findOfOrder(orderId) ?: return
        if (redemption.release(now)) discountRedemptionRepository.save(redemption)
    }

    /** The lines of [cart] as a code looks at them. */
    fun linesOf(cart: Cart, offers: Map<VariantId, PurchaseOffer>): List<DiscountLine> = cart.lines.map { line ->
        // The read model keeps every color ever made, so a cart line's color is always there.
        val offer = offers[line.variantId] ?: error("No offer for variant ${line.variantId.value} of cart line ${line.itemId.value}")
        DiscountLine(offer, Quantity.constructFromPersisted(line.quantity.value))
    }

    private fun checkFound(code: DiscountCode?, userId: UserId?, lines: List<DiscountLine>, now: Instant): Result<CodeDiscount, DiscountDomainService.Failure> {
        val shopper = userId?.let { DiscountShopper.Customer(it, hasPaidOrder = orderQmRepository.purchasesOf(listOf(it)).containsKey(it)) }
            ?: DiscountShopper.Guest
        val usage = code?.let { discountRedemptionRepository.usageOf(it.id, userId) } ?: DiscountUsage(0, 0)
        return discountDomainService.check(code, shopper, usage, lines, now)
    }

    private fun CartOwner.userId(): UserId? = (this as? CartOwner.User)?.userId
}

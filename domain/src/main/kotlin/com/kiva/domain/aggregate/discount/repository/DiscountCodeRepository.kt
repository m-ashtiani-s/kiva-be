package com.kiva.domain.aggregate.discount.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.entity.DiscountRedemption
import com.kiva.domain.aggregate.discount.model.DiscountUsage
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.order.model.valueobject.OrderId

/** Discount codes, kept as rows. Codes are made by SQL until the panel's are built; the shop only reads them. */
interface DiscountCodeRepository {

    fun findByCode(code: DiscountCodeText): DiscountCode?

    /**
     * The code, its row locked until the transaction ends (section 11, lock 5): placing an order counts its uses and
     * adds one under this lock, so two orders never take its last use together.
     */
    fun lockByCode(code: DiscountCodeText): DiscountCode?
}

/**
 * The orders' uses of discount codes. A new use is added under its code's lock; confirming and releasing one happen
 * under its order's payments lock (section 11, lock 6), so each order's use changes once at a time.
 */
interface DiscountRedemptionRepository {

    /** Held and confirmed uses of [codeId], in all and by [userId] (none for a guest). */
    fun usageOf(codeId: DiscountCodeId, userId: UserId?): DiscountUsage

    fun findOfOrder(orderId: OrderId): DiscountRedemption?

    /**
     * Locks the order's use of a code, if any, and reads it (section 4.11, lock 5): a cancellation takes it first, before
     * the order's payments, so giving the use back never crosses its confirmation.
     */
    fun lockOfOrder(orderId: OrderId): DiscountRedemption?

    fun add(redemption: DiscountRedemption)

    /** Writes its confirmation, release or giving back, only if its row is still in the status it was read in. */
    fun save(redemption: DiscountRedemption)
}

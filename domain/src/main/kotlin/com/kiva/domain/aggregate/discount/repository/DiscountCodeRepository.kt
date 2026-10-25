package com.kiva.domain.aggregate.discount.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.entity.DiscountRedemption
import com.kiva.domain.aggregate.discount.model.DiscountUsage
import com.kiva.domain.aggregate.discount.model.DiscountUsageStats
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.order.model.valueobject.OrderId

/** Discount codes, kept as rows. The panel makes and edits them; the shop only reads them. */
interface DiscountCodeRepository {

    fun findByCode(code: DiscountCodeText): DiscountCode?

    fun findById(id: DiscountCodeId): DiscountCode?

    /** The code, its row locked until the transaction ends (the panel's edit and switch-off, section 4.11 lock 5). */
    fun lockById(id: DiscountCodeId): DiscountCode?

    /** The id a new code will have. */
    fun nextId(): DiscountCodeId

    /** The panel's list: [active] (its switch) or every code, newest made first. */
    fun list(active: Boolean?, pageable: Pageable): Page<DiscountCode>

    /**
     * Adds a new code. Its text taken by another code a moment ago (the use case checked first) is a
     * UniqueTakenException.
     */
    fun add(code: DiscountCode)

    /**
     * Writes the code over the version it was read in, and counts the version up: a save another one beat is a
     * ConcurrentChangeException (409); its text taken by another code a moment ago is a UniqueTakenException.
     */
    fun save(code: DiscountCode)

    /**
     * How much each of [ids] was used: its uses by orders paid and not given back (CONFIRMED; a cancelled order's use is
     * released), in one read for a whole page. A code never used is missing.
     */
    fun usageStatsOf(ids: Collection<DiscountCodeId>): Map<DiscountCodeId, DiscountUsageStats>

    companion object {
        /** The only order of [list]: newest made first. */
        const val NEWEST = "newest"
    }

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

package com.kiva.domain.aggregate.refund.repository

import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId

/**
 * Refunds, kept as rows. Opening or changing one runs under the lock of its order's row in the read model (roadmap
 * section 4.11, lock 9) taken first; [lockAllOf] then reads the order's refunds under their own row locks, and [save]
 * checks the row's version too, the last wall.
 */
interface RefundRepository {

    /** Stores a new refund. */
    fun add(refund: Refund)

    /** The order [id] refunds, read without a lock (to lock the order's row first); null when there is no such refund. */
    fun findOrderOf(id: RefundId): OrderId?

    /** Locks the refund's row and reads it; null when there is none. */
    fun lock(id: RefundId): Refund?

    /** Locks every refund of [orderId], oldest first, and reads them all. */
    fun lockAllOf(orderId: OrderId): List<Refund>

    /** Every refund of [orderId], oldest first, for reading only. */
    fun findAllOf(orderId: OrderId): List<Refund>

    /** Every refund of the returns [returnIds] (phase 7), oldest first, for reading only: a page of returns in one read. */
    fun findAllOfReturns(returnIds: Collection<ReturnId>): List<Refund>

    /**
     * Writes the refund's change, only if its row still has the version it was read with, and raises that version.
     * Throws ConcurrentChangeException when someone saved it in the meantime.
     */
    fun save(refund: Refund)
}

/** Hands out the ids of new refunds, each only once, from the `refundid_seq` database sequence. */
interface RefundIdGenerator {
    fun nextRefundId(): RefundId
}

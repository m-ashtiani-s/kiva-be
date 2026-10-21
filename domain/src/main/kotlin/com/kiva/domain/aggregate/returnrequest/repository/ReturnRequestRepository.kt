package com.kiva.domain.aggregate.returnrequest.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus

/**
 * Returns, kept as rows. A change runs under the lock of its order's row in the read model (roadmap section 4.11, lock
 * 9), then the return's own row lock ([lock], lock 10); [save] checks the row's version too, the last wall. At most one
 * return of an order that isn't REJECTED: the order refuses a second (Order.requestReturn), and a partial unique index
 * is the wall behind it.
 */
interface ReturnRequestRepository {

    /** Stores a new return. */
    fun add(returnRequest: ReturnRequest)

    /** The return [id], read without a lock (to find its order and lock the order's row first); null when there is none. */
    fun find(id: ReturnId): ReturnRequest?

    /** Locks the return's row and reads it; null when there is none. */
    fun lock(id: ReturnId): ReturnRequest?

    /** Every return of [orderId], newest first (REJECTED ones too), for reading only. */
    fun findOfOrder(orderId: OrderId): List<ReturnRequest>

    /** At most [limit] returns of [userId], newest first, for reading only. */
    fun findOfUser(userId: UserId, limit: Int): List<ReturnRequest>

    /**
     * The panel's queue, for reading only: the returns in [status] (every return when null), newest asked first or oldest
     * (the pageable's sort: `newest`, `oldest`; ties to the return's id the same way).
     */
    fun page(status: ReturnStatus?, pageable: Pageable): Page<ReturnRequest>

    /**
     * Writes the return's change, only if its row still has the version it was read with, and raises that version.
     * Throws ConcurrentChangeException when someone saved it in the meantime.
     */
    fun save(returnRequest: ReturnRequest)
}

/** Hands out the ids of new returns, each only once, from the `returnrequestid_seq` database sequence. */
interface ReturnIdGenerator {
    fun nextReturnId(): ReturnId
}

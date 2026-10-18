package com.kiva.application.usecase.order.usecase.query

/**
 * The orders the panel's queue offers (contract `sort` of `GET /admin/orders`): newest placed first, or oldest first to
 * work the queue in turn. Ties go to the order id, the same way.
 */
object AdminOrderSorts {
    const val NEWEST = "newest"
    const val OLDEST = "oldest"

    const val DEFAULT = NEWEST

    val names: Set<String> = setOf(NEWEST, OLDEST)
}

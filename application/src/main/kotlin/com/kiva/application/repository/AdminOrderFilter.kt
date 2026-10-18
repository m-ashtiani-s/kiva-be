package com.kiva.application.repository

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * The panel's order queue, checked (`GET /admin/orders`, AdminOrderFilters). Every set part narrows it; null is «any».
 *
 * @property statuses the orders in these statuses; null for the queue's default, every status but the unpaid and the
 *   expired ones ([QUEUE_DEFAULT]).
 * @property reserved true: in a reservation group (held still, or let go); false: in none.
 * @property awaitingPhotos true: being prepared with its photos waiting to be sent (PROCESSING and WAITING); false: not that.
 * @property placedFrom placed at or after; @property placedBefore placed before (both from Tehran days).
 */
data class AdminOrderFilter(
    val statuses: Set<OrderStatus>? = null,
    val search: AdminOrderSearch? = null,
    val shippingMethod: ShippingMethodCode? = null,
    val reserved: Boolean? = null,
    val awaitingPhotos: Boolean? = null,
    val placedFrom: Instant? = null,
    val placedBefore: Instant? = null,
) {
    /** The same queue in any status: what the status tabs count. */
    fun withoutStatuses(): AdminOrderFilter = copy(statuses = null)

    companion object {
        /** The operator's queue: what was paid. Unpaid and expired orders come only when asked for by status. */
        val QUEUE_DEFAULT: Set<OrderStatus> = OrderStatus.entries.filter { it.isPaid }.toSet()
    }
}

/** What `q` of the queue looks for, decided by its shape. */
sealed interface AdminOrderSearch {
    /** An order's code (`KV-218340`, or its digits alone). */
    data class Code(val code: OrderCode) : AdminOrderSearch

    /** A mobile number: the account's, or the recipient's. */
    data class Phone(val phone: IranianMobileNumber) : AdminOrderSearch {
        override fun toString(): String = "Phone(${phone.masked()})"
    }

    /** Words, each in the code, the recipient's name or phone, the city, or the account's name. */
    data class Words(val words: List<String>) : AdminOrderSearch {
        override fun toString(): String = "Words(${words.size})"
    }
}

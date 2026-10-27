package com.kiva.application.dto

import com.kiva.common.pagination.Page
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Instant

/**
 * The panel's order queue (the contract's AdminOrderListResponse): one page of rows, and how many orders each status has
 * with the same filters but the status (every status, zero too), so the tabs' counts don't move when a tab is picked.
 */
data class AdminOrderListDto(val page: Page<Item>, val statusCounts: Map<OrderStatus, Long>) {

    /**
     * One row (AdminOrderListItem). Personal data: [toString] shows the code and status only.
     *
     * @property customerName the account's name, otherwise the recipient's; null for an erased order (its account deleted).
     * @property customerPhone the account's phone; null for an erased order.
     * @property reservationExpiresAt the end of its reservation group while the group still holds.
     * @property shipmentGroupCode its reservation group's code (`SG-3021`), held or let go.
     * @property mediaCount the photos of the last batch sent, otherwise of the draft.
     */
    data class Item(
        val code: String,
        val placedOn: Instant,
        val status: OrderStatus,
        val customerName: String?,
        val customerPhone: String?,
        val city: String,
        val itemsCount: Int,
        val payable: Money,
        val shippingMethod: ShippingMethodCode,
        val messenger: MessengerChannel,
        val reserved: Boolean,
        val reservationExpiresAt: Instant?,
        val shipmentGroupCode: String?,
        val mediaCount: Int,
        val trackingCode: String?,
    ) {
        override fun toString(): String = "Item(code=$code, status=$status)"
    }
}

/**
 * One order in the panel (the contract's AdminOrder): the customer's page ([detail]) and what only the panel sees.
 *
 * @property payments every attempt to pay, oldest first.
 * @property notes the panel's internal notes, oldest first.
 * @property allowedTransitions what `POST …/status` takes now (OrderTransitionPolicy).
 * @property returns the order's returns as the panel's queue shows them (`detail.returns` is the customer's view).
 */
data class AdminOrderDto(
    val detail: OrderDetailDto,
    val returns: List<AdminReturnRequestDto>,
    val customer: Customer,
    val shipmentGroup: ShipmentGroup?,
    val payments: List<OrderDetailDto.Payment>,
    val notes: List<AdminNoteDto>,
    val allowedTransitions: Set<OrderStatus>,
) {
    /**
     * The account that bought it. Personal data: [toString] shows the id only.
     *
     * @property fullName null when the customer gave none.
     * @property ordersCount their paid orders, cancelled ones included.
     */
    data class Customer(val id: Long, val fullName: String?, val phone: String?, val ordersCount: Int) {
        override fun toString(): String = "Customer(id=$id)"
    }

    /**
     * The reservation group it ships with, under one tracking code.
     *
     * @property orderCodes its orders that stand (not cancelled), in the order they were paid.
     * @property releaseAt the end of the reservation.
     */
    data class ShipmentGroup(val code: String, val orderCodes: List<String>, val releaseAt: Instant)
}

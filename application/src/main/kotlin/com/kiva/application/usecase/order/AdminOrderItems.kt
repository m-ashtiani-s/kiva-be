package com.kiva.application.usecase.order

import com.kiva.application.dto.AdminOrderListDto
import com.kiva.application.repository.AdminOrderQm
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupCode
import com.kiva.domain.aggregate.user.entity.User

/**
 * One row of the panel's order lists (the contract's AdminOrderListItem), the same in the queue (FindAdminOrdersUc) and
 * on a customer's page (GetCustomerDetailUc): named by the account when it has a name, else by the recipient.
 */
object AdminOrderItems {

    fun itemOf(row: AdminOrderQm, account: User?): AdminOrderListDto.Item {
        val order = row.order
        val accountName = account?.let { listOfNotNull(it.firstName?.value, it.lastName?.value).joinToString(" ").ifEmpty { null } }
        return AdminOrderListDto.Item(
            code = order.code.value,
            placedOn = order.placedOn,
            status = order.status,
            customerName = accountName ?: order.address.recipientName.value,
            customerPhone = account?.phone?.value ?: order.address.recipientPhone.value,
            city = order.address.cityName,
            itemsCount = order.totalsView.itemsCount,
            payable = order.totalsView.payable,
            shippingMethod = order.shippingMethod,
            messenger = order.messenger.channel,
            reserved = order.reservationGroupId != null,
            reservationExpiresAt = row.groupHoldsUntil,
            shipmentGroupCode = order.reservationGroupId?.let { ReservationGroupCode.of(it).value },
            mediaCount = order.photosSent.size.takeIf { it > 0 } ?: order.photoDraft.size,
            trackingCode = order.shipment?.trackingCode?.value,
        )
    }
}

package com.kiva.application.dto

import com.kiva.domain.aggregate.user.model.valueobject.Gender
import java.time.LocalDate

/**
 * One customer's page in the panel (the contract's AdminCustomerDetail): what the list shows ([customer]), the whole
 * profile, every saved address (the default first) and the last orders in any status. Personal data: [toString] shows
 * the id only.
 */
data class AdminCustomerDetailDto(
    val customer: AdminCustomerDto,
    val firstName: String?,
    val lastName: String?,
    val birthDate: LocalDate?,
    val gender: Gender,
    val defaultMessengerPhone: String?,
    val addresses: List<AddressDto>,
    val recentOrders: List<AdminOrderListDto.Item>,
) {
    override fun toString(): String = "AdminCustomerDetailDto(id=${customer.id})"
}

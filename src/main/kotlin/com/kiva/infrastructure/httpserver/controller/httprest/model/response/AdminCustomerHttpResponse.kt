package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AdminCustomerDetailDto
import com.kiva.application.dto.AdminCustomerDto
import kotlinx.serialization.Serializable

/**
 * The contract's AdminCustomer.
 *
 * @property ordersCount the orders the money came in for, cancelled ones too; unpaid and expired ones not.
 * @property totalSpent in toman: what those orders were paid (a balance after a change included), less what went back
 *   (the completed refunds).
 */
@Serializable
data class AdminCustomerHttpResponse(
    val id: Long,
    val phone: String,
    val fullName: String?,
    val email: String?,
    val ordersCount: Int,
    val totalSpent: Long,
    val lastOrderAt: String?,
    val defaultMessenger: String?,
    val marketingSmsOptIn: Boolean,
    val roles: List<String>,
    val createdAt: String,
) {
    companion object {
        fun from(customer: AdminCustomerDto) = AdminCustomerHttpResponse(
            id = customer.id,
            phone = customer.phone,
            fullName = customer.fullName,
            email = customer.email,
            ordersCount = customer.ordersCount,
            totalSpent = customer.totalSpent.toman,
            lastOrderAt = customer.lastOrderAt?.toString(),
            defaultMessenger = customer.defaultMessenger,
            marketingSmsOptIn = customer.marketingSmsOptIn,
            roles = customer.roles,
            createdAt = customer.createdOn.toString(),
        )
    }
}

/** The contract's AdminCustomerDetail: AdminCustomer and the whole profile, the addresses and the last orders. Personal data. */
@Serializable
data class AdminCustomerDetailHttpResponse(
    val id: Long,
    val phone: String,
    val fullName: String?,
    val email: String?,
    val ordersCount: Int,
    val totalSpent: Long,
    val lastOrderAt: String?,
    val defaultMessenger: String?,
    val marketingSmsOptIn: Boolean,
    val roles: List<String>,
    val createdAt: String,
    val firstName: String?,
    val lastName: String?,
    val birthDate: String?,
    val gender: String,
    val defaultMessengerPhone: String?,
    val addresses: List<AddressHttpResponse>,
    val recentOrders: List<AdminOrderListItemHttpResponse>,
) {
    override fun toString(): String = "AdminCustomerDetailHttpResponse(id=$id)"

    companion object {
        fun from(detail: AdminCustomerDetailDto): AdminCustomerDetailHttpResponse {
            val customer = AdminCustomerHttpResponse.from(detail.customer)
            return AdminCustomerDetailHttpResponse(
                id = customer.id,
                phone = customer.phone,
                fullName = customer.fullName,
                email = customer.email,
                ordersCount = customer.ordersCount,
                totalSpent = customer.totalSpent,
                lastOrderAt = customer.lastOrderAt,
                defaultMessenger = customer.defaultMessenger,
                marketingSmsOptIn = customer.marketingSmsOptIn,
                roles = customer.roles,
                createdAt = customer.createdAt,
                firstName = detail.firstName,
                lastName = detail.lastName,
                birthDate = detail.birthDate?.toString(),
                gender = detail.gender.name,
                defaultMessengerPhone = detail.defaultMessengerPhone,
                addresses = detail.addresses.map(AddressHttpResponse::from),
                recentOrders = detail.recentOrders.map(AdminOrderListItemHttpResponse::from),
            )
        }
    }
}

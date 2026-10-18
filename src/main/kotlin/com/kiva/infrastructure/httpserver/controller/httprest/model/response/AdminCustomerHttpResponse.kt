package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AdminCustomerDto
import kotlinx.serialization.Serializable

/**
 * The contract's AdminCustomer.
 *
 * @property ordersCount the paid orders, cancelled ones too.
 * @property totalSpent in toman: what the paid orders cost, less what went back (the completed refunds).
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

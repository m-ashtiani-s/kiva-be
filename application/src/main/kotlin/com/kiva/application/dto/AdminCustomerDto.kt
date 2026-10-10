package com.kiva.application.dto

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.user.entity.User
import java.time.Instant

/**
 * A customer as the panel lists them (the contract's AdminCustomer).
 *
 * @property ordersCount, totalSpent, lastOrderAt from the customer's orders; 0, 0 and null until the orders phase.
 */
data class AdminCustomerDto(
    val id: Long,
    val phone: String,
    val fullName: String?,
    val email: String?,
    val ordersCount: Int,
    val totalSpent: Money,
    val lastOrderAt: Instant?,
    val defaultMessenger: String?,
    val marketingSmsOptIn: Boolean,
    val roles: List<String>,
    val createdOn: Instant,
) {
    companion object {
        fun from(user: User) = AdminCustomerDto(
            id = user.id.value,
            phone = user.phone.value,
            fullName = listOfNotNull(user.firstName?.value, user.lastName?.value).joinToString(" ").ifEmpty { null },
            email = user.email?.value,
            ordersCount = 0,
            totalSpent = Money.ZERO,
            lastOrderAt = null,
            defaultMessenger = user.defaultMessenger?.name,
            marketingSmsOptIn = user.marketingSmsOptIn,
            roles = user.roles.values.map { it.name },
            createdOn = user.createdOn,
        )
    }
}

package com.kiva.application.dto

import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.user.entity.User
import java.time.Instant

/**
 * A customer as the panel lists them (the contract's AdminCustomer).
 *
 * @property ordersCount the customer's orders the money came in for (OrderRepository's Purchases);
 * @property totalSpent what they paid for them (their payable; the refunds of a later phase will come off it);
 * @property lastOrderAt when the latest of them was placed; null when they bought nothing.
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
        fun from(user: User, purchases: OrderQmRepository.Purchases?) = AdminCustomerDto(
            id = user.id.value,
            phone = user.phone.value,
            fullName = listOfNotNull(user.firstName?.value, user.lastName?.value).joinToString(" ").ifEmpty { null },
            email = user.email?.value,
            ordersCount = purchases?.ordersCount ?: 0,
            totalSpent = purchases?.totalSpent ?: Money.ZERO,
            lastOrderAt = purchases?.lastOrderOn,
            defaultMessenger = user.defaultMessenger?.name,
            marketingSmsOptIn = user.marketingSmsOptIn,
            roles = user.roles.values.map { it.name },
            createdOn = user.createdOn,
        )
    }
}

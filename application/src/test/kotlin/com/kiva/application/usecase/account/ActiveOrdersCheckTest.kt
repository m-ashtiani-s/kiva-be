package com.kiva.application.usecase.account

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «سفارش جاری» (roadmap phase 14, decisions 5 and 17), order by order: what keeps a customer from deleting their account. */
class ActiveOrdersCheckTest {

    private val nothingOwed = Money.ZERO

    @ParameterizedTest
    @EnumSource(
        OrderStatus::class,
        names = ["PENDING_PAYMENT", "PAYMENT_FAILED", "RESERVED", "PROCESSING", "PHOTO_SENT", "CHANGE_REQUESTED", "SHIPPED", "RETURN_REQUESTED", "RETURNED"],
    )
    fun `an order on its way, waiting for its money or being returned is active`(status: OrderStatus) {
        assertTrue(ActiveOrdersCheck.isActive(status, nothingOwed, refundOpen = false))
    }

    @ParameterizedTest
    @EnumSource(OrderStatus::class, names = ["DELIVERED", "CANCELLED", "EXPIRED", "REFUNDED"])
    fun `a delivered, cancelled, expired or refunded order with nothing open is not`(status: OrderStatus) {
        assertFalse(ActiveOrdersCheck.isActive(status, nothingOwed, refundOpen = false))
    }

    @ParameterizedTest
    @EnumSource(OrderStatus::class, names = ["DELIVERED", "CANCELLED", "REFUNDED"])
    fun `a finished order with a refund still on its way is active`(status: OrderStatus) {
        assertTrue(ActiveOrdersCheck.isActive(status, nothingOwed, refundOpen = true))
    }

    @Test
    fun `a delivered order that still owes something is active`() {
        assertTrue(ActiveOrdersCheck.isActive(OrderStatus.DELIVERED, Money.constructFromPersisted(200_000), refundOpen = false))
    }

    @Test
    fun `every status is one or the other - a new status must be put on a side`() {
        assertEquals(OrderStatus.entries.size, ActiveOrdersCheck.ACTIVE.size + listOf("DELIVERED", "CANCELLED", "EXPIRED", "REFUNDED").size)
    }
}

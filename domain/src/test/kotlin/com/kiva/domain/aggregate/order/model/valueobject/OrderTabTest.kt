package com.kiva.domain.aggregate.order.model.valueobject

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OrderTabTest {

    @Test
    fun `an unpaid order still to be paid is current, an expired one is cancelled with the cancelled ones`() {
        assertEquals(
            mapOf(
                OrderTab.CURRENT to setOf(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_FAILED, OrderStatus.RESERVED, OrderStatus.PROCESSING, OrderStatus.PHOTO_SENT,
                    OrderStatus.CHANGE_REQUESTED, OrderStatus.SHIPPED,
                ),
                OrderTab.DELIVERED to setOf(OrderStatus.DELIVERED, OrderStatus.RETURN_REQUESTED, OrderStatus.RETURNED),
                OrderTab.CANCELLED to setOf(OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.REFUNDED),
            ),
            OrderTab.entries.associateWith { it.statuses },
        )
    }

    @Test
    fun `every status is in exactly one tab`() {
        assertEquals(OrderStatus.entries.toSet(), OrderTab.entries.flatMap { it.statuses }.toSet())
        assertEquals(OrderStatus.entries.size, OrderTab.entries.sumOf { it.statuses.size })
    }
}

package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.order.model.valueobject.OrderActions
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentPolicyTest {

    private val holdUntil = at(15)

    @Test
    fun `a new attempt may start only while unpaid and before the time to pay ends`() {
        assertTrue(PaymentPolicy.canStartPayment(OrderStatus.PENDING_PAYMENT, holdUntil, at(14)))
        assertTrue(PaymentPolicy.canStartPayment(OrderStatus.PAYMENT_FAILED, holdUntil, at(14)))
        assertFalse(PaymentPolicy.canStartPayment(OrderStatus.PENDING_PAYMENT, holdUntil, holdUntil))
        listOf(OrderStatus.EXPIRED, OrderStatus.PROCESSING, OrderStatus.RESERVED).forEach {
            assertFalse(PaymentPolicy.canStartPayment(it, holdUntil, now), "$it")
        }
    }

    @Test
    fun `an attempt whose shopper never came back is abandoned 20 minutes after it was sent to the gateway`() {
        assertFalse(PaymentPolicy.isAbandoned(at(14), at(33)))
        assertTrue(PaymentPolicy.isAbandoned(at(14), at(34)))
        assertTrue(PaymentPolicy.isAbandoned(at(14), at(60)))
    }

    @Test
    fun `the result page offers a retry for an attempt that didn't succeed, while the order may still be paid`() {
        listOf(PaymentStatus.CANCELLED, PaymentStatus.FAILED, PaymentStatus.START_FAILED, PaymentStatus.REDIRECTED).forEach {
            assertTrue(PaymentPolicy.canRetry(it, OrderStatus.PAYMENT_FAILED, holdUntil, at(14)), "$it")
        }
        assertFalse(PaymentPolicy.canRetry(PaymentStatus.CANCELLED, OrderStatus.PAYMENT_FAILED, holdUntil, holdUntil), "after the time to pay")
        assertFalse(PaymentPolicy.canRetry(PaymentStatus.FAILED, OrderStatus.PROCESSING, holdUntil, at(14)), "paid by another attempt")
        assertFalse(PaymentPolicy.canRetry(PaymentStatus.SUCCEEDED, OrderStatus.PROCESSING, holdUntil, at(14)))
        assertFalse(PaymentPolicy.canRetry(PaymentStatus.VERIFYING, OrderStatus.PENDING_PAYMENT, holdUntil, at(14)), "being confirmed: the page waits")
    }

    @Test
    fun `two unpaid orders holding stock are fine, the third stops the next until the earliest ends`() {
        assertNull(PaymentPolicy.refusalToPlace(listOf(at(5), at(9)), now))

        val refusal = PaymentPolicy.refusalToPlace(listOf(at(9), at(5), at(12)), now)

        assertEquals(Duration.ofMinutes(5), refusal?.retryAfter)
        assertEquals(mapOf("retryAfterSeconds" to 300L), refusal?.meta)
    }

    @Test
    fun `with more than three, enough of them must end for one more to fit`() {
        assertEquals(Duration.ofMinutes(9), PaymentPolicy.refusalToPlace(listOf(at(5), at(9), at(12), at(14)), now)?.retryAfter)
    }

    @Test
    fun `an order held past its time to pay by an attempt on the gateway still waits at least a second`() {
        assertEquals(mapOf("retryAfterSeconds" to 1L), PaymentPolicy.refusalToPlace(listOf(at(-2), at(9), at(12)), now)?.meta)
    }

    @Test
    fun `the eleventh attempt is refused until the order's time to pay ends`() {
        assertNull(PaymentPolicy.refusalToAttempt(attempts = 9, holdUntil, at(4)))

        val refusal = PaymentPolicy.refusalToAttempt(attempts = 10, holdUntil, at(4))

        assertEquals("RATE_LIMITED", refusal?.code)
        assertEquals(mapOf("retryAfterSeconds" to 11 * 60L), refusal?.meta)
    }

    @Test
    fun `only «پرداخت» shows in this phase, for an unpaid order in time`() {
        val none = OrderActions(false, false, false, false, false, false, false)

        assertEquals(none.copy(canPay = true), OrderActionsPolicy.actionsOf(OrderStatus.PENDING_PAYMENT, holdUntil, at(14)))
        assertEquals(none.copy(canPay = true), OrderActionsPolicy.actionsOf(OrderStatus.PAYMENT_FAILED, holdUntil, at(14)))
        assertEquals(none, OrderActionsPolicy.actionsOf(OrderStatus.PENDING_PAYMENT, holdUntil, at(15)))
        OrderStatus.entries.filter { !it.isAwaitingPayment }.forEach {
            assertEquals(none, OrderActionsPolicy.actionsOf(it, holdUntil, now), "$it")
        }
    }
}

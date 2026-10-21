package com.kiva.domain.aggregate.order.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.order.model.valueobject.OrderActions
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
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

    private fun actions(
        status: OrderStatus,
        at: java.time.Instant = now,
        photoEverSent: Boolean = false,
        photoStatus: PhotoStatus = PhotoStatus.WAITING,
        balanceDue: Money = Money.ZERO,
        returnableUntil: java.time.Instant? = null,
        returnSettled: Boolean = false,
    ) = OrderActionsPolicy.actionsOf(OrderActionFacts(status, holdUntil, photoEverSent, photoStatus, balanceDue, returnableUntil, returnSettled, at))

    @Test
    fun `a balance is payable while something is owed and the order is being prepared, its photos sent, or a change asked - never after`() {
        val owed = Money.constructFromPersisted(200_000)

        assertEquals(setOf(OrderStatus.PROCESSING, OrderStatus.PHOTO_SENT, OrderStatus.CHANGE_REQUESTED), OrderStatus.entries.filter { PaymentPolicy.canPayBalance(it, owed) }.toSet())
        OrderStatus.entries.forEach { assertFalse(PaymentPolicy.canPayBalance(it, Money.ZERO), "$it owing nothing") }
    }

    @Test
    fun `«پرداخت» shows for a paid order that owes something after a change, whatever its time to pay was`() {
        val owed = Money.constructFromPersisted(200_000)

        assertTrue(actions(OrderStatus.PROCESSING, at(60 * 24), photoEverSent = true, balanceDue = owed).canPay)
        assertTrue(actions(OrderStatus.PHOTO_SENT, at(60 * 24), photoEverSent = true, photoStatus = PhotoStatus.SENT, balanceDue = owed).canPay)
        assertFalse(actions(OrderStatus.CANCELLED, balanceDue = Money.ZERO).canPay)
    }

    @Test
    fun `the eleventh attempt of a balance is refused, telling to look again after the gateway's window`() {
        assertNull(PaymentPolicy.refusalToPayBalance(balanceAttempts = 9))

        val refusal = PaymentPolicy.refusalToPayBalance(balanceAttempts = 10)

        assertEquals("RATE_LIMITED", refusal?.code)
        assertEquals(mapOf("retryAfterSeconds" to 20 * 60L), refusal?.meta)
    }

    @Test
    fun `«تلاش دوباره» of a balance's attempt - not succeeded, not being confirmed, and still owed`() {
        val owed = Money.constructFromPersisted(200_000)

        assertTrue(PaymentPolicy.canRetryBalance(PaymentStatus.CANCELLED, OrderStatus.PHOTO_SENT, owed))
        assertFalse(PaymentPolicy.canRetryBalance(PaymentStatus.VERIFYING, OrderStatus.PHOTO_SENT, owed))
        assertFalse(PaymentPolicy.canRetryBalance(PaymentStatus.SUCCEEDED, OrderStatus.PHOTO_SENT, Money.ZERO))
        assertFalse(PaymentPolicy.canRetryBalance(PaymentStatus.CANCELLED, OrderStatus.PHOTO_SENT, Money.ZERO))
    }

    @Test
    fun `«پرداخت» shows for an unpaid order in time, «افزودن به رزرو» for a RESERVED one, «لغو» while nothing moved`() {
        val none = OrderActions(false, false, false, false, false, false, false)

        assertEquals(none.copy(canPay = true), actions(OrderStatus.PENDING_PAYMENT, at(14)))
        assertEquals(none.copy(canPay = true), actions(OrderStatus.PAYMENT_FAILED, at(14)))
        assertEquals(none, actions(OrderStatus.PENDING_PAYMENT, at(15)))
        assertEquals(none.copy(canAddToReservation = true, canCancel = true), actions(OrderStatus.RESERVED))
        assertEquals(none.copy(canCancel = true), actions(OrderStatus.PROCESSING))
        // PHOTO_SENT with photos WAITING never happens; the photos' own cases are below.
        OrderStatus.entries.filter { !it.isAwaitingPayment && it != OrderStatus.RESERVED && it != OrderStatus.PROCESSING }.forEach {
            assertEquals(none, actions(it), "$it")
        }
    }

    @Test
    fun `«لغو» is gone once a photo was ever sent, even back in PROCESSING - «همونه!» while photos sent wait for an answer, «درخواست تغییر» after them, approved or not`() {
        val none = OrderActions(false, false, false, false, false, false, false)

        assertEquals(none, actions(OrderStatus.PROCESSING, photoEverSent = true))
        assertEquals(
            none.copy(canApproveMedia = true, canRequestChange = true),
            actions(OrderStatus.PHOTO_SENT, photoEverSent = true, photoStatus = PhotoStatus.SENT),
        )
        assertEquals(none.copy(canRequestChange = true), actions(OrderStatus.PHOTO_SENT, photoEverSent = true, photoStatus = PhotoStatus.APPROVED))
        assertEquals(none, actions(OrderStatus.CHANGE_REQUESTED, photoEverSent = true, photoStatus = PhotoStatus.CHANGE_REQUESTED), "one at a time")
    }
}

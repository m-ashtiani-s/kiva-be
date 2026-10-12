package com.kiva.domain.aggregate.payment.entity

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason.GATEWAY_DECLINED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason.GATEWAY_REJECTED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason.GATEWAY_UNREACHABLE
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentOutcome
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.CANCELLED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.EXPIRED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.FAILED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.INITIATED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.REDIRECTED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.REFUNDED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.START_FAILED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.SUCCEEDED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus.VERIFYING
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentTest {

    private val now = Instant.parse("2026-10-12T08:00:00Z")
    private fun at(seconds: Long) = now.plusSeconds(seconds)

    private val authority = GatewayAuthority.constructFromPersisted("A00000000000000000000000000217885159")
    private val redirect = PaymentRedirect("https://payment.zarinpal.com/pg/StartPay/A00000000000000000000000000217885159", PaymentRedirect.Method.GET)
    private val refId = GatewayRefId.constructFromPersisted("201843917")
    private val card = CardMask.constructFromPersisted("6037-99**-****-1234")

    private fun started() = Payment.start(
        PaymentPublicId.constructFromPersisted("pay_0000000000A1"), OrderId.constructFromPersisted(42), UserId.constructFromPersisted(7),
        PaymentGatewayCode.ZARINPAL, Money.constructFromPersisted(1_525_000), now,
    )

    private fun redirected() = started().also { it.redirected(authority, redirect, at(1)) }
    private fun verifying() = redirected().also { it.startVerifying(at(60)) }

    /** Every change a payment can be asked for, by name, each from a fresh payment in [from]. */
    private val changes: Map<String, (Payment) -> Result<*, *>> = mapOf(
        "redirected" to { p -> p.redirected(authority, redirect, at(2)) },
        "startFailed" to { p -> p.startFailed(GATEWAY_UNREACHABLE, at(2)) },
        "cancel" to { p -> p.cancel(at(2)) },
        "startVerifying" to { p -> p.startVerifying(at(2)) },
        "refuseAsNotPayable" to { p -> p.refuseAsNotPayable(at(2)) },
        "succeed" to { p -> p.succeed(refId, card, at(2)) },
        "failDeclined" to { p -> p.fail(GATEWAY_DECLINED, at(2)) },
        "failRejected" to { p -> p.fail(GATEWAY_REJECTED, at(2)) },
        "expire" to { p -> p.expire(at(2)) },
    )

    /** The roadmap's table 10.1: which change is allowed from which status, and where it goes. */
    private val allowed: Map<Pair<PaymentStatus, String>, PaymentStatus> = mapOf(
        (INITIATED to "redirected") to REDIRECTED,
        (INITIATED to "startFailed") to START_FAILED,
        (INITIATED to "expire") to EXPIRED,
        (REDIRECTED to "cancel") to CANCELLED,
        (REDIRECTED to "startVerifying") to VERIFYING,
        (REDIRECTED to "refuseAsNotPayable") to FAILED,
        (REDIRECTED to "failDeclined") to FAILED,
        (REDIRECTED to "expire") to EXPIRED,
        (VERIFYING to "succeed") to SUCCEEDED,
        (VERIFYING to "failDeclined") to FAILED,
        (VERIFYING to "failRejected") to FAILED,
    )

    /** A payment in [status], walked there the way a real one gets there. */
    private fun inStatus(status: PaymentStatus): Payment = when (status) {
        INITIATED -> started()
        REDIRECTED -> redirected()
        VERIFYING -> verifying()
        START_FAILED -> started().also { it.startFailed(GATEWAY_REJECTED, at(1)) }
        CANCELLED -> redirected().also { it.cancel(at(30)) }
        SUCCEEDED -> verifying().also { it.succeed(refId, card, at(61)) }
        FAILED -> verifying().also { it.fail(GATEWAY_DECLINED, at(61)) }
        EXPIRED -> started().also { it.expire(at(900)) }
        REFUNDED -> Payment.constructFromPersisted(
            PaymentPublicId.constructFromPersisted("pay_0000000000A1"), OrderId.constructFromPersisted(42), UserId.constructFromPersisted(7),
            PaymentGatewayCode.ZARINPAL, Money.constructFromPersisted(1_525_000), REFUNDED, authority, redirect, refId, card, null, now, at(1), at(60), at(61), 3,
        )
    }

    @Test
    fun `every change of the table is allowed from its status, and every other change is refused`() {
        PaymentStatus.entries.forEach { from ->
            changes.forEach { (name, change) ->
                val payment = inStatus(from)
                val result = change(payment)
                val to = allowed[from to name]
                if (to != null) {
                    assertTrue(result.isSuccess, "$name from $from was refused: $result")
                    assertEquals(to, payment.status, "$name from $from")
                } else {
                    assertTrue(result.isFailure, "$name from $from was allowed")
                    assertEquals(from, payment.status, "$name from $from changed it")
                    assertEquals("PAYMENT_TRANSITION_INVALID", result.failureOrNull()?.code)
                }
            }
        }
    }

    @Test
    fun `a new attempt is INITIATED, for the order's payable, nothing from the gateway yet`() {
        val payment = started()

        assertEquals(INITIATED, payment.status)
        assertEquals(PaymentOutcome.PENDING, payment.status.outcome)
        assertEquals(listOf(null, null, null, null, null), listOf(payment.authority, payment.redirect, payment.refId, payment.failureReason, payment.settledOn))
        assertEquals(0, payment.version)
    }

    @Test
    fun `the gateway's session, then confirming, then the bank's reference - each with its time`() {
        val payment = started()

        payment.redirected(authority, redirect, at(1))
        payment.startVerifying(at(60))
        payment.succeed(refId, card, at(61))

        assertEquals(listOf(authority, redirect, at(1), at(60)), listOf(payment.authority, payment.redirect, payment.redirectedOn, payment.verifyingSince))
        assertEquals(listOf(refId, card, at(61)), listOf(payment.refId, payment.cardMask, payment.settledOn))
        assertNull(payment.failureReason)
        assertTrue(payment.status.isSettled)
        assertEquals(PaymentOutcome.SUCCEEDED, payment.status.outcome)
    }

    @Test
    fun `each way of not succeeding keeps its reason and is settled`() {
        val reasons = listOf(
            inStatus(START_FAILED), inStatus(CANCELLED), redirected().also { it.refuseAsNotPayable(at(30)) }, inStatus(FAILED), inStatus(EXPIRED),
        ).map { it.failureReason to it.status.outcome }

        assertEquals(
            listOf(
                GATEWAY_REJECTED to PaymentOutcome.FAILED,
                PaymentFailureReason.USER_CANCELLED to PaymentOutcome.CANCELLED,
                PaymentFailureReason.ORDER_NOT_PAYABLE to PaymentOutcome.FAILED,
                GATEWAY_DECLINED to PaymentOutcome.FAILED,
                PaymentFailureReason.EXPIRED to PaymentOutcome.EXPIRED,
            ),
            reasons,
        )
    }

    @Test
    fun `only the attempts not settled yet can still change`() {
        assertEquals(setOf(INITIATED, REDIRECTED, VERIFYING), PaymentStatus.entries.filter { !it.isSettled }.toSet())
        assertTrue(inStatus(SUCCEEDED).status.isSettled)
    }

    @Test
    fun `a start fails only for no answer or a refusal, and a gateway fails a payment only by declining or refusing`() {
        assertFailsWith<IllegalArgumentException> { started().startFailed(PaymentFailureReason.USER_CANCELLED, at(1)) }
        assertFailsWith<IllegalArgumentException> { verifying().fail(PaymentFailureReason.ORDER_NOT_PAYABLE, at(61)) }
    }

    @Test
    fun `a payment of nothing, or a stored row that breaks the rules, is a bug`() {
        assertFailsWith<IllegalArgumentException> {
            Payment.start(
                PaymentPublicId.constructFromPersisted("pay_0000000000A1"), OrderId.constructFromPersisted(42), UserId.constructFromPersisted(7),
                PaymentGatewayCode.ZARINPAL, Money.ZERO, now,
            )
        }
        // SUCCEEDED without the bank's reference.
        assertFailsWith<IllegalArgumentException> {
            Payment.constructFromPersisted(
                PaymentPublicId.constructFromPersisted("pay_0000000000A1"), OrderId.constructFromPersisted(42), UserId.constructFromPersisted(7),
                PaymentGatewayCode.ZARINPAL, Money.constructFromPersisted(1_525_000), SUCCEEDED, authority, redirect, null, null, null, now, at(1), at(60), at(61), 2,
            )
        }
    }

    @Test
    fun `printing a payment shows only its ids and status`() {
        assertEquals("Payment(publicId=pay_0000000000A1, orderId=42, status=VERIFYING)", "${verifying()}")
    }
}

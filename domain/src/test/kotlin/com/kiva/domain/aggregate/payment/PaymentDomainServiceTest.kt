package com.kiva.domain.aggregate.payment

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.order.OrderFixtures.placedOrder
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.entity.event.OrderPaid
import com.kiva.domain.aggregate.order.entity.event.OrderPaymentFailed
import com.kiva.domain.aggregate.order.model.policy.PaymentPolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.PaymentDomainService.Confirmed
import com.kiva.domain.aggregate.payment.PaymentDomainService.ExpireRefusal
import com.kiva.domain.aggregate.payment.PaymentDomainService.Return
import com.kiva.domain.aggregate.payment.PaymentDomainService.StartAttemptFailure
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentIdGenerator
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentDomainServiceTest {

    private val ids = object : PaymentIdGenerator {
        var handedOut = 0
        override fun nextPaymentPublicId() = PaymentPublicId.constructFromPersisted("pay_N" + (++handedOut).toString().padStart(11, '0'))
    }
    private val service = PaymentDomainService(ids)
    private val refId = GatewayRefId.constructFromPersisted("201843917")
    private val card = CardMask.constructFromPersisted("6037-99**-****-1234")

    /** An attempt of [order] whose session is open, as the gateway sent the shopper away with it. */
    private fun redirected(order: Order, n: Int = 1): Payment {
        val authority = GatewayAuthority.constructFromPersisted("A0000000000000000000000000000000000$n")
        return Payment.start(PaymentPublicId.constructFromPersisted("pay_00000000000$n"), order.id, sara, PaymentGatewayCode.ZARINPAL, order.totals.payable, at(1))
            .also { it.redirected(authority, PaymentRedirect("https://payment.zarinpal.com/pg/StartPay/${authority.value}", PaymentRedirect.Method.GET), at(1)) }
    }

    /** A fresh order, with its placing already saved: only what these tests do is recorded. */
    private fun order(): Order = Order(placedOrder().getRecordedEvents())

    private fun Order.newEventTypes(): List<String> = getRecordedEvents().map { it.type.value }

    // ============ another attempt ============

    @Test
    fun `another attempt of an unpaid order - for its payable, through the gateway asked, its id taken last`() {
        val order = order()
        val first = redirected(order)
        first.cancel(at(2))

        val attempt = service.startAttempt(order, listOf(first), PaymentGatewayCode.ZARINPAL, gatewayAvailable = true, sara, at(3)).getOrNull()!!

        assertEquals(listOf(PaymentStatus.INITIATED, order.totals.payable, order.id, PaymentGatewayCode.ZARINPAL), listOf(attempt.status, attempt.amount, attempt.orderId, attempt.gateway))
        assertEquals("pay_N00000000001", attempt.publicId.value)
        assertEquals(PaymentStatus.CANCELLED, first.status, "the attempts of before stay as they are")
    }

    @Test
    fun `another attempt is refused in the contract's order - paid, then time over, then the gateway, then too many - and takes no id`() {
        val unavailable = false
        val paid = order().also { o -> redirected(o).let { p -> service.shopperReturned(p, true, o, listOf(p), sara, at(1)); service.confirmed(p, o, refId, card, sara, at(1)) } }
        val tenAttempts = { o: Order -> List(10) { redirected(o, it) } }

        assertEquals(StartAttemptFailure.AlreadyPaid, service.startAttempt(paid, tenAttempts(paid), PaymentGatewayCode.SAMAN, unavailable, sara, at(30)).failureOrNull())
        val late = order()
        assertEquals(StartAttemptFailure.Expired, service.startAttempt(late, tenAttempts(late), PaymentGatewayCode.SAMAN, unavailable, sara, at(15)).failureOrNull())
        val expired = order().also { it.expire(sara, at(16)) }
        assertEquals(StartAttemptFailure.Expired, service.startAttempt(expired, emptyList(), PaymentGatewayCode.ZARINPAL, true, sara, at(16)).failureOrNull())
        val open = order()
        assertEquals(StartAttemptFailure.GatewayUnavailable, service.startAttempt(open, tenAttempts(open), PaymentGatewayCode.SAMAN, unavailable, sara, at(4)).failureOrNull())
        val tooMany = service.startAttempt(open, tenAttempts(open), PaymentGatewayCode.ZARINPAL, true, sara, at(4)).failureOrNull()
        assertEquals(StartAttemptFailure.TooManyAttempts(PaymentPolicy.Refusal.TooManyAttempts(Duration.ofMinutes(11))), tooMany)
        assertEquals(mapOf("retryAfterSeconds" to 660L), tooMany?.meta)
        assertEquals(0, ids.handedOut)
        assertEquals("pay_N00000000001", service.startAttempt(open, tenAttempts(open).take(9), PaymentGatewayCode.ZARINPAL, true, sara, at(4)).getOrNull()?.publicId?.value)
    }

    // ============ the expiry job ============

    @Test
    fun `past its time to pay, with no attempt going on, the order expires and so do its attempts still open`() {
        val order = order()
        val cancelled = redirected(order, 1).also { it.cancel(at(2)) }
        val abandoned = redirected(order, 2)
        val neverSent = Payment.start(
            PaymentPublicId.constructFromPersisted("pay_000000000003"), order.id, sara, PaymentGatewayCode.ZARINPAL, order.totals.payable, at(1),
        )

        val expired = service.expireUnpaid(order, listOf(cancelled, abandoned, neverSent), sara, at(21)).getOrNull()!!

        assertEquals(listOf(abandoned, neverSent), expired)
        assertEquals(listOf(PaymentStatus.CANCELLED, PaymentStatus.EXPIRED, PaymentStatus.EXPIRED), listOf(cancelled, abandoned, neverSent).map { it.status })
        assertEquals(OrderStatus.EXPIRED, order.status)
    }

    @Test
    fun `no expiry before the time to pay ends, of an order paid or expired, nor while an attempt may still pay it`() {
        val early = order()
        assertEquals(ExpireRefusal.TooEarly(at(15)), service.expireUnpaid(early, emptyList(), sara, at(14)).failureOrNull())

        val paid = order().also { o -> redirected(o).let { p -> service.shopperReturned(p, true, o, listOf(p), sara, at(1)); service.confirmed(p, o, refId, card, sara, at(1)) } }
        assertEquals(ExpireRefusal.NotAwaitingPayment(OrderStatus.PROCESSING), service.expireUnpaid(paid, emptyList(), sara, at(30)).failureOrNull())

        val onTheBankPage = order()
        val sentLate = redirected(onTheBankPage)
        assertEquals(ExpireRefusal.PaymentInProgress(sentLate.publicId), service.expireUnpaid(onTheBankPage, listOf(sentLate), sara, at(20)).failureOrNull())

        val beingConfirmed = order()
        val verifying = redirected(beingConfirmed).also { service.beginConfirming(it, beingConfirmed, listOf(it), at(14)) }
        assertEquals(ExpireRefusal.PaymentInProgress(verifying.publicId), service.expireUnpaid(beingConfirmed, listOf(verifying), sara, at(90)).failureOrNull())

        val expired = order().also { service.expireUnpaid(it, emptyList(), sara, at(16)) }
        assertEquals(ExpireRefusal.NotAwaitingPayment(OrderStatus.EXPIRED), service.expireUnpaid(expired, emptyList(), sara, at(17)).failureOrNull())
        assertEquals(listOf(OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_PAYMENT, OrderStatus.PENDING_PAYMENT), listOf(early, onTheBankPage, beingConfirmed).map { it.status })
    }

    @Test
    fun `an attempt still at the bank is closed only once abandoned, and only from its open session`() {
        val order = order()
        val payment = redirected(order)

        assertFalse(service.abandon(payment, at(20)))
        assertEquals(PaymentStatus.REDIRECTED, payment.status)
        assertTrue(service.abandon(payment, at(21)))
        assertEquals(PaymentStatus.EXPIRED to PaymentFailureReason.EXPIRED, payment.status to payment.failureReason)
        assertFalse(service.abandon(payment, at(40)), "closed already")
        assertEquals(OrderStatus.PENDING_PAYMENT, order.status, "its order is the expiry's business")
    }

    // ============ giving back (the exception of section 10.5) ============

    @Test
    fun `confirmed for an order that couldn't take it - given back while the gateway allows, then REFUNDED`() {
        val order = order()
        val payment = redirected(order)
        service.beginConfirming(payment, order, listOf(payment), at(3))
        order.expire(sara, at(16))
        service.confirmed(payment, order, refId, card, sara, at(17))

        assertTrue(service.mayReverse(payment, order, at(46)))
        assertFalse(service.mayReverse(payment, order, at(47)), "30 minutes after it was confirmed")

        service.reversed(payment, order, at(18))
        assertEquals(PaymentStatus.REFUNDED to refId, payment.status to payment.refId)
        assertFalse(service.mayReverse(payment, order, at(19)), "given back already")
    }

    @Test
    fun `the attempt that paid its order is never given back alone`() {
        val order = order()
        val payment = redirected(order)
        service.beginConfirming(payment, order, listOf(payment), at(3))
        service.confirmed(payment, order, refId, card, sara, at(4))

        assertFalse(service.mayReverse(payment, order, at(5)))
        assertFailsWith<IllegalArgumentException> { service.reversed(payment, order, at(5)) }
        assertEquals(PaymentStatus.SUCCEEDED, payment.status)
    }

    // ============ back from the gateway ============

    @Test
    fun `paid, and the order waits - the attempt starts being confirmed and the order is untouched yet`() {
        val order = order()
        val payment = redirected(order)

        val returned = service.shopperReturned(payment, paid = true, order, listOf(payment), sara, at(3))

        assertEquals(Return.Confirming, returned)
        assertEquals(PaymentStatus.VERIFYING, payment.status)
        assertEquals(at(3), payment.verifyingSince)
        assertEquals(emptyList(), order.newEventTypes())
    }

    @Test
    fun `cancelled at the gateway - the attempt is CANCELLED and the order's try failed`() {
        val order = order()
        val payment = redirected(order)

        val returned = service.shopperReturned(payment, paid = false, order, listOf(payment), sara, at(3))

        assertEquals(Return.Cancelled, returned)
        assertEquals(PaymentStatus.CANCELLED, payment.status)
        assertEquals(PaymentFailureReason.USER_CANCELLED, payment.failureReason)
        assertEquals(OrderStatus.PAYMENT_FAILED, order.status)
        val failed = assertIs<OrderPaymentFailed>(order.getRecordedEvents().single())
        assertEquals(payment.publicId, failed.paymentId)
    }

    @Test
    fun `paid, but the order expired meanwhile - refused as ORDER_NOT_PAYABLE, never confirmed`() {
        val order = order()
        val payment = redirected(order)
        order.expire(sara, at(16))
        val before = order.getRecordedEvents().size

        val returned = service.shopperReturned(payment, paid = true, order, listOf(payment), sara, at(17))

        assertEquals(Return.NotPayable, returned)
        assertEquals(PaymentStatus.FAILED, payment.status)
        assertEquals(PaymentFailureReason.ORDER_NOT_PAYABLE, payment.failureReason)
        assertNull(payment.verifyingSince)
        assertEquals(before, order.getRecordedEvents().size, "nothing more happens to an expired order")
    }

    @Test
    fun `two attempts both paid - the second, back after the first was confirmed, is refused and never confirmed`() {
        val order = order()
        val first = redirected(order, 1)
        val second = redirected(order, 2)
        val attempts = listOf(first, second)
        service.shopperReturned(first, paid = true, order, attempts, sara, at(3))
        service.confirmed(first, order, refId, card, sara, at(3))

        val returned = service.shopperReturned(second, paid = true, order, attempts, sara, at(4))

        assertEquals(Return.NotPayable, returned)
        assertEquals(PaymentFailureReason.ORDER_NOT_PAYABLE, second.failureReason)
        assertEquals(OrderStatus.PROCESSING, order.status)
        assertEquals(first.publicId, order.paidByPaymentId)
    }

    @Test
    fun `two attempts both paid at once - while the first is being confirmed, the second waits and changes nothing`() {
        val order = order()
        val first = redirected(order, 1)
        val second = redirected(order, 2)
        val attempts = listOf(first, second)
        service.shopperReturned(first, paid = true, order, attempts, sara, at(3))

        val returned = service.shopperReturned(second, paid = true, order, attempts, sara, at(3))

        assertEquals(Return.Deferred, returned)
        assertEquals(PaymentStatus.REDIRECTED, second.status)
        // If the first is refused, the second may be confirmed after all.
        service.refused(first, order, PaymentFailureReason.GATEWAY_DECLINED, sara, at(4))
        assertEquals(Return.Confirming, service.beginConfirming(second, order, attempts, at(5)))
    }

    @Test
    fun `a callback that comes again changes nothing - after it ended, and while it is being confirmed`() {
        val order = order()
        val cancelled = redirected(order, 1)
        val verifying = redirected(order, 2)
        val attempts = listOf(cancelled, verifying)
        service.shopperReturned(cancelled, paid = false, order, attempts, sara, at(3))
        service.shopperReturned(verifying, paid = true, order, attempts, sara, at(4))
        val events = order.getRecordedEvents().size

        assertEquals(Return.AlreadySettled, service.shopperReturned(cancelled, paid = true, order, attempts, sara, at(5)))
        assertEquals(Return.BeingConfirmed, service.shopperReturned(verifying, paid = false, order, attempts, sara, at(5)))
        assertEquals(listOf(PaymentStatus.CANCELLED, PaymentStatus.VERIFYING), attempts.map { it.status })
        assertEquals(events, order.getRecordedEvents().size)
    }

    @Test
    fun `the attempts must be the order's`() {
        val order = order()
        val payment = redirected(order)
        val ofAnotherOrder = Payment.start(
            PaymentPublicId.constructFromPersisted("pay_000000000009"), OrderId.constructFromPersisted(99), sara, PaymentGatewayCode.ZARINPAL, order.totals.payable, at(1),
        )

        assertFailsWith<IllegalArgumentException> { service.shopperReturned(ofAnotherOrder, paid = true, order, listOf(ofAnotherOrder), sara, at(3)) }
        assertFailsWith<IllegalArgumentException> { service.shopperReturned(payment, paid = true, order, listOf(payment, ofAnotherOrder), sara, at(3)) }
        assertFailsWith<IllegalArgumentException> { service.shopperReturned(payment, paid = true, order, emptyList(), sara, at(3)) }
    }

    // ============ the gateway's answer ============

    @Test
    fun `confirmed - the attempt succeeds and the order is paid with it, for exactly its amount`() {
        val order = order()
        val payment = redirected(order)
        service.shopperReturned(payment, paid = true, order, listOf(payment), sara, at(3))

        val confirmed = service.confirmed(payment, order, refId, card, sara, at(4))

        assertEquals(Confirmed.Paid, confirmed)
        assertEquals(listOf(PaymentStatus.SUCCEEDED, refId, card, at(4)), listOf(payment.status, payment.refId, payment.cardMask, payment.settledOn))
        assertEquals(OrderStatus.PROCESSING, order.status)
        val paid = assertIs<OrderPaid>(order.getRecordedEvents().single())
        assertEquals(listOf(payment.publicId, Money.constructFromPersisted(3_480_000), false), listOf(paid.paymentId, paid.amount, paid.heldForReservation))
    }

    @Test
    fun `confirmed for an order that can't take it any more - the money came in, so the attempt succeeds and the bug is told back`() {
        val order = order()
        val payment = redirected(order)
        service.shopperReturned(payment, paid = true, order, listOf(payment), sara, at(3))
        // Only a bug gets here: an order isn't expired while one of its attempts is being confirmed.
        order.expire(sara, at(16))

        val confirmed = service.confirmed(payment, order, refId, card, sara, at(17))

        assertEquals(Confirmed.OrderNotPaid(Order.MarkPaidFailure.NotPayable(OrderStatus.EXPIRED)), confirmed)
        assertEquals(PaymentStatus.SUCCEEDED, payment.status)
        assertEquals(OrderStatus.EXPIRED, order.status)
    }

    @Test
    fun `refused - the attempt fails and so does the order's try`() {
        val order = order()
        val payment = redirected(order)
        service.shopperReturned(payment, paid = true, order, listOf(payment), sara, at(3))

        service.refused(payment, order, PaymentFailureReason.GATEWAY_DECLINED, sara, at(4))

        assertEquals(listOf(PaymentStatus.FAILED, PaymentFailureReason.GATEWAY_DECLINED), listOf(payment.status, payment.failureReason))
        assertEquals(OrderStatus.PAYMENT_FAILED, order.status)
        assertEquals(PaymentFailureReason.GATEWAY_DECLINED, assertIs<OrderPaymentFailed>(order.getRecordedEvents().single()).reason)
    }

    @Test
    fun `the gateway says an attempt the shopper never came back from failed - it fails from its open session`() {
        val order = order()
        val payment = redirected(order)

        service.refused(payment, order, PaymentFailureReason.GATEWAY_DECLINED, sara, at(10))

        assertEquals(PaymentStatus.FAILED, payment.status)
        assertEquals(OrderStatus.PAYMENT_FAILED, order.status)
        assertEquals(now.plusSeconds(600), payment.settledOn)
    }
}

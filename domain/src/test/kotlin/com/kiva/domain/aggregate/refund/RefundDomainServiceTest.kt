package com.kiva.domain.aggregate.refund

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.OrderFixtures.placedOrder
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.refund.entity.Refund
import com.kiva.domain.aggregate.refund.model.valueobject.BankReference
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.refund.model.valueobject.RefundReason
import com.kiva.domain.aggregate.refund.model.valueobject.RefundSource
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.refund.repository.RefundIdGenerator
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class RefundDomainServiceTest {

    private val now = Instant.parse("2026-10-18T08:00:00Z")
    private val admin = UserId.constructFromPersisted(1)
    private var nextId = 100L
    private val service = RefundDomainService(
        object : RefundIdGenerator {
            override fun nextRefundId(): RefundId = RefundId.constructFromPersisted(++nextId)
        },
    )
    private val order: Order = placedOrder()

    private enum class Outcome { SUCCEEDED, FAILED, REFUNDED_BY_GATEWAY }

    private fun toman(amount: Long) = Money.constructFromPersisted(amount)

    /** An attempt of [order] of [amount], taken to SUCCEEDED, or left FAILED by the gateway, or given back by it. */
    private fun payment(n: Int, amount: Long, outcome: Outcome = Outcome.SUCCEEDED): Payment {
        val payment = Payment.start(PaymentPublicId.constructFromPersisted("pay_00000000000$n"), order.id, sara, PaymentGatewayCode.ZARINPAL, toman(amount), now)
        payment.redirected(GatewayAuthority.constructFromPersisted("A0000000000000000000000000000000000$n"), PaymentRedirect("https://pay.test/$n", PaymentRedirect.Method.GET), now)
        payment.startVerifying(now)
        when (outcome) {
            Outcome.SUCCEEDED -> payment.succeed(GatewayRefId.constructFromPersisted("20184391$n"), null, now)
            Outcome.REFUNDED_BY_GATEWAY -> payment.succeed(GatewayRefId.constructFromPersisted("20184391$n"), null, now).also { payment.refund(now) }
            Outcome.FAILED -> payment.fail(PaymentFailureReason.GATEWAY_DECLINED, now)
        }
        return payment
    }

    private fun refund(id: Long, amount: Long, status: RefundStatus): Refund {
        val refund = Refund.start(
            RefundId.constructFromPersisted(id), order.id, toman(amount), RefundMethod.BANK_TRANSFER, RefundSource.MANUAL, null, admin, now,
        )
        when (status) {
            RefundStatus.PENDING -> {}
            RefundStatus.PROCESSING -> refund.markProcessing(null, now)
            RefundStatus.COMPLETED -> refund.complete(BankReference.constructFromPersisted("1405"), null, now)
            RefundStatus.FAILED -> refund.fail(null, now)
        }
        return refund
    }

    @Test
    fun `paid 2,000,000 with a PENDING refund of 500,000 and a FAILED one of 300,000 - 1,500,000 is still refundable`() {
        val existing = listOf(refund(1, 500_000, RefundStatus.PENDING), refund(2, 300_000, RefundStatus.FAILED))

        assertEquals(toman(1_500_000), service.refundable(toman(2_000_000), existing))
    }

    @Test
    fun `paid is the SUCCEEDED attempts - a failed one, and one the gateway gave back by itself, brought nothing`() {
        val payments = listOf(payment(1, 2_000_000, Outcome.FAILED), payment(2, 2_000_000), payment(3, 2_000_000, Outcome.REFUNDED_BY_GATEWAY))

        assertEquals(toman(2_000_000), service.paidBy(payments))
    }

    @Test
    fun `a refund of what is left opens PENDING with a new id - one toman more is REFUND_EXCEEDS_PAID with what is refundable`() {
        val payments = listOf(payment(1, 2_000_000))
        val existing = listOf(refund(1, 500_000, RefundStatus.COMPLETED))

        val opened = service.open(order, payments, existing, toman(1_500_000), RefundMethod.ORIGINAL_PAYMENT, RefundReason.constructFromPersisted("آسیب"), RefundSource.MANUAL, admin, now)
            .getOrNull()!!
        val refused = service.open(order, payments, existing, toman(1_500_001), RefundMethod.ORIGINAL_PAYMENT, null, RefundSource.MANUAL, admin, now).failureOrNull()

        assertEquals(RefundStatus.PENDING, opened.status)
        assertEquals(101L, opened.id.value)
        assertEquals(toman(1_500_000), opened.amount)
        assertEquals(RefundDomainService.OpenFailure.ExceedsPaid(toman(1_500_000)), refused)
        assertEquals("amount", refused?.field)
        assertEquals(mapOf("refundable" to 1_500_000L), refused?.meta)
    }

    @Test
    fun `refunds promised beyond what was paid leave nothing refundable, never a negative amount`() {
        assertEquals(Money.ZERO, service.refundable(toman(100), listOf(refund(1, 300, RefundStatus.PENDING))))
    }
}

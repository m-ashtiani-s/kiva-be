package com.kiva.domain.aggregate.order.entity

import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.CartFixtures
import com.kiva.domain.aggregate.order.OrderFixtures.address
import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.item
import com.kiva.domain.aggregate.order.OrderFixtures.messenger
import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.order.OrderFixtures.placedOrder
import com.kiva.domain.aggregate.order.OrderFixtures.placement
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.PricingFixtures
import com.kiva.domain.aggregate.order.entity.event.OrderExpired
import com.kiva.domain.aggregate.order.entity.event.OrderPaid
import com.kiva.domain.aggregate.order.entity.event.OrderPaymentFailed
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.entity.event.OrderReleasedFromReservation
import com.kiva.domain.aggregate.order.model.PricedLine
import com.kiva.domain.aggregate.order.model.policy.OrderPricing
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.EXPIRED
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PAYMENT_FAILED
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PENDING_PAYMENT
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PROCESSING
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.RESERVED
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason.EXPIRED as PAYMENT_EXPIRED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason.GATEWAY_DECLINED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason.ORDER_NOT_PAYABLE
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason.USER_CANCELLED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.reservation.ReservationFixtures
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.POST
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderTest {

    private val firstAttempt = PaymentPublicId.constructFromPersisted("pay_0000000000A1")
    private val secondAttempt = PaymentPublicId.constructFromPersisted("pay_0000000000B2")
    private val golden = Money.constructFromPersisted(3_480_000)
    private val sg1001 = ReservationGroupId.constructFromPersisted(1001)

    private fun Order.eventTypes(): List<String> = getRecordedEvents().map { it.type.value }

    private fun Order.sequences(): List<Long> = getRecordedEvents().map { it.sequence.value }

    // ============ placing, straight on the aggregate ============

    private fun line(lineId: Int, variantId: Long, productId: Long, quantity: Int, price: Long = 1_450_000): OrderLine =
        OrderLine.constructFromPersisted(
            lineId, ProductId.constructFromPersisted(productId), VariantId.constructFromPersisted(variantId), item(),
            Quantity.constructFromPersisted(quantity), PriceTag.constructFromPersisted(Money.constructFromPersisted(price), null),
            reservable = true, returnable = true,
        )

    private fun totalsOf(lines: List<OrderLine>, freeShippingThreshold: Long = 3_000_000): OrderTotals = OrderPricing.quote(
        lines.map { PricedLine(it.priceTag, it.quantity, purchasable = true) },
        PricingFixtures.shopSettings(freeShippingThreshold = freeShippingThreshold),
        POST,
        now,
    ).totals

    private fun place(
        lines: List<OrderLine>,
        limit: Int = 5,
        totals: OrderTotals = totalsOf(lines),
        gift: GiftNote? = null,
    ) = Order.place(
        OrderId.constructFromPersisted(1), OrderCode.constructFromPersisted("KV-218340"), sara, lines,
        lines.associate { it.productId to MaxPerOrder.constructFromPersisted(limit) }, address, messenger, POST, totals,
        reserveRequested = false, customerNote = null, gift = gift, paymentPending = Duration.ofMinutes(15), by = sara, now = now,
    )

    @Nested
    inner class Placing {
        @Test
        fun `placing records OrderPlaced with the whole snapshot - PENDING_PAYMENT, its time to pay 15 minutes on`() {
            val order = placedOrder()

            assertEquals(listOf("OrderPlaced"), order.eventTypes())
            val placed = assertIs<OrderPlaced>(order.getRecordedEvents().single())
            assertEquals(EventSequence.FIRST, placed.sequence)
            assertEquals(PENDING_PAYMENT, order.status)
            assertEquals(now, order.placedOn)
            assertEquals(now.plus(Duration.ofMinutes(15)), order.holdUntil)
            assertEquals(golden, order.totals.payable)
            assertEquals(address, order.address)
            assertEquals(messenger, order.messenger)
            assertEquals(POST, order.shippingMethod)
            assertEquals(sara, order.userId)
            assertNull(order.paidOn)
            assertTrue(order.isAwaitingPayment)
        }

        @Test
        fun `with nothing to pay it is paid at once - OrderPlaced and OrderPaid numbered 1 and 2, PROCESSING, without a payment`() {
            val lines = listOf(line(1, 201, 2, 1, price = 0))

            val order = place(lines, totals = totalsOf(lines, freeShippingThreshold = 0)).getOrNull()!!

            assertEquals(listOf("OrderPlaced", "OrderPaid"), order.eventTypes())
            assertEquals(listOf(1L, 2L), order.sequences())
            assertEquals(PROCESSING, order.status)
            assertEquals(now, order.paidOn)
            assertNull(order.paidByPaymentId)
            assertEquals(Money.ZERO, assertIs<OrderPaid>(order.getRecordedEvents().last()).amount)
        }

        @Test
        fun `6 of one product with a limit of 5 is refused, its colors counted together`() {
            val sixOfOne = listOf(line(1, 301, 3, 3), line(2, 302, 3, 3))

            val refused = place(sixOfOne).failureOrNull()

            assertEquals(Order.PlaceFailure.LimitExceeded(listOf(ProductId.constructFromPersisted(3))), refused)
            assertTrue(place(listOf(line(1, 301, 3, 3), line(2, 302, 3, 2))).isSuccess)
        }

        @Test
        fun `an order without lines is refused`() {
            assertEquals(Order.PlaceFailure.NoLines, place(emptyList(), totals = totalsOf(listOf(line(1, 201, 2, 1)))).failureOrNull())
        }

        @Test
        fun `lines not numbered 1 to n, a color twice, or totals of other lines are bugs`() {
            assertFailsWith<IllegalArgumentException> { place(listOf(line(2, 201, 2, 1))) }
            assertFailsWith<IllegalArgumentException> { place(listOf(line(1, 201, 2, 1), line(2, 201, 2, 1))) }
            assertFailsWith<IllegalArgumentException> { place(listOf(line(1, 201, 2, 1)), totals = totalsOf(listOf(line(1, 201, 2, 2)))) }
        }
    }

    // ============ the money ============

    @Nested
    inner class Paying {
        @Test
        fun `paying an unpaid order makes it PROCESSING, by the attempt that paid`() {
            val order = placedOrder()

            order.markPaid(firstAttempt, golden, heldForReservation = false, by = UserId.SYSTEM, now = at(3))

            assertEquals(PROCESSING, order.status)
            assertEquals(at(3), order.paidOn)
            assertEquals(firstAttempt, order.paidByPaymentId)
            assertEquals(listOf(1L, 2L), order.sequences())
        }

        @Test
        fun `an order whose first attempt failed can still be paid`() {
            val order = placedOrder()
            order.recordPaymentFailure(firstAttempt, USER_CANCELLED, UserId.SYSTEM, at(2))

            assertTrue(order.markPaid(secondAttempt, golden, false, UserId.SYSTEM, at(5)).isSuccess)
            assertEquals(PROCESSING, order.status)
        }

        @Test
        fun `held for a reservation it is RESERVED in its group - the event says it, as decided`() {
            val order = placedOrder()

            order.markPaid(firstAttempt, golden, heldForReservation = true, by = UserId.SYSTEM, now = at(3), reservationGroupId = sg1001)

            assertEquals(RESERVED, order.status)
            assertEquals(sg1001, order.reservationGroupId)
            val paid = assertIs<OrderPaid>(order.getRecordedEvents().last())
            assertTrue(paid.heldForReservation)
            assertEquals(sg1001, paid.reservationGroupId)
        }

        @Test
        fun `held without a group, or paid into a group other than the one it joined, is a bug`() {
            val joined = placedOrder(placement().copy(holdingGroup = ReservationFixtures.group()))
            val other = ReservationGroupId.constructFromPersisted(1002)

            assertFailsWith<IllegalArgumentException> { placedOrder().markPaid(firstAttempt, golden, true, UserId.SYSTEM, at(3)) }
            assertFailsWith<IllegalArgumentException> { joined.markPaid(firstAttempt, joined.totals.payable, true, UserId.SYSTEM, at(3), other) }
        }

        @Test
        fun `a held order let go of is PROCESSING - any other order, or letting go twice, changes nothing`() {
            val held = placedOrder().also { it.markPaid(firstAttempt, golden, true, UserId.SYSTEM, at(3), sg1001) }
            val notHeld = placedOrder().also { it.markPaid(firstAttempt, golden, false, UserId.SYSTEM, at(3)) }
            val unpaid = placedOrder()

            listOf(held, notHeld, unpaid, held).forEach { it.releaseFromReservation(UserId.SYSTEM, at(60)) }

            assertEquals(listOf(PROCESSING, PROCESSING, PENDING_PAYMENT), listOf(held.status, notHeld.status, unpaid.status))
            assertIs<OrderReleasedFromReservation>(held.getRecordedEvents().last())
            assertEquals(listOf(3, 2, 1), listOf(held, notHeld, unpaid).map { it.getRecordedEvents().size })
        }

        @Test
        fun `paid twice is AlreadyPaid, an expired order NotPayable, another amount AmountMismatch - and none records anything`() {
            val paid = placedOrder().also { it.markPaid(firstAttempt, golden, false, UserId.SYSTEM, at(3)) }
            val expired = placedOrder().also { it.expire(UserId.SYSTEM, at(15)) }
            val unpaid = placedOrder()

            assertEquals(Order.MarkPaidFailure.AlreadyPaid, paid.markPaid(secondAttempt, golden, false, UserId.SYSTEM, at(4)).failureOrNull())
            assertEquals(Order.MarkPaidFailure.NotPayable(EXPIRED), expired.markPaid(firstAttempt, golden, false, UserId.SYSTEM, at(16)).failureOrNull())
            assertEquals(
                Order.MarkPaidFailure.AmountMismatch(expected = golden, paid = Money.constructFromPersisted(3_479_000)),
                unpaid.markPaid(firstAttempt, Money.constructFromPersisted(3_479_000), false, UserId.SYSTEM, at(3)).failureOrNull(),
            )
            assertEquals(listOf(2, 2, 1), listOf(paid, expired, unpaid).map { it.getRecordedEvents().size })
            assertEquals(PENDING_PAYMENT, unpaid.status)
        }

        @Test
        fun `a callback after the time to pay is still taken, as long as the order hasn't expired`() {
            val order = placedOrder()

            assertTrue(order.markPaid(firstAttempt, golden, false, UserId.SYSTEM, at(18)).isSuccess)
        }

        @Test
        fun `every failed attempt is recorded once - the same attempt again changes nothing`() {
            val order = placedOrder()

            order.recordPaymentFailure(firstAttempt, USER_CANCELLED, UserId.SYSTEM, at(2))
            order.recordPaymentFailure(firstAttempt, USER_CANCELLED, UserId.SYSTEM, at(2))
            order.recordPaymentFailure(secondAttempt, GATEWAY_DECLINED, UserId.SYSTEM, at(5))

            assertEquals(listOf("OrderPlaced", "OrderPaymentFailed", "OrderPaymentFailed"), order.eventTypes())
            assertEquals(PAYMENT_FAILED, order.status)
            assertEquals(
                listOf(firstAttempt to USER_CANCELLED, secondAttempt to GATEWAY_DECLINED),
                order.getRecordedEvents().filterIsInstance<OrderPaymentFailed>().map { it.paymentId to it.reason },
            )
        }

        @Test
        fun `a failed attempt on a paid or expired order records nothing`() {
            val paid = placedOrder().also { it.markPaid(firstAttempt, golden, false, UserId.SYSTEM, at(3)) }
            val expired = placedOrder().also { it.expire(UserId.SYSTEM, at(15)) }

            paid.recordPaymentFailure(secondAttempt, USER_CANCELLED, UserId.SYSTEM, at(4))
            expired.recordPaymentFailure(secondAttempt, USER_CANCELLED, UserId.SYSTEM, at(16))

            assertEquals(listOf("OrderPlaced", "OrderPaid"), paid.eventTypes())
            assertEquals(listOf("OrderPlaced", "OrderExpired"), expired.eventTypes())
            assertEquals(PROCESSING, paid.status)
        }

        @Test
        fun `an order that couldn't be paid any more, or an attempt that expired, never fails an order`() {
            assertFailsWith<IllegalArgumentException> { placedOrder().recordPaymentFailure(firstAttempt, ORDER_NOT_PAYABLE, UserId.SYSTEM, at(2)) }
            assertFailsWith<IllegalArgumentException> { placedOrder().recordPaymentFailure(firstAttempt, PAYMENT_EXPIRED, UserId.SYSTEM, at(2)) }
        }

        @Test
        fun `a new attempt may start only while unpaid and before the time to pay ends`() {
            val order = placedOrder()

            assertTrue(order.canStartPayment(at(14)))
            assertFalse(order.canStartPayment(at(15)))
            order.markPaid(firstAttempt, golden, false, UserId.SYSTEM, at(3))
            assertFalse(order.canStartPayment(at(4)))
        }
    }

    // ============ expiring ============

    @Nested
    inner class Expiring {
        @Test
        fun `before its time to pay ends it is TooEarly, at the end it expires, and again changes nothing`() {
            val order = placedOrder()

            assertEquals(Order.ExpireFailure.TooEarly(at(15)), order.expire(UserId.SYSTEM, at(15).minusSeconds(1)).failureOrNull())
            assertTrue(order.expire(UserId.SYSTEM, at(15)).isSuccess)
            assertTrue(order.expire(UserId.SYSTEM, at(16)).isSuccess)

            assertEquals(EXPIRED, order.status)
            assertEquals(listOf("OrderPlaced", "OrderExpired"), order.eventTypes())
            assertFalse(order.isAwaitingPayment)
        }

        @Test
        fun `an order whose payment failed expires too`() {
            val order = placedOrder().also { it.recordPaymentFailure(firstAttempt, USER_CANCELLED, UserId.SYSTEM, at(2)) }

            order.expire(UserId.SYSTEM, at(20))

            assertEquals(EXPIRED, order.status)
        }

        @Test
        fun `a paid order doesn't expire`() {
            val order = placedOrder().also { it.markPaid(firstAttempt, golden, false, UserId.SYSTEM, at(3)) }

            assertEquals(Order.ExpireFailure.NotAwaitingPayment(PROCESSING), order.expire(UserId.SYSTEM, at(30)).failureOrNull())
            assertEquals(PROCESSING, order.status)
        }
    }

    // ============ replay ============

    @Test
    fun `replaying placed, failed and paid gives exactly the same order`() {
        val giftNote = GiftNote.constructFromPersisted("تولدت مبارک!", hidePrices = true)
        val order = place(listOf(line(1, 301, 3, 2, price = 2_790_000), line(2, 205, 2, 1)), gift = giftNote).getOrNull()!!
        order.recordPaymentFailure(firstAttempt, USER_CANCELLED, UserId.SYSTEM, at(2))
        order.markPaid(secondAttempt, order.totals.payable, false, UserId.SYSTEM, at(6))

        val replayed = Order(order.getRecordedEvents())

        fun Order.state(): List<Any?> = listOf(
            id, code, userId, status, lines, address, messenger, shippingMethod, totals, reserveRequested, placedOn, holdUntil, paidOn,
            paidByPaymentId, customerNote, gift,
        )
        assertEquals(order.state(), replayed.state())
        assertEquals(emptyList(), replayed.getRecordedEvents())
        assertEquals(listOf(1, 2), replayed.lines.map { it.lineId })
        // The failed attempt is remembered too: the same callback again records nothing.
        replayed.recordPaymentFailure(firstAttempt, USER_CANCELLED, UserId.SYSTEM, at(7))
        assertEquals(emptyList(), replayed.getRecordedEvents())
    }

    @Test
    fun `a replayed failed attempt is not recorded again`() {
        val order = placedOrder().also { it.recordPaymentFailure(firstAttempt, USER_CANCELLED, UserId.SYSTEM, at(2)) }
        val replayed = Order(order.getRecordedEvents())

        replayed.recordPaymentFailure(firstAttempt, USER_CANCELLED, UserId.SYSTEM, at(3))

        assertEquals(emptyList(), replayed.getRecordedEvents())
        assertEquals(PAYMENT_FAILED, replayed.status)
    }

    @Test
    fun `printing an order or its first event shows no address, phone or note`() {
        val order = placedOrder()
        val printed = "$order ${order.getRecordedEvents().single()} ${order.address} ${order.messenger}"

        listOf("ولیعصر", "1965843117", "سارا", "0912", "@sara_m").forEach { assertFalse(it in printed, "$it in $printed") }
        assertEquals("Order(id=1, code=KV-218340, status=PENDING_PAYMENT)", "$order")
    }

    @Test
    fun `the lines' amounts come from the same calculation as the cart's`() {
        val order = placedOrder()

        val caramel = order.lines.first()
        assertEquals(Money.constructFromPersisted(2_790_000), caramel.totals.total)
        assertEquals(Money.constructFromPersisted(500_000), caramel.totals.discount)
        assertEquals(order.totals.subtotal, order.lines.map { it.totals.total }.fold(Money.ZERO, Money::plus))
        assertEquals(CartFixtures.AIDA_CARAMEL, caramel.variantId.value)
    }
}

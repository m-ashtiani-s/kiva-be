package com.kiva.domain.aggregate.order.entity

import com.kiva.common.railway.FieldFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.placedOrder
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.entity.event.OrderReturnEnded
import com.kiva.domain.aggregate.order.entity.event.OrderReturnReceived
import com.kiva.domain.aggregate.order.entity.event.OrderReturnRequested
import com.kiva.domain.aggregate.order.model.policy.OrderActionFacts
import com.kiva.domain.aggregate.order.model.policy.OrderActionsPolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnOutcome
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sara's golden order after its delivery (roadmap section 12.4): a return asked for, received and ended. Line 1, the caramel
 * Aida, is returnable; line 2, the brown KV-1015, isn't. Delivered at [deliveredAt], returnable for 7 days.
 */
class OrderReturnTest {

    private val operator = UserId.constructFromPersisted(900)
    private val deliveredAt = at(60 * 48)
    private val until = deliveredAt.plus(Duration.ofDays(7))
    private val first = ReturnId.constructFromPersisted(12)
    private val second = ReturnId.constructFromPersisted(13)

    private fun media(n: Int) = MediaId.constructFromPersisted("med_" + n.toString().padStart(22, '0'))

    private fun item(lineId: Int, quantity: Int = 1) = ReturnItem.constructFromPersisted(lineId, quantity)

    /** Paid, photographed, approved, shipped and delivered at [deliveredAt]. */
    private fun delivered(): Order = placedOrder().also {
        it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), Money.constructFromPersisted(3_480_000), false, sara, at(5)).getOrNull()!!
        it.attachPhotos(listOf(OrderPhoto(media(1), 1, MediaView.FRONT, 0), OrderPhoto(media(2), 2, MediaView.FRONT, 1)), operator, at(50)).getOrNull()!!
        it.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, at(60)).getOrNull()!!
        it.approvePhotos(automatic = false, sara, at(90)).getOrNull()!!
        it.ship(ShippingMethodCode.POST, TrackingCode.constructFromPersisted("TPX4821760"), at(120), true, operator, at(120)).getOrNull()!!
        it.markDelivered(7, null, operator, deliveredAt).getOrNull()!!
    }

    private fun requested(): Order = delivered().also { it.requestReturn(first, listOf(item(1)), sara, deliveredAt.plusSeconds(60)).getOrNull()!! }

    private fun received(): Order = requested().also { it.receiveReturn(first, operator, deliveredAt.plusSeconds(120)).getOrNull()!! }

    private fun canReturn(order: Order, now: Instant) = OrderActionsPolicy.actionsOf(OrderActionFacts.of(order, now)).canReturn

    @Nested
    inner class Request {
        @Test
        fun `a delivered order within its window - RETURN_REQUESTED, the return open`() {
            val order = delivered()

            order.requestReturn(first, listOf(item(1)), sara, deliveredAt.plusSeconds(60)).getOrNull()!!

            val event = assertIs<OrderReturnRequested>(order.getRecordedEvents().last())
            assertEquals(first, event.returnId)
            assertEquals(OrderStatus.RETURN_REQUESTED, order.status)
            assertEquals(first, order.openReturnId)
        }

        @Test
        fun `at the very end of the window it is still asked - a nanosecond later RETURN_WINDOW_EXPIRED with the window's end`() {
            assertTrue(delivered().requestReturn(first, listOf(item(1)), sara, until).isSuccess)

            val late = delivered().requestReturn(first, listOf(item(1)), sara, until.plusNanos(1)).failureOrNull()

            assertEquals(Order.RequestReturnFailure.WindowExpired(until), late)
            assertEquals(mapOf("returnableUntil" to until.toString()), late?.meta)
        }

        @Test
        fun `before the delivery, while a return is open, or after it arrived - ORDER_NOT_RETURNABLE`() {
            val shipped = placedOrder()
            listOf(shipped, requested(), received()).forEach { order ->
                val refused = order.requestReturn(second, listOf(item(1)), sara, deliveredAt.plusSeconds(600)).failureOrNull()

                assertEquals("ORDER_NOT_RETURNABLE", refused?.code, "${order.status}")
            }
        }

        @Test
        fun `after a return was closed or refunded it is never returned again - after one turned down it is`() {
            val closed = requested().also { it.endReturn(first, ReturnOutcome.CLOSED, operator, deliveredAt.plusSeconds(120)).getOrNull()!! }
            val refunded = received().also { it.endReturn(first, ReturnOutcome.REFUNDED, operator, deliveredAt.plusSeconds(180)).getOrNull()!! }
            val rejected = requested().also { it.endReturn(first, ReturnOutcome.REJECTED, operator, deliveredAt.plusSeconds(120)).getOrNull()!! }
            val later = deliveredAt.plusSeconds(600)

            assertEquals("ORDER_NOT_RETURNABLE", closed.requestReturn(second, listOf(item(1)), sara, later).failureOrNull()?.code)
            assertEquals("ORDER_NOT_RETURNABLE", refunded.requestReturn(second, listOf(item(1)), sara, later).failureOrNull()?.code)
            assertTrue(rejected.requestReturn(second, listOf(item(1)), sara, later).isSuccess)
            assertEquals(second, rejected.openReturnId)
        }

        @Test
        fun `a line it doesn't have, one not returnable, one twice, more than bought - each on its own field, together, nothing recorded`() {
            val order = delivered()
            val before = order.getRecordedEvents().size

            val refused = order.requestReturn(first, listOf(item(9), item(2), item(1, 2), item(1)), sara, deliveredAt.plusSeconds(60)).failureOrNull()

            assertIs<Order.RequestReturnFailure.ItemsInvalid>(refused)
            assertEquals(
                listOf(
                    "items.0.orderItemId ORDER_ITEM_NOT_FOUND", "items.1.orderItemId ITEM_NOT_RETURNABLE", "items.2.quantity RETURN_QUANTITY_EXCEEDS",
                    "items.3.orderItemId LIST_ITEM_DUPLICATE",
                ),
                refused.causes.map { "${(it as FieldFailure).name} ${it.code}" },
            )
            assertEquals(mapOf("maxQuantity" to 1), refused.causes[2].meta)
            assertEquals(before, order.getRecordedEvents().size)
            assertEquals(OrderStatus.DELIVERED, order.status)
        }
    }

    @Nested
    inner class AfterTheRequest {
        @Test
        fun `its parcel arrived - RETURNED, the return still open`() {
            val order = received()

            assertIs<OrderReturnReceived>(order.getRecordedEvents().last())
            assertEquals(OrderStatus.RETURNED, order.status)
            assertEquals(first, order.openReturnId)
        }

        @Test
        fun `turned down - DELIVERED again, nothing settled - closed - DELIVERED, settled - refunded - REFUNDED, settled`() {
            val rejected = requested().also { it.endReturn(first, ReturnOutcome.REJECTED, operator, deliveredAt.plusSeconds(120)).getOrNull()!! }
            val closed = received().also { it.endReturn(first, ReturnOutcome.CLOSED, operator, deliveredAt.plusSeconds(180)).getOrNull()!! }
            val refunded = received().also { it.endReturn(first, ReturnOutcome.REFUNDED, operator, deliveredAt.plusSeconds(180)).getOrNull()!! }

            assertEquals(listOf(OrderStatus.DELIVERED, false), listOf(rejected.status, rejected.returnSettled))
            assertEquals(listOf(OrderStatus.DELIVERED, true), listOf(closed.status, closed.returnSettled))
            assertEquals(listOf(OrderStatus.REFUNDED, true), listOf(refunded.status, refunded.returnSettled))
            listOf(rejected, closed, refunded).forEach { assertNull(it.openReturnId) }
            assertEquals(ReturnOutcome.REFUNDED, assertIs<OrderReturnEnded>(refunded.getRecordedEvents().last()).outcome)
        }

        @Test
        fun `another return than the open one, refunded before it arrived, or arriving twice is a bug and stops loudly`() {
            assertThrows<IllegalStateException> { requested().receiveReturn(second, operator, deliveredAt) }
            assertThrows<IllegalStateException> { requested().endReturn(first, ReturnOutcome.REFUNDED, operator, deliveredAt) }
            assertThrows<IllegalStateException> { received().receiveReturn(first, operator, deliveredAt) }
            assertThrows<IllegalStateException> { received().endReturn(first, ReturnOutcome.REJECTED, operator, deliveredAt) }
            assertThrows<IllegalStateException> { delivered().endReturn(first, ReturnOutcome.CLOSED, operator, deliveredAt) }
        }
    }

    @Nested
    inner class Buttons {
        @Test
        fun `«مرجوع کردن» shows while delivered within the window - not while open, after it was closed, or after the window`() {
            val inside = deliveredAt.plusSeconds(600)

            assertTrue(canReturn(delivered(), inside))
            assertTrue(canReturn(delivered(), until))
            assertFalse(canReturn(delivered(), until.plusNanos(1)))
            assertFalse(canReturn(requested(), inside))
            assertFalse(canReturn(received(), inside))
            assertFalse(canReturn(received().also { it.endReturn(first, ReturnOutcome.CLOSED, operator, inside).getOrNull()!! }, inside), "closed, even within the window")
            assertTrue(canReturn(requested().also { it.endReturn(first, ReturnOutcome.REJECTED, operator, inside).getOrNull()!! }, inside), "turned down")
        }

        @Test
        fun `an order delivered with nothing returnable never shows it`() {
            val order = delivered()
            val nothingReturnable = OrderActionFacts.of(order, deliveredAt).copy(returnableUntil = null)

            assertFalse(OrderActionsPolicy.actionsOf(nothingReturnable).canReturn)
            assertFalse(canReturn(placedOrder(), deliveredAt), "never delivered")
        }
    }

    @Test
    fun `asked, received, refunded - replaying the events gives the same order, and a turned-down one the same too`() {
        val refunded = received().also { it.endReturn(first, ReturnOutcome.REFUNDED, operator, deliveredAt.plusSeconds(180)).getOrNull()!! }
        val rejected = requested().also { it.endReturn(first, ReturnOutcome.REJECTED, operator, deliveredAt.plusSeconds(120)).getOrNull()!! }

        listOf(refunded, rejected, received()).forEach { order ->
            val replayed = Order(order.getRecordedEvents())

            assertEquals(listOf(order.status, order.openReturnId, order.returnSettled, order.version), listOf(replayed.status, replayed.openReturnId, replayed.returnSettled, replayed.version))
        }
    }
}

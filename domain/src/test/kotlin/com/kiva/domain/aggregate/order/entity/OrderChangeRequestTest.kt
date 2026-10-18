package com.kiva.domain.aggregate.order.entity

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.discount.DiscountFixtures
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.OrderFixtures
import com.kiva.domain.aggregate.order.OrderFixtures.aidaCaramel
import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.line
import com.kiva.domain.aggregate.order.OrderFixtures.placedOrder
import com.kiva.domain.aggregate.order.OrderFixtures.placement
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.entity.event.OrderBalancePaid
import com.kiva.domain.aggregate.order.entity.event.OrderChangeApplied
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRejected
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRequested
import com.kiva.domain.aggregate.order.entity.event.OrderLineRemoved
import com.kiva.domain.aggregate.order.entity.event.OrderLineReplaced
import com.kiva.domain.aggregate.order.model.OrderPlacement
import com.kiva.domain.aggregate.order.model.policy.ChangeRequestPolicy
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.ChangeRequest
import com.kiva.domain.aggregate.order.model.valueobject.ChangeTarget
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A change request after the photos (roadmap section 8.3) on Sara's golden order: the caramel Aida (2,790,000) and the
 * brown KV-1015 (690,000), 3,480,000 shipped free by post.
 */
class OrderChangeRequestTest {

    private val operator = UserId.constructFromPersisted(900)
    private fun toman(amount: Long) = Money.constructFromPersisted(amount)
    private fun note(text: String) = StatusNote.constructFromPersisted(text)
    private fun variant(id: Long) = VariantId.constructFromPersisted(id)
    private fun product(id: Long) = ProductId.constructFromPersisted(id)
    private val limit5 = MaxPerOrder.constructFromPersisted(5)

    private var minute = 100L

    /** The next minute: each step of a test a minute after the one before. */
    private fun next(): Instant = at(minute++)

    private fun paid(placement: OrderPlacement = placement()): Order = placedOrder(placement).also {
        it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), it.totals.payable, heldForReservation = false, sara, at(5)).getOrNull()!!
    }

    private var media = 0

    /** Photos of every line sent: PHOTO_SENT with photos SENT. */
    private fun Order.sendPhotos(): Order = apply {
        attachPhotos(lines.map { OrderPhoto(MediaId.constructFromPersisted("med_" + (++media).toString().padStart(22, '0')), it.lineId, MediaView.FRONT, media) }, operator, next())
            .getOrNull()!!
        sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, next()).getOrNull()!!
    }

    private fun photoSent(placement: OrderPlacement = placement()) = paid(placement).sendPhotos()

    private fun color(lineId: Int = 1, desired: Long = 302) = ChangeRequest(ChangeType.COLOR, lineId, variant(desired), null)

    private fun Order.ask(request: ChangeRequest, target: ChangeTarget? = request.desiredVariantId?.let { ChangeTarget(it, product(3)) }): Order = apply {
        requestChange(request, target, sara, next()).getOrNull() ?: error("refused: ${requestChange(request, target, sara, next()).failureOrNull()}")
    }

    /** Line 1 (the caramel Aida) as another Aida color at [price]. */
    private fun Order.aidaInstead(variantId: Long = 302, price: Long, productId: Long = 3): OrderLine = lines.first { it.lineId == 1 }.replacedBy(
        product(productId), variant(variantId), OrderFixtures.item("KV-1003-BLACK", "black", "مشکی"), PriceTag.constructFromPersisted(toman(price), null),
        reservable = true, returnable = true,
    )

    /** Asks for line 1 in another Aida color and carries it out at [price]: the settlement. */
    private fun Order.swapLine1(price: Long, variantId: Long = 302): ChangeRequestPolicy.Settlement {
        ask(color(desired = variantId))
        replaceLine(aidaInstead(variantId, price), limit5, operator, next()).getOrNull()!!
        return applyChange(null, operator, next()).getOrNull()!!
    }

    @Nested
    inner class RequestingAChange {
        @Test
        fun `asking after photos sent makes it CHANGE_REQUESTED, its photos too, with the request open`() {
            val order = photoSent()

            order.requestChange(color(), ChangeTarget(variant(302), product(3)), sara, next()).getOrNull()!!

            assertIs<OrderChangeRequested>(order.getRecordedEvents().last())
            assertEquals(OrderStatus.CHANGE_REQUESTED, order.status)
            assertEquals(PhotoStatus.CHANGE_REQUESTED, order.photoStatus)
            assertEquals(color(), order.openChange)
        }

        @Test
        fun `after approving the photos the customer may still ask`() {
            val order = photoSent()
            order.approvePhotos(automatic = false, sara, next()).getOrNull()!!

            order.ask(ChangeRequest(ChangeType.OTHER, null, null, note("بند بلندتر لطفاً")))

            assertEquals(OrderStatus.CHANGE_REQUESTED, order.status)
        }

        @Test
        fun `in PROCESSING (no photos yet) or SHIPPED it is CHANGE_REQUEST_NOT_ALLOWED`() {
            val shipped = photoSent().also {
                it.approvePhotos(automatic = false, sara, next()).getOrNull()!!
                it.ship(ShippingMethodCode.POST, TrackingCode.constructFromPersisted("TPX4821760"), next(), true, operator, at(minute)).getOrNull()!!
            }

            listOf(paid(), shipped).forEach { order ->
                val refused = order.requestChange(color(), ChangeTarget(variant(302), product(3)), sara, next()).failureOrNull()
                assertEquals(Order.RequestChangeFailure.NotAllowed(order.status), refused)
                assertEquals("CHANGE_REQUEST_NOT_ALLOWED", refused?.code)
            }
        }

        @Test
        fun `a second request while one is open is CHANGE_REQUEST_NOT_ALLOWED`() {
            val order = photoSent().ask(color())

            val refused = order.requestChange(ChangeRequest(ChangeType.OTHER, null, null, note("یه چیز دیگه")), null, sara, next()).failureOrNull()

            assertEquals(Order.RequestChangeFailure.NotAllowed(OrderStatus.CHANGE_REQUESTED), refused)
        }

        @Test
        fun `a line the order doesn't have is ORDER_ITEM_NOT_FOUND on orderItemId`() {
            val refused = photoSent().requestChange(ChangeRequest(ChangeType.CANCEL_ITEM, 3, null, null), null, sara, next()).failureOrNull()

            assertEquals(Order.RequestChangeFailure.UnknownLine(3), refused)
            assertEquals("orderItemId", refused?.field)
        }

        @Test
        fun `COLOR with another product's color or the line's own color, and MODEL with a color of the same product, are DESIRED_VARIANT_INVALID`() {
            val order = photoSent()

            listOf(
                color(desired = 201) to ChangeTarget(variant(201), product(2)),
                color(desired = 301) to ChangeTarget(variant(301), product(3)),
                ChangeRequest(ChangeType.MODEL, 1, variant(302), null) to ChangeTarget(variant(302), product(3)),
            ).forEach { (request, target) ->
                val refused = order.requestChange(request, target, sara, next()).failureOrNull()
                assertEquals(Order.RequestChangeFailure.DesiredVariantInvalid, refused, "$request")
                assertEquals("desiredVariantId", refused?.field)
            }
            assertEquals(OrderStatus.PHOTO_SENT, order.status)
        }

        @Test
        fun `MODEL with a color of another product is taken`() {
            val order = photoSent().ask(ChangeRequest(ChangeType.MODEL, 1, variant(201), null), ChangeTarget(variant(201), product(2)))

            assertEquals(ChangeType.MODEL, order.openChange?.type)
        }
    }

    @Nested
    inner class Rejecting {
        @Test
        fun `rejected with a note - PROCESSING, photos WAITING for a new batch, the last batch still there, nothing owed`() {
            val order = photoSent().ask(color())
            val lastBatch = order.photosSent

            order.rejectChange(note("مشکی تموم شده"), operator, next()).getOrNull()!!

            assertIs<OrderChangeRejected>(order.getRecordedEvents().last())
            assertEquals(OrderStatus.PROCESSING, order.status)
            assertEquals(PhotoStatus.WAITING, order.photoStatus)
            assertNull(order.openChange)
            assertEquals(lastBatch, order.photosSent)
            assertEquals(Money.ZERO, order.balanceDue)
        }

        @Test
        fun `rejected without a note is NOTE_REQUIRED on note`() {
            val refused = photoSent().ask(color()).rejectChange(null, operator, next()).failureOrNull()

            assertEquals(Order.ResolveChangeFailure.NoteRequired, refused)
            assertEquals("note", refused?.field)
        }

        @Test
        fun `nothing open to resolve is INVALID_STATUS_TRANSITION from the status, to PROCESSING`() {
            val refused = photoSent().rejectChange(note("نه"), operator, next()).failureOrNull()

            assertIs<Order.ResolveChangeFailure.InvalidTransition>(refused)
            assertEquals("INVALID_STATUS_TRANSITION", refused.code)
            assertEquals(mapOf("from" to "PHOTO_SENT", "to" to "PROCESSING", "allowed" to listOf("CANCELLED")), refused.meta)
        }
    }

    @Nested
    inner class SwappingAColor {
        @Test
        fun `golden - Aida caramel 2,790,000 to Aida black at 2,990,000 - 200,000 owed, the line swapped in place`() {
            val order = photoSent()

            val settled = order.swapLine1(2_990_000)

            assertEquals(ChangeRequestPolicy.Settlement(toman(200_000), Money.ZERO), settled)
            assertEquals(toman(200_000), order.balanceDue)
            assertEquals(OrderStatus.PROCESSING, order.status)
            assertEquals(PhotoStatus.WAITING, order.photoStatus)
            assertEquals(listOf(1 to 302L, 2 to OrderFixtures.KV1015_BROWN), order.lines.map { it.lineId to it.variantId.value })
            val replaced = order.getRecordedEvents().filterIsInstance<OrderLineReplaced>().single()
            assertEquals(200_000L, replaced.priceDifference)
            val applied = order.getRecordedEvents().filterIsInstance<OrderChangeApplied>().single()
            assertEquals(variant(302), applied.variantId)
            assertEquals(toman(200_000), applied.balanceDueAfter)
            val view = order.totalsView
            assertEquals(listOf(toman(3_680_000), toman(3_480_000), toman(200_000)), listOf(view.subtotal, view.payable, view.balanceDue))
        }

        @Test
        fun `golden - the same at 2,590,000 - nothing owed and 200,000 to refund`() {
            val order = photoSent()

            val settled = order.swapLine1(2_590_000)

            assertEquals(ChangeRequestPolicy.Settlement(Money.ZERO, toman(200_000)), settled)
            assertEquals(Money.ZERO, order.balanceDue)
            assertEquals(toman(200_000), order.differenceRefunded)
        }

        @Test
        fun `golden - +300,000 then -500,000 - nothing owed and 200,000 to refund`() {
            val order = photoSent()

            val first = order.swapLine1(3_090_000)
            order.sendPhotos()
            val second = order.swapLine1(2_590_000, variantId = 303)

            assertEquals(ChangeRequestPolicy.Settlement(toman(300_000), Money.ZERO), first)
            assertEquals(ChangeRequestPolicy.Settlement(Money.ZERO, toman(200_000)), second)
            assertEquals(Money.ZERO, order.balanceDue)
            assertEquals(toman(200_000), order.differenceRefunded)
        }

        @Test
        fun `the color put in must still fit the change - another product for a COLOR change is DESIRED_VARIANT_INVALID on variantId`() {
            val order = photoSent().ask(color())

            val refused = order.replaceLine(order.aidaInstead(201, 1_450_000, productId = 2), limit5, operator, next()).failureOrNull()

            assertEquals(Order.ReplaceLineFailure.VariantInvalid, refused)
            assertEquals("variantId", refused?.field)
        }

        @Test
        fun `more of the new product than its limit per order is QUANTITY_EXCEEDS_LIMIT`() {
            // Line 1 to a color of KV-1015, which line 2 has already: 2 of a product that allows 1.
            val order = photoSent().ask(ChangeRequest(ChangeType.MODEL, 1, variant(1502), null), ChangeTarget(variant(1502), product(15)))

            val refused = order.replaceLine(order.aidaInstead(1502, 690_000, productId = 15), MaxPerOrder.constructFromPersisted(1), operator, next()).failureOrNull()

            assertEquals(Order.ReplaceLineFailure.LimitExceeded(product(15)), refused)
            assertEquals("QUANTITY_EXCEEDS_LIMIT", refused?.code)
        }

        @Test
        fun `swapping when nothing is open is INVALID_STATUS_TRANSITION`() {
            val order = photoSent()

            assertIs<Order.ReplaceLineFailure.InvalidTransition>(order.replaceLine(order.aidaInstead(price = 2_990_000), limit5, operator, next()).failureOrNull())
        }

        @Test
        fun `applying a COLOR change before its line is swapped is a bug`() {
            val order = photoSent().ask(color())

            assertThrows<IllegalArgumentException> { order.applyChange(null, operator, next()) }
        }
    }

    @Nested
    inner class TakingALineOut {
        @Test
        fun `the brown KV-1015 taken out - gone from the lines, its 690,000 to refund`() {
            val order = photoSent().ask(ChangeRequest(ChangeType.CANCEL_ITEM, 2, null, null))

            order.removeLine(operator, next()).getOrNull()!!
            val settled = order.applyChange(null, operator, next()).getOrNull()!!

            assertEquals(toman(690_000), order.getRecordedEvents().filterIsInstance<OrderLineRemoved>().single().paidShare)
            assertEquals(ChangeRequestPolicy.Settlement(Money.ZERO, toman(690_000)), settled)
            assertEquals(listOf(1), order.lines.map { it.lineId })
            assertEquals(OrderStatus.PROCESSING, order.status)
        }

        @Test
        fun `with a code of 348,000 - 621,000 comes back and the code's 69,000 share leaves with the line`() {
            val order = photoSent(placement(codeDiscount = DiscountFixtures.codeDiscount(348_000))).ask(ChangeRequest(ChangeType.CANCEL_ITEM, 2, null, null))

            order.removeLine(operator, next()).getOrNull()!!
            val settled = order.applyChange(null, operator, next()).getOrNull()!!

            assertEquals(toman(621_000), settled.refund)
            assertEquals(toman(69_000), order.codeDiscountRemoved)
            assertEquals(toman(279_000), order.totalsView.codeDiscount)
        }

        @Test
        fun `what was owed is taken off first - 200,000 owed, then a line of 690,000 out, 490,000 to refund`() {
            val order = photoSent()
            order.swapLine1(2_990_000)
            order.sendPhotos().ask(ChangeRequest(ChangeType.CANCEL_ITEM, 2, null, null))

            order.removeLine(operator, next()).getOrNull()!!
            val settled = order.applyChange(null, operator, next()).getOrNull()!!

            assertEquals(ChangeRequestPolicy.Settlement(Money.ZERO, toman(490_000)), settled)
        }

        @Test
        fun `the only line is never taken out - that is the order cancelled, so asking the aggregate is a bug`() {
            val order = photoSent(placement(lines = listOf(line(1, aidaCaramel)))).ask(ChangeRequest(ChangeType.CANCEL_ITEM, 1, null, null))

            assertThrows<IllegalArgumentException> { order.removeLine(operator, next()) }
        }

        @Test
        fun `a line taken out is gone - asking about it again is ORDER_ITEM_NOT_FOUND`() {
            val order = photoSent().ask(ChangeRequest(ChangeType.CANCEL_ITEM, 2, null, null))
            order.removeLine(operator, next()).getOrNull()!!
            order.applyChange(null, operator, next()).getOrNull()!!
            order.sendPhotos()

            assertEquals(
                Order.RequestChangeFailure.UnknownLine(2),
                order.requestChange(ChangeRequest(ChangeType.CANCEL_ITEM, 2, null, null), null, sara, next()).failureOrNull(),
            )
        }
    }

    @Nested
    inner class Other {
        @Test
        fun `OTHER is applied with a note of what was done, and changes no money`() {
            val order = photoSent().ask(ChangeRequest(ChangeType.OTHER, null, null, note("بند بلندتر")))

            val settled = order.applyChange(note("بند بلندتر گذاشتیم"), operator, next()).getOrNull()!!

            assertEquals(ChangeRequestPolicy.Settlement(Money.ZERO, Money.ZERO), settled)
            assertEquals(OrderStatus.PROCESSING, order.status)
        }

        @Test
        fun `OTHER applied without a note is NOTE_REQUIRED`() {
            val order = photoSent().ask(ChangeRequest(ChangeType.OTHER, null, null, note("بند بلندتر")))

            assertEquals(Order.ResolveChangeFailure.NoteRequired, order.applyChange(null, operator, next()).failureOrNull())
        }
    }

    @Nested
    inner class TheBalanceDue {
        private val balanceAttempt = PaymentPublicId.constructFromPersisted("pay_0000000000B2")

        /** 200,000 owed, its new photos sent and approved: everything but the money lets it ship. */
        private fun owing(): Order = photoSent().also {
            it.swapLine1(2_990_000)
            it.sendPhotos()
            it.approvePhotos(automatic = false, sara, next()).getOrNull()!!
        }

        private fun Order.shipNow() = ship(ShippingMethodCode.POST, TrackingCode.constructFromPersisted("TPX4821760"), at(minute), true, operator, at(minute++))

        @Test
        fun `it doesn't ship while something is owed - 422 BALANCE_DUE with the amount`() {
            val refused = owing().shipNow().failureOrNull()

            assertEquals(Order.ShipFailure.BalanceDue(toman(200_000)), refused)
            assertEquals(mapOf("balanceDue" to 200_000L), refused?.meta)
        }

        @Test
        fun `paid exactly - nothing owed, the money added to what was paid, the status unchanged, and it ships`() {
            val order = owing()

            order.payBalance(balanceAttempt, toman(200_000), sara, next()).getOrNull()!!

            assertIs<OrderBalancePaid>(order.getRecordedEvents().last())
            assertEquals(listOf(Money.ZERO, toman(200_000)), listOf(order.balanceDue, order.balancePaid))
            assertEquals(OrderStatus.PHOTO_SENT, order.status)
            assertEquals(toman(3_680_000), order.totalsView.payable)
            order.shipNow().getOrNull()!!
        }

        @Test
        fun `the same attempt again changes nothing`() {
            val order = owing()
            order.payBalance(balanceAttempt, toman(200_000), sara, next()).getOrNull()!!
            val events = order.getRecordedEvents().size

            order.payBalance(balanceAttempt, toman(200_000), sara, next()).getOrNull()!!

            assertEquals(events, order.getRecordedEvents().size)
        }

        @Test
        fun `nothing owed is ORDER_ALREADY_PAID, and another amount is PAYMENT_AMOUNT_MISMATCH`() {
            assertEquals(Order.PayBalanceFailure.NoBalanceDue, photoSent().payBalance(balanceAttempt, toman(1), sara, next()).failureOrNull())
            assertEquals(
                Order.PayBalanceFailure.AmountMismatch(expected = toman(200_000), paid = toman(150_000)),
                owing().payBalance(balanceAttempt, toman(150_000), sara, next()).failureOrNull(),
            )
        }

        @Test
        fun `a cancelled order owes nothing - cancelling closes the open change and clears what was owed`() {
            val order = owing().ask(ChangeRequest(ChangeType.OTHER, null, null, note("پشیمون شدم")))

            order.cancel(CancelledBy.STAFF, null, null, operator, next()).getOrNull()!!

            assertEquals(OrderStatus.CANCELLED, order.status)
            assertNull(order.openChange)
            assertEquals(Money.ZERO, order.balanceDue)
            assertEquals(Order.PayBalanceFailure.NoBalanceDue, order.payBalance(balanceAttempt, toman(200_000), sara, next()).failureOrNull())
        }

        @Test
        fun `what was paid for a change can be refunded too, never more than everything paid`() {
            val order = owing()
            order.payBalance(balanceAttempt, toman(200_000), sara, next()).getOrNull()!!

            order.recordRefund(RefundId.constructFromPersisted(1), toman(3_680_000), operator, next()).getOrNull()!!
            assertThrows<IllegalStateException> { order.recordRefund(RefundId.constructFromPersisted(2), toman(1), operator, next()) }
        }
    }

    @Nested
    inner class TheTotalsIdentity {
        /** The identity of decision 7: subtotal − codeDiscount + shippingCost = payable + balanceDue − what the changes refunded. */
        private fun assertIdentity(order: Order, case: String) {
            val view = order.totalsView
            assertEquals(
                view.subtotal.toman - view.codeDiscount.toman + view.shippingCost.toman,
                view.payable.toman + view.balanceDue.toman - order.differenceRefunded.toman,
                case,
            )
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.kiva.domain.aggregate.order.entity.OrderChangeRequestTest#combinations")
        fun `holds after every combination of changes`(case: String, withCode: Boolean, steps: List<String>) {
            val order = photoSent(if (withCode) placement(codeDiscount = DiscountFixtures.codeDiscount(348_000)) else placement())
            steps.forEachIndexed { index, step ->
                if (order.status == OrderStatus.PROCESSING) order.sendPhotos()
                when (step) {
                    "dearer" -> order.swapLine1(order.lines.first { it.lineId == 1 }.priceTag.price.toman + 200_000, variantId = 302L + index)
                    "cheaper" -> order.swapLine1(order.lines.first { it.lineId == 1 }.priceTag.price.toman - 500_000, variantId = 302L + index)
                    "same" -> order.swapLine1(order.lines.first { it.lineId == 1 }.priceTag.price.toman, variantId = 302L + index)
                    "pay" -> order.payBalance(PaymentPublicId.constructFromPersisted("pay_000000000${index}C3"), order.balanceDue, sara, next()).getOrNull()!!
                    "remove" -> {
                        order.ask(ChangeRequest(ChangeType.CANCEL_ITEM, 2, null, null))
                        order.removeLine(operator, next()).getOrNull()!!
                        order.applyChange(null, operator, next()).getOrNull()!!
                    }
                    "other" -> order.ask(ChangeRequest(ChangeType.OTHER, null, null, note("بند"))).applyChange(note("انجام شد"), operator, next()).getOrNull()!!
                    "reject" -> order.ask(color()).rejectChange(note("نه"), operator, next()).getOrNull()!!
                    else -> error(step)
                }
                assertIdentity(order, "$case after $step")
            }
            assertIdentity(order, case)
            // Read back from its events, it has the same money.
            val again = Order(order.getRecordedEvents())
            assertEquals(order.totalsView, again.totalsView, case)
            assertEquals(order.differenceRefunded, again.differenceRefunded, case)
        }
    }

    @Nested
    inner class ReadingBack {
        @Test
        fun `an order read back from its events has the same lines, money, status and open change`() {
            val order = photoSent(placement(codeDiscount = DiscountFixtures.codeDiscount(348_000)))
            order.swapLine1(2_990_000)
            order.sendPhotos().ask(ChangeRequest(ChangeType.CANCEL_ITEM, 2, null, null))

            val again = Order(order.getRecordedEvents())

            assertEquals(order.lines, again.lines)
            assertEquals(listOf(order.balanceDue, order.balancePaid, order.differenceRefunded, order.codeDiscountRemoved), listOf(again.balanceDue, again.balancePaid, again.differenceRefunded, again.codeDiscountRemoved))
            assertEquals(order.status, again.status)
            assertEquals(order.openChange, again.openChange)
        }

        @Test
        fun `a stream whose applied change settled to another amount than its lines say is broken and stops`() {
            val order = photoSent()
            order.swapLine1(2_990_000)
            val events = order.getRecordedEvents().map { event ->
                if (event is OrderChangeApplied) event.copy(balanceDueAfter = toman(1)) else event
            }

            assertThrows<IllegalStateException> { Order(events) }
        }
    }

    companion object {
        @JvmStatic
        fun combinations(): List<Array<Any>> = listOf(
            arrayOf("nothing changed", false, emptyList<String>()),
            arrayOf("a dearer color", false, listOf("dearer")),
            arrayOf("a dearer color, then paid", false, listOf("dearer", "pay")),
            arrayOf("a cheaper color", false, listOf("cheaper")),
            arrayOf("a color at the same price", false, listOf("same")),
            arrayOf("dearer, then cheaper", false, listOf("dearer", "cheaper")),
            arrayOf("dearer, then a line out", false, listOf("dearer", "remove")),
            arrayOf("a line out", false, listOf("remove")),
            arrayOf("a line out with a code", true, listOf("remove")),
            arrayOf("a dearer color with a code, paid, then a line out", true, listOf("dearer", "pay", "remove")),
            arrayOf("rejected, then OTHER", false, listOf("reject", "other")),
        )
    }
}

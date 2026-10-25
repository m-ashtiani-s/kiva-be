package com.kiva.domain.aggregate.order.entity

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.placedOrder
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.entity.event.OrderCancelled
import com.kiva.domain.aggregate.order.entity.event.OrderDelivered
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosSent
import com.kiva.domain.aggregate.order.entity.event.OrderShipped
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.PhotoMessage
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sara's golden order after its payment (roadmap section 7.4): its photos, the customer's answer, shipping, delivery,
 * cancelling and refunds. Two lines: the caramel Aida (returnable) and the brown KV-1015 (not returnable).
 */
class OrderAfterPaymentTest {

    private val operator = UserId.constructFromPersisted(900)
    private val golden = Money.constructFromPersisted(3_480_000)
    private val paidAt = at(5)
    private val tracking = TrackingCode.constructFromPersisted("TPX4821760")

    private fun media(n: Int) = MediaId.constructFromPersisted("med_" + n.toString().padStart(22, '0'))

    private fun photo(n: Int, lineId: Int?, view: MediaView? = MediaView.FRONT) = OrderPhoto(media(n), lineId, view, n)

    /** Sara's golden order, paid at [paidAt] and being prepared. */
    private fun paid(): Order = placedOrder().also {
        it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), golden, heldForReservation = false, sara, paidAt).getOrNull()!!
    }

    private fun held(): Order = placedOrder().also {
        it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), golden, heldForReservation = true, sara, paidAt, ReservationGroupId.constructFromPersisted(7))
            .getOrNull()!!
    }

    private val sentAt = at(60)

    /** Paid, and a photo of each line sent at [sentAt]. */
    private fun photoSent(): Order = paid().also {
        it.attachPhotos(listOf(photo(1, 1), photo(2, 2)), operator, at(50)).getOrNull()!!
        it.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, sentAt).getOrNull()!!
    }

    private val deadline = sentAt.plus(Duration.ofHours(24))

    private fun approved(): Order = photoSent().also { it.approvePhotos(automatic = false, sara, at(90)).getOrNull()!! }

    private val shipAt = at(120)

    private fun shipped(): Order = approved().also { it.ship(ShippingMethodCode.POST, tracking, shipAt, true, operator, shipAt).getOrNull()!! }

    private fun Order.types() = getRecordedEvents().map { it.type.value }

    @Nested
    inner class Photos {
        @Test
        fun `the draft is replaced as a whole, and the order stays PROCESSING with its photos WAITING`() {
            val order = paid()

            order.attachPhotos(listOf(photo(1, 1)), operator, at(40)).getOrNull()!!
            order.attachPhotos(listOf(photo(2, 1), photo(3, 2)), operator, at(41)).getOrNull()!!

            assertEquals(listOf(photo(2, 1), photo(3, 2)), order.photoDraft)
            assertEquals(OrderStatus.PROCESSING, order.status)
            assertEquals(PhotoStatus.WAITING, order.photoStatus)
            assertEquals(emptyList(), order.photosSent)
        }

        @Test
        fun `photos are refused unless PROCESSING with photos WAITING - an unpaid, held or photographed order is 422`() {
            listOf(placedOrder(), held(), photoSent()).forEach { order ->
                val refused = order.attachPhotos(listOf(photo(1, 1)), operator, at(70)).failureOrNull()
                assertIs<Order.AttachPhotosFailure.NotInPhotoStep>(refused, "${order.status}")
                assertEquals("INVALID_STATUS_TRANSITION", refused.code)
            }
        }

        @Test
        fun `a photo of a line the order doesn't have is ORDER_ITEM_NOT_FOUND on its own field`() {
            val refused = paid().attachPhotos(listOf(photo(1, 1), photo(2, 3)), operator, at(40)).failureOrNull()

            assertEquals(Order.AttachPhotosFailure.UnknownLine(1, 3), refused)
            assertEquals("items.1.orderItemId", refused?.field)
        }

        @Test
        fun `sending makes it PHOTO_SENT - the draft becomes the batch, a deadline 24 hours on, and nothing left in the draft`() {
            val order = paid()
            order.attachPhotos(listOf(photo(1, 1), photo(2, 2)), operator, at(50)).getOrNull()!!

            order.sendPhotos(MessengerChannel.BALE, PhotoMessage.constructFromPersisted("این کیف خودته"), 24, operator, sentAt).getOrNull()!!

            val sent = assertIs<OrderPhotosSent>(order.getRecordedEvents().last())
            assertEquals(1, sent.batchNo)
            assertTrue(sent.manual)
            assertEquals(MessengerChannel.BALE, sent.channel)
            assertEquals(OrderStatus.PHOTO_SENT, order.status)
            assertEquals(PhotoStatus.SENT, order.photoStatus)
            assertEquals(listOf(photo(1, 1), photo(2, 2)), order.photosSent)
            assertEquals(emptyList(), order.photoDraft)
            assertTrue(order.photoEverSent)
            assertEquals(sentAt, order.photosSentOn)
            assertEquals(deadline, order.feedbackDeadline)
        }

        @Test
        fun `an order of two lines with photos of only the first is PHOTOS_INCOMPLETE naming the second`() {
            val order = paid()
            order.attachPhotos(listOf(photo(1, 1), photo(2, null)), operator, at(50)).getOrNull()!!

            val refused = order.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, sentAt).failureOrNull()

            assertEquals(Order.SendPhotosFailure.PhotosIncomplete(listOf(2)), refused)
            assertEquals(mapOf("missingItemIds" to listOf(2)), refused?.meta)
        }

        @Test
        fun `nothing to send, or not in the photo step, is 422 INVALID_STATUS_TRANSITION`() {
            assertIs<Order.SendPhotosFailure.NotInPhotoStep>(paid().sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, sentAt).failureOrNull())
            assertIs<Order.SendPhotosFailure.NotInPhotoStep>(photoSent().sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, at(70)).failureOrNull())
        }

        @Test
        fun `the customer approves photos sent - APPROVED, the order still PHOTO_SENT - and approving again changes nothing`() {
            val order = photoSent()

            order.approvePhotos(automatic = false, sara, at(90)).getOrNull()!!
            order.approvePhotos(automatic = false, sara, at(91)).getOrNull()!!

            assertEquals(PhotoStatus.APPROVED, order.photoStatus)
            assertEquals(OrderStatus.PHOTO_SENT, order.status)
            assertEquals(1, order.types().count { it == "OrderPhotosApproved" })
        }

        @Test
        fun `nothing waits for an answer before the photos are sent - 422`() {
            val refused = paid().approvePhotos(automatic = false, sara, at(90)).failureOrNull()

            assertIs<Order.ApprovePhotosFailure.NotAwaitingApproval>(refused)
            assertEquals("INVALID_STATUS_TRANSITION", refused.code)
        }

        @Test
        fun `the system approves only once the deadline has come`() {
            val early = photoSent().approvePhotos(automatic = true, UserId.SYSTEM, deadline.minusSeconds(1)).failureOrNull()
            assertEquals(Order.ApprovePhotosFailure.NotDue(deadline), early)

            val order = photoSent()
            order.approvePhotos(automatic = true, UserId.SYSTEM, deadline).getOrNull()!!
            assertEquals(PhotoStatus.APPROVED, order.photoStatus)
        }
    }

    @Nested
    inner class Shipping {
        @Test
        fun `an approved order ships - SHIPPED with its parcel`() {
            val order = approved()

            order.ship(ShippingMethodCode.POST, tracking, shipAt, showInDailyList = false, operator, shipAt).getOrNull()!!

            val shipped = assertIs<OrderShipped>(order.getRecordedEvents().last())
            assertEquals(OrderStatus.SHIPPED, order.status)
            assertEquals(tracking, order.shipment?.trackingCode)
            assertEquals(shipAt, order.shipment?.shippedOn)
            assertFalse(shipped.showInDailyList)
            assertNull(shipped.reservationGroupId)
        }

        @Test
        fun `before approval and before the deadline it is AWAITING_CUSTOMER_FEEDBACK - after the deadline without an answer it ships`() {
            val refused = photoSent().ship(ShippingMethodCode.POST, tracking, at(100), true, operator, at(100)).failureOrNull()
            assertEquals(Order.ShipFailure.AwaitingFeedback(deadline), refused)
            assertEquals(mapOf("feedbackDeadline" to deadline.toString()), refused?.meta)

            val silent = photoSent()
            assertTrue(silent.ship(ShippingMethodCode.POST, tracking, deadline, true, operator, deadline).isSuccess)
            assertEquals(OrderStatus.SHIPPED, silent.status)
        }

        @Test
        fun `another carrier than the one paid for is CARRIER_MISMATCH on carrier`() {
            val refused = approved().ship(ShippingMethodCode.TIPAX, tracking, shipAt, true, operator, shipAt).failureOrNull()

            assertEquals(Order.ShipFailure.CarrierMismatch(ShippingMethodCode.POST), refused)
            assertEquals("carrier", refused?.field)
        }

        @Test
        fun `handed over in the future, more than 7 days ago, or before the photos were sent is SHIPPED_AT_INVALID`() {
            val now = at(60 * 24 * 9)
            listOf(now.plusSeconds(1), now.minus(Duration.ofDays(7)).minusSeconds(1), sentAt.minusSeconds(1)).forEach { shippedOn ->
                assertEquals(Order.ShipFailure.ShippedOnInvalid, approved().ship(ShippingMethodCode.POST, tracking, shippedOn, true, operator, now).failureOrNull(), "$shippedOn")
            }
            assertTrue(approved().ship(ShippingMethodCode.POST, tracking, now.minus(Duration.ofDays(7)), true, operator, now).isSuccess)
        }

        @Test
        fun `only a PHOTO_SENT order ships - PROCESSING and SHIPPED are 422 with what is allowed`() {
            val refused = paid().ship(ShippingMethodCode.POST, tracking, shipAt, true, operator, shipAt).failureOrNull()
            assertIs<Order.ShipFailure.InvalidTransition>(refused)
            assertEquals(mapOf("from" to "PROCESSING", "to" to "SHIPPED", "allowed" to listOf("CANCELLED")), refused.meta)

            assertIs<Order.ShipFailure.InvalidTransition>(shipped().ship(ShippingMethodCode.POST, tracking, shipAt, true, operator, at(130)).failureOrNull())
        }

        @Test
        fun `a shipped order's parcel is corrected - the same parcel again changes nothing - a delivered one is 422`() {
            val order = shipped()
            val other = TrackingCode.constructFromPersisted("TPX0000001")

            order.correctShipment(other, shipAt, true, operator, at(130)).getOrNull()!!
            order.correctShipment(other, shipAt, true, operator, at(131)).getOrNull()!!

            assertEquals(other, order.shipment?.trackingCode)
            assertEquals(1, order.types().count { it == "OrderShipmentCorrected" })
            assertEquals(OrderStatus.SHIPPED, order.status)

            order.markDelivered(7, null, operator, at(200)).getOrNull()!!
            assertIs<Order.CorrectShipmentFailure.InvalidTransition>(order.correctShipment(tracking, shipAt, true, operator, at(201)).failureOrNull())
        }

        @Test
        fun `delivered - DELIVERED now, returnable for 7 days because a line is returnable - only from SHIPPED`() {
            val order = shipped()
            val deliveredAt = at(60 * 48)

            order.markDelivered(7, null, operator, deliveredAt).getOrNull()!!

            val delivered = assertIs<OrderDelivered>(order.getRecordedEvents().last())
            assertEquals(deliveredAt.plus(Duration.ofDays(7)), delivered.returnableUntil)
            assertEquals(OrderStatus.DELIVERED, order.status)
            assertEquals(deliveredAt, order.deliveredOn)

            val refused = approved().markDelivered(7, null, operator, deliveredAt).failureOrNull()
            assertEquals(Order.InvalidTransition(OrderStatus.PHOTO_SENT, OrderStatus.DELIVERED, setOf(OrderStatus.CANCELLED)), refused)
        }
    }

    @Nested
    inner class Cancelling {
        @Test
        fun `the customer cancels a PROCESSING order no photo was sent for, and a held one`() {
            listOf(paid(), held()).forEach { order ->
                order.cancel(CancelledBy.CUSTOMER, CancelReason.CHANGED_MIND, null, sara, at(30)).getOrNull()!!

                val cancelled = assertIs<OrderCancelled>(order.getRecordedEvents().last())
                assertEquals(CancelledBy.CUSTOMER, cancelled.by)
                assertEquals(OrderStatus.CANCELLED, order.status)
                assertEquals(at(30), order.cancellation?.cancelledOn)
            }
        }

        @Test
        fun `after one photo was sent the customer can't cancel - ORDER_NOT_CANCELLABLE - staff still can`() {
            val order = photoSent()

            val refused = order.cancel(CancelledBy.CUSTOMER, CancelReason.CHANGED_MIND, null, sara, at(70)).failureOrNull()

            assertEquals(Order.CancelFailure.NotCancellable(OrderStatus.PHOTO_SENT), refused)
            assertEquals("ORDER_NOT_CANCELLABLE", refused?.code)
            val note = StatusNote.constructFromPersisted("مشتری زنگ زد")
            assertTrue(order.cancel(CancelledBy.STAFF, null, note, operator, at(71)).isSuccess)
            assertEquals(note, order.cancellation?.note)
        }

        @Test
        fun `cancelling twice - the customer's second is ORDER_NOT_CANCELLABLE, staff's INVALID_STATUS_TRANSITION`() {
            val order = paid()
            order.cancel(CancelledBy.CUSTOMER, CancelReason.OTHER, null, sara, at(30)).getOrNull()!!

            assertIs<Order.CancelFailure.NotCancellable>(order.cancel(CancelledBy.CUSTOMER, CancelReason.OTHER, null, sara, at(31)).failureOrNull())
            assertIs<Order.CancelFailure.InvalidTransition>(order.cancel(CancelledBy.STAFF, null, null, operator, at(31)).failureOrNull())
            assertEquals(1, order.types().count { it == "OrderCancelled" })
        }

        @Test
        fun `staff can't cancel a shipped order - 422 with DELIVERED allowed - nor an unpaid one, and the customer neither`() {
            val refused = shipped().cancel(CancelledBy.STAFF, null, null, operator, at(130)).failureOrNull()
            assertIs<Order.CancelFailure.InvalidTransition>(refused)
            assertEquals(listOf("DELIVERED"), refused.meta["allowed"])

            assertIs<Order.CancelFailure.InvalidTransition>(placedOrder().cancel(CancelledBy.STAFF, null, null, operator, at(1)).failureOrNull())
            assertIs<Order.CancelFailure.NotCancellable>(placedOrder().cancel(CancelledBy.CUSTOMER, CancelReason.OTHER, null, sara, at(1)).failureOrNull())
        }
    }

    @Nested
    inner class Refunds {
        private val refund31 = RefundId.constructFromPersisted(31)

        @Test
        fun `a completed refund adds to refunded - the same refund again changes nothing`() {
            val order = paid()

            order.recordRefund(refund31, Money.constructFromPersisted(500_000), operator, at(30)).getOrNull()!!
            order.recordRefund(refund31, Money.constructFromPersisted(500_000), operator, at(31)).getOrNull()!!
            order.recordRefund(RefundId.constructFromPersisted(32), Money.constructFromPersisted(300_000), operator, at(32)).getOrNull()!!

            assertEquals(Money.constructFromPersisted(800_000), order.refunded)
            assertEquals(2, order.types().count { it == "OrderRefunded" })
        }

        @Test
        fun `more back than was paid is a bug and stops loudly`() {
            val order = paid()
            order.recordRefund(refund31, Money.constructFromPersisted(3_000_000), operator, at(30)).getOrNull()!!

            assertThrows<IllegalStateException> { order.recordRefund(RefundId.constructFromPersisted(32), Money.constructFromPersisted(480_001), operator, at(31)) }
        }
    }

    @Nested
    inner class Replay {
        @Test
        fun `placed, paid, photographed, approved, shipped, delivered - replaying the events gives exactly the same order`() {
            val order = shipped()
            order.markDelivered(7, null, operator, at(60 * 48)).getOrNull()!!

            val replayed = Order(order.getRecordedEvents())

            assertEquals(OrderStatus.DELIVERED, replayed.status)
            assertEquals(order.photosSent, replayed.photosSent)
            assertEquals(order.photoStatus, replayed.photoStatus)
            assertEquals(order.photoBatch, replayed.photoBatch)
            assertTrue(replayed.photoEverSent)
            assertEquals(order.feedbackDeadline, replayed.feedbackDeadline)
            assertEquals(order.shipment, replayed.shipment)
            assertEquals(order.deliveredOn, replayed.deliveredOn)
            assertEquals(order.returnableUntil, replayed.returnableUntil)
            assertEquals(order.version, replayed.version)
        }

        @Test
        fun `placed, paid, cancelled, refunded - replaying gives the cancellation and what went back`() {
            val order = paid()
            order.cancel(CancelledBy.CUSTOMER, CancelReason.FOUND_CHEAPER, StatusNote.constructFromPersisted("ارزون‌ترش رو پیدا کردم"), sara, at(30)).getOrNull()!!
            order.recordRefund(RefundId.constructFromPersisted(31), golden, operator, at(60 * 24)).getOrNull()!!

            val replayed = Order(order.getRecordedEvents())

            assertEquals(OrderStatus.CANCELLED, replayed.status)
            assertEquals(order.cancellation, replayed.cancellation)
            assertEquals(golden, replayed.refunded)
            val again = replayed.recordRefund(RefundId.constructFromPersisted(31), golden, operator, at(60 * 25))
            assertTrue(again.isSuccess)
            assertEquals(emptyList(), replayed.getRecordedEvents())
        }
    }

    @Test
    fun `the order's version counts every event after the payment too`() {
        val order = shipped()

        assertEquals(order.getRecordedEvents().size.toLong(), order.version)
        assertEquals(listOf("OrderPlaced", "OrderPaid", "OrderPhotosAttached", "OrderPhotosSent", "OrderPhotosApproved", "OrderShipped"), order.types())
    }
}

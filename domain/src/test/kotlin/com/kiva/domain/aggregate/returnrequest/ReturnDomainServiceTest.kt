package com.kiva.domain.aggregate.returnrequest

import com.kiva.common.railway.FieldFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.MediaFixtures
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.placedOrder
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.entity.event.OrderReturnRequested
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.item
import com.kiva.domain.aggregate.returnrequest.ReturnFixtures.text
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.returnrequest.repository.ReturnIdGenerator
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Asking for a return of Sara's golden order (roadmap section 12.2, decisions 3, 5 and 6), delivered and returnable for 7 days. */
class ReturnDomainServiceTest {

    private val operator = UserId.constructFromPersisted(900)
    private val deliveredAt = at(60 * 48)
    private val now = deliveredAt.plusSeconds(3_600)

    private class Ids : ReturnIdGenerator {
        var handedOut = 0
            private set

        override fun nextReturnId(): ReturnId = ReturnId.constructFromPersisted(12L + handedOut++)
    }

    private val ids = Ids()
    private val service = ReturnDomainService(ids)

    private val evidence = MediaFixtures.photo(MediaFixtures.id(1), MediaPurpose.RETURN_EVIDENCE, uploadedBy = sara)

    private fun delivered(): Order = placedOrder().also {
        it.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), Money.constructFromPersisted(3_480_000), false, sara, at(5)).getOrNull()!!
        it.attachPhotos(listOf(OrderPhoto(MediaFixtures.id(8), 1, MediaView.FRONT, 0), OrderPhoto(MediaFixtures.id(9), 2, MediaView.FRONT, 1)), operator, at(50)).getOrNull()!!
        it.sendPhotos(MessengerChannel.TELEGRAM, null, 24, operator, at(60)).getOrNull()!!
        it.approvePhotos(automatic = false, sara, at(90)).getOrNull()!!
        it.ship(ShippingMethodCode.POST, TrackingCode.constructFromPersisted("TPX4821760"), at(120), true, operator, at(120)).getOrNull()!!
        it.markDelivered(7, null, operator, deliveredAt).getOrNull()!!
    }

    private fun open(
        order: Order = delivered(),
        items: List<ReturnItem> = listOf(item(1)),
        reason: ReturnReason = ReturnReason.NOT_AS_PICTURED,
        description: ReturnText? = null,
        mediaIds: List<MediaId> = listOf(evidence.id),
        found: List<MediaAsset> = listOf(evidence),
        at: Instant = now,
    ) = service.open(order, sara, items, reason, description, mediaIds, found, RefundMethod.BANK_TRANSFER, at)

    private fun problemsOf(failure: Any?): List<String> =
        assertIs<ReturnDomainService.OpenFailure.InvalidInput>(failure).causes.map { "${(it as FieldFailure).name} ${it.code}" }

    @Test
    fun `the shop's fault with a photo of Sara's own - REQUESTED, the shop pays, the order RETURN_REQUESTED with it open`() {
        val order = delivered()

        val ret = open(order).getOrNull()!!

        assertEquals(listOf(ReturnStatus.REQUESTED, "RT-1012", ReturnShippingPayer.KIVA), listOf(ret.status, ret.code.value, ret.shippingPaidBy))
        assertEquals(listOf(evidence.id), ret.mediaIds)
        assertEquals(RefundMethod.BANK_TRANSFER, ret.refundMethod)
        assertEquals(OrderStatus.RETURN_REQUESTED, order.status)
        assertEquals(ret.id, assertIs<OrderReturnRequested>(order.getRecordedEvents().last()).returnId)
    }

    @Test
    fun `changed mind without a photo is fine and the customer pays - other without a description is DESCRIPTION_REQUIRED`() {
        assertEquals(ReturnShippingPayer.CUSTOMER, open(reason = ReturnReason.CHANGED_MIND, mediaIds = emptyList(), found = emptyList()).getOrNull()?.shippingPaidBy)

        assertEquals(listOf("description DESCRIPTION_REQUIRED"), problemsOf(open(reason = ReturnReason.OTHER, mediaIds = emptyList(), found = emptyList()).failureOrNull()))
        assertEquals(ReturnStatus.REQUESTED, open(reason = ReturnReason.OTHER, description = text("بند کوتاهه"), mediaIds = emptyList(), found = emptyList()).getOrNull()?.status)
    }

    @Test
    fun `each of the shop's four faults without a photo is EVIDENCE_REQUIRED on mediaIds`() {
        ReturnReason.entries.filter { it.isKivaFault }.forEach { reason ->
            assertEquals(listOf("mediaIds EVIDENCE_REQUIRED"), problemsOf(open(reason = reason, mediaIds = emptyList(), found = emptyList()).failureOrNull()), "$reason")
        }
    }

    @Test
    fun `someone else's upload, another purpose's, or one not there are all the same MEDIA_NOT_FOUND on their own field`() {
        val someoneElses = MediaFixtures.photo(MediaFixtures.id(2), MediaPurpose.RETURN_EVIDENCE, uploadedBy = UserId.constructFromPersisted(8))
        val aProductPhoto = MediaFixtures.photo(MediaFixtures.id(3), MediaPurpose.PRODUCT, uploadedBy = sara)
        val missing = MediaFixtures.id(4)

        val failure = open(mediaIds = listOf(evidence.id, someoneElses.id, aProductPhoto.id, missing), found = listOf(evidence, someoneElses, aProductPhoto)).failureOrNull()

        assertEquals(listOf("mediaIds.1 MEDIA_NOT_FOUND", "mediaIds.2 MEDIA_NOT_FOUND", "mediaIds.3 MEDIA_NOT_FOUND"), problemsOf(failure))
    }

    @Test
    fun `in the contract's order - the media first, alone - then the lines and the description together - no id handed out, the order untouched`() {
        val order = delivered()

        val mediaFirst = open(order, items = listOf(item(2)), reason = ReturnReason.OTHER, mediaIds = listOf(MediaFixtures.id(4)), found = emptyList()).failureOrNull()
        val thenTheRest = open(order, items = listOf(item(2)), reason = ReturnReason.OTHER, mediaIds = emptyList(), found = emptyList()).failureOrNull()

        assertEquals(listOf("mediaIds.0 MEDIA_NOT_FOUND"), problemsOf(mediaFirst))
        assertEquals(listOf("items.0.orderItemId ITEM_NOT_RETURNABLE", "description DESCRIPTION_REQUIRED"), problemsOf(thenTheRest))
        assertEquals(0, ids.handedOut)
        assertEquals(OrderStatus.DELIVERED, order.status)
    }

    @Test
    fun `someone else's photo on an order that may not be returned is still MEDIA_NOT_FOUND - the media come before the order`() {
        val order = delivered()
        open(order).getOrNull()!!

        val failure = open(order, mediaIds = listOf(MediaFixtures.id(4)), found = emptyList()).failureOrNull()

        assertEquals(listOf("mediaIds.0 MEDIA_NOT_FOUND"), problemsOf(failure))
    }

    @Test
    fun `an order that may not be returned is 422 before its lines and evidence - a second return ORDER_NOT_RETURNABLE, a late one RETURN_WINDOW_EXPIRED`() {
        val order = delivered()
        open(order).getOrNull()!!

        val second = open(order, items = listOf(item(2)), mediaIds = emptyList(), found = emptyList()).failureOrNull()
        val late = open(at = deliveredAt.plusSeconds(8 * 86_400)).failureOrNull()

        assertIs<ReturnDomainService.OpenFailure.NotReturnable>(second)
        assertEquals("ORDER_NOT_RETURNABLE", second.code)
        assertIs<ReturnDomainService.OpenFailure.WindowExpired>(late)
        assertEquals(mapOf("returnableUntil" to deliveredAt.plusSeconds(7 * 86_400).toString()), late.meta)
    }

    @Test
    fun `another customer's order is a bug - the use case finds orders by their owner`() {
        assertThrows<IllegalArgumentException> {
            service.open(delivered(), UserId.constructFromPersisted(8), listOf(item(1)), ReturnReason.CHANGED_MIND, null, emptyList(), emptyList(), RefundMethod.ORIGINAL_PAYMENT, now)
        }
    }

    @Test
    fun `the return made is the one the order holds open`() {
        val order = delivered()

        val ret: ReturnRequest = open(order).getOrNull()!!

        assertEquals(ret.id, order.openReturnId)
        assertEquals(listOf(order.id, order.code, sara), listOf(ret.orderId, ret.orderCode, ret.userId))
    }
}

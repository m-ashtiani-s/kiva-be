package com.kiva.domain.aggregate.returnrequest

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReceiptLine
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnPickup
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * Ready-made returns: Sara sends back one caramel Aida (line 1) of her golden order KV-218340, because it isn't as
 * pictured, on Monday 2026-10-19; the operator (900) deals with it. [inStatus] takes it to any status the usual way.
 */
object ReturnFixtures {

    val now: Instant = Instant.parse("2026-10-19T08:00:00Z")
    val sara: UserId = UserId.constructFromPersisted(7)
    val operator: UserId = UserId.constructFromPersisted(900)
    val returnId: ReturnId = ReturnId.constructFromPersisted(12)
    val refundId: RefundId = RefundId.constructFromPersisted(31)

    fun media(n: Int): MediaId = MediaId.constructFromPersisted("med_" + n.toString().padStart(22, '0'))

    fun text(value: String): ReturnText = ReturnText.constructFromPersisted(value)

    fun item(lineId: Int, quantity: Int = 1): ReturnItem = ReturnItem.constructFromPersisted(lineId, quantity)

    fun received(lineId: Int, received: Int, restock: Int): ReceiptLine = ReceiptLine.constructFromPersisted(lineId, received, restock)

    val pickup: ReturnPickup = ReturnPickup.constructFromPersisted(ShippingMethodCode.TIPAX, now.plusSeconds(86_400), TrackingCode.constructFromPersisted("TPX4821760"), null)

    fun requested(reason: ReturnReason = ReturnReason.NOT_AS_PICTURED, items: List<ReturnItem> = listOf(item(1))): ReturnRequest = ReturnRequest.request(
        returnId, OrderId.constructFromPersisted(42), OrderCode.constructFromPersisted("KV-218340"), sara, reason, text("رنگش با عکس فرق داشت"),
        items, listOf(media(1)), RefundMethod.ORIGINAL_PAYMENT, now,
    )

    /** The usual way to [status]: approved, collected, received (one back, on the shelf), refunded, turned down or closed. */
    fun inStatus(status: ReturnStatus): ReturnRequest = requested().also {
        when (status) {
            ReturnStatus.REQUESTED -> {}
            ReturnStatus.APPROVED -> it.approve(null, null, operator, now).getOrNull()!!
            ReturnStatus.REJECTED -> it.reject(text("عکس‌ها مشکلی نشون نمی‌دن"), operator, now).getOrNull()!!
            ReturnStatus.PICKUP_SCHEDULED -> inStatusOn(it, ReturnStatus.APPROVED).schedulePickup(pickup).getOrNull()!!
            ReturnStatus.RECEIVED -> inStatusOn(it, ReturnStatus.APPROVED).receive(listOf(received(1, 1, 1)), null, operator, now).getOrNull()!!
            ReturnStatus.REFUNDED -> inStatusOn(it, ReturnStatus.RECEIVED).markRefunded(refundId, now).getOrNull()!!
            ReturnStatus.CLOSED -> inStatusOn(it, ReturnStatus.APPROVED).close(text("کالا نرسید"), hasOpenRefund = false, now).getOrNull()!!
        }
    }

    private fun inStatusOn(ret: ReturnRequest, status: ReturnStatus): ReturnRequest = ret.also {
        when (status) {
            ReturnStatus.APPROVED -> it.approve(null, null, operator, now).getOrNull()!!
            ReturnStatus.RECEIVED -> inStatusOn(it, ReturnStatus.APPROVED).receive(listOf(received(1, 1, 1)), null, operator, now).getOrNull()!!
            else -> error("not a step on the way: $status")
        }
    }
}

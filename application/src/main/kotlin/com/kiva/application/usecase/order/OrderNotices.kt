package com.kiva.application.usecase.order

import com.kiva.application.port.SmsSender
import com.kiva.common.logging.getLogger
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import kotlin.coroutines.cancellation.CancellationException

/**
 * The SMS an order's customer gets as staff work on it (roadmap section 7.9), one place for every use case that sends
 * one (Rule 15). Always after the commit, never inside a transaction; a failure is only a WARN: the work is done all the
 * same (the photos were sent by hand, the parcel left).
 *
 * The phone is the buyer's account's, read by the caller inside its transaction; none (an account removed) sends nothing.
 */
class OrderNotices(private val smsSender: SmsSender) {

    private val logger = getLogger()

    /** «عکس کیفت فرستاده شد». */
    suspend fun photosSent(to: IranianMobileNumber?, orderCode: OrderCode) = send("«عکس کیفت فرستاده شد»", to, orderCode) { smsSender.sendPhotosSent(it, orderCode) }

    /** «پولت برگشت». */
    suspend fun refundCompleted(to: IranianMobileNumber?, orderCode: OrderCode) = send("«پولت برگشت»", to, orderCode) { smsSender.sendRefundCompleted(it, orderCode) }

    /** «لغو شد». */
    suspend fun cancelled(to: IranianMobileNumber?, orderCode: OrderCode) = send("«لغو شد»", to, orderCode) { smsSender.sendOrderCancelled(it, orderCode) }

    /** «ارسال شد», with the carrier's name and the tracking code. */
    suspend fun shipped(to: IranianMobileNumber?, orderCode: OrderCode, carrierName: String, trackingCode: String) =
        send("«ارسال شد»", to, orderCode) { smsSender.sendOrderShipped(it, orderCode, carrierName, trackingCode) }

    /** «تحویل شد». */
    suspend fun delivered(to: IranianMobileNumber?, orderCode: OrderCode) = send("«تحویل شد»", to, orderCode) { smsSender.sendOrderDelivered(it, orderCode) }

    /** «درخواستت بررسی شد»: staff resolved the customer's change request; staff's note is on the order's page, never in the SMS. */
    suspend fun changeResolved(to: IranianMobileNumber?, orderCode: OrderCode) =
        send("«درخواستت بررسی شد»", to, orderCode) { smsSender.sendChangeResolved(it, orderCode) }

    private suspend fun send(what: String, to: IranianMobileNumber?, orderCode: OrderCode, sending: suspend (IranianMobileNumber) -> Unit) {
        if (to == null) {
            logger.warn("OrderNotices: $what of order ${orderCode.value} not sent, its account is gone")
            return
        }
        try {
            sending(to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("OrderNotices: $what of order ${orderCode.value} to ${to.masked()} failed; the order is changed all the same", e)
        }
    }
}

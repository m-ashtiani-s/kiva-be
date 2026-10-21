package com.kiva.application.port

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnCode

/**
 * Sends SMS through the shop's SMS provider. The domain doesn't know which provider; development writes to the log.
 */
interface SmsSender {

    /**
     * Sends [code] to [to]. [purpose] picks the provider's approved template (Iranian providers send codes only
     * through templates approved in advance).
     *
     * Throws when the provider refuses or can't be reached. It is called after the code is committed, never inside a
     * transaction: a slow provider must not hold a database connection.
     */
    suspend fun sendCode(to: IranianMobileNumber, code: OtpCode, purpose: OtpPurpose)

    /**
     * Tells [oldPhone] that its account moved to another number, so a change its owner didn't make is noticed at once.
     * Only the end of the new number is in the text ([newPhoneEnding], e.g. `4567`). Throws like [sendCode].
     */
    suspend fun sendPhoneChangedNotice(oldPhone: IranianMobileNumber, newPhoneEnding: String)

    /**
     * «پرداخت موفق»: tells [to] that the order [orderCode] is paid, with the link to follow it (contract section 5).
     * Sent after the payment is committed; when it fails, only the log knows: the order is paid all the same. Throws
     * like [sendCode].
     */
    suspend fun sendOrderPaid(to: IranianMobileNumber, orderCode: OrderCode)

    /**
     * «عکس کیفت فرستاده شد»: the photos of the packed bag were sent to [to] on their messenger (by the operator, by hand),
     * with the link to the order's page. Sent after the photos are recorded as sent; when it fails, only the log knows.
     * Throws like [sendCode].
     */
    suspend fun sendPhotosSent(to: IranianMobileNumber, orderCode: OrderCode)

    /**
     * «پولت برگشت»: a refund of the order [orderCode] was completed, the money sent back to the customer. Sent after the
     * refund is recorded as completed; when it fails, only the log knows. Throws like [sendCode].
     */
    suspend fun sendRefundCompleted(to: IranianMobileNumber, orderCode: OrderCode)

    /**
     * «لغو شد»: the order [orderCode] was cancelled (by the customer, or by staff when they chose to tell them). Sent after
     * the cancellation is committed; when it fails, only the log knows. Throws like [sendCode].
     */
    suspend fun sendOrderCancelled(to: IranianMobileNumber, orderCode: OrderCode)

    /**
     * «ارسال شد»: the order [orderCode] left with [carrierName] under [trackingCode]. Sent after the shipment is committed
     * (one for a whole reservation group), never for a correction; when it fails, only the log knows. Throws like [sendCode].
     */
    suspend fun sendOrderShipped(to: IranianMobileNumber, orderCode: OrderCode, carrierName: String, trackingCode: String)

    /**
     * «تحویل شد»: staff recorded the order [orderCode] as delivered and chose to tell the customer. Sent after the commit;
     * when it fails, only the log knows. Throws like [sendCode].
     */
    suspend fun sendOrderDelivered(to: IranianMobileNumber, orderCode: OrderCode)

    /**
     * «درخواستت بررسی شد»: staff resolved the customer's change request of the order [orderCode] (carried it out or turned
     * it down) and chose to tell them; the details, staff's note included, are on the order's page, never in the SMS (no
     * free text in an SMS, roadmap section 1, row 14). Sent after the commit; when it fails, only the log knows. Throws
     * like [sendCode].
     */
    suspend fun sendChangeResolved(to: IranianMobileNumber, orderCode: OrderCode)

    /**
     * «مرجوعی تأیید شد»: staff approved the return [returnCode]; how to send it back is on its page. Sent after the commit;
     * when it fails, only the log knows. Throws like [sendCode].
     */
    suspend fun sendReturnApproved(to: IranianMobileNumber, returnCode: ReturnCode)

    /**
     * «مرجوعی رد شد»: staff turned the return [returnCode] down; why is on its page, never in the SMS (no free text in an
     * SMS, roadmap section 1, row 14). Sent after the commit; when it fails, only the log knows. Throws like [sendCode].
     */
    suspend fun sendReturnRejected(to: IranianMobileNumber, returnCode: ReturnCode)

    /**
     * «جمع‌آوری مرجوعی»: [carrierName] will collect the parcel of the return [returnCode]; when is on its page. Sent after
     * the commit, only when staff chose to tell the customer; when it fails, only the log knows. Throws like [sendCode].
     */
    suspend fun sendReturnPickup(to: IranianMobileNumber, returnCode: ReturnCode, carrierName: String)

    /**
     * «مرجوعی بسته شد»: staff closed the return [returnCode] without money back; why is on its page, never in the SMS.
     * Sent after the commit, only when staff chose to tell the customer; when it fails, only the log knows. Throws like
     * [sendCode].
     */
    suspend fun sendReturnClosed(to: IranianMobileNumber, returnCode: ReturnCode)
}

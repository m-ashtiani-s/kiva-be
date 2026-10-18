package com.kiva.application.port

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose

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
}

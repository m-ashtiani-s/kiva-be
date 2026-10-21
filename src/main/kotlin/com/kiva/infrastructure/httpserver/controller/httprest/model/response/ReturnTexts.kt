package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus

/**
 * The Persian words of a return (roadmap section 12.6): its status, and how to send it back when staff wrote nothing of
 * their own. The order's timeline labels of a return are OrderTexts' (decision 36).
 */
object ReturnTexts {

    fun statusLabel(status: ReturnStatus): String = when (status) {
        ReturnStatus.REQUESTED -> "در حال بررسی"
        ReturnStatus.APPROVED -> "تأیید شد؛ منتظر ارسال کالا"
        ReturnStatus.REJECTED -> "رد شد"
        ReturnStatus.PICKUP_SCHEDULED -> "جمع‌آوری زمان‌بندی شد"
        ReturnStatus.RECEIVED -> "به انبار رسید"
        ReturnStatus.REFUNDED -> "وجه برگشت داده شد"
        ReturnStatus.CLOSED -> "بسته شد"
    }

    /** How to send it back when staff wrote nothing of their own: the tracking code goes to support (decision 10). */
    fun defaultInstructions(payer: ReturnShippingPayer): String = when (payer) {
        ReturnShippingPayer.KIVA ->
            "بسته رو با تیپاکس یا پست به آدرس انبار کیوا بفرست؛ هزینه‌ی ارسال با کیواست. کد رهگیری رو برای پشتیبانی بفرست."
        ReturnShippingPayer.CUSTOMER ->
            "بسته رو با تیپاکس یا پست به آدرس انبار کیوا بفرست؛ هزینه‌ی ارسال برگشت با خودته. کد رهگیری رو برای پشتیبانی بفرست."
    }
}

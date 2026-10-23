package com.kiva.application.usecase.contact

import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode

/**
 * The shop's emails (roadmap phase 13, decision 11): plain text in Persian, signed by Kiva. Here and not in the HTTP
 * layer, because the use cases that send them write them; no HTML.
 */
object EmailTexts {
    private const val SIGNATURE = "پشتیبانی کیوا\nkivashop.ir"

    /** The subject of a reply to the contact message [code]. */
    fun contactReplySubject(code: TicketCode): String = "پاسخ پیامت به کیوا (${code.value})"

    /** A reply to [fullName]'s contact message [code]: the whole [reply], as the operator wrote it. */
    fun contactReplyBody(fullName: String, code: TicketCode, reply: String): String =
        "سلام $fullName،\n\n$reply\n\nکد پیگیری پیامت: ${code.value}\nاگه سؤال دیگه‌ای داری، همین‌جا جواب نده؛ از فرم «تماس با ما» تو سایت بنویس.\n\n$SIGNATURE"
}

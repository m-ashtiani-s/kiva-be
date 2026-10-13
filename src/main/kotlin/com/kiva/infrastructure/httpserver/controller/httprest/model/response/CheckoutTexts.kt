package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel

/** The Persian texts of the checkout page, made here because the domain only gives codes. */
object CheckoutTexts {

    /**
     * «عکس کیفت رو قبل از ارسال کجا بفرستیم؟»: every messenger, in the contract's order. Their names and hints are fixed
     * here, since the shop's settings don't have them (roadmap section 5.2); a new channel won't compile without its own.
     * Only Telegram is found by a handle too, so only its hint says so (`username` of the order's messenger).
     */
    val messengers: List<MessengerOptionHttpResponse> = MessengerChannel.entries.map { channel ->
        val (name, hint) = when (channel) {
            MessengerChannel.RUBIKA -> "روبیکا" to "با شماره موبایل"
            MessengerChannel.TELEGRAM -> "تلگرام" to "با آیدی یا شماره"
            MessengerChannel.BALE -> "بله" to "با شماره موبایل"
        }
        MessengerOptionHttpResponse(channel = channel.name, name = name, hint = hint, iconUrl = null, enabled = true)
    }
}

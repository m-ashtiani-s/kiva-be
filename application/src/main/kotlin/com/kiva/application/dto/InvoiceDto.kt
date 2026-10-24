package com.kiva.application.dto

import java.time.Instant

/**
 * What a purchase receipt says (`GET /me/orders/{orderCode}/invoice`, roadmap phase 14, decision 14): the order as its
 * page shows it (its current lines after a change request, its totals now), when it was paid, and who sells. Values
 * only: the Persian words, digits and layout are the renderer's.
 *
 * @property paidOn when the order was paid (the receipt's date).
 * @property cancelled the order was cancelled after it was paid: the receipt says so across its top.
 */
data class InvoiceDto(val order: OrderDetailDto, val seller: Seller, val paidOn: Instant, val cancelled: Boolean) {
    /**
     * The shop as the receipt names it: the brand's name, the support phone and email of the settings, and the site.
     * No legal name or address yet (the roadmap's open question 12).
     */
    data class Seller(val name: String, val phone: String?, val email: String?, val website: String)

    // An order's page holds personal data.
    override fun toString(): String = "InvoiceDto(order=${order.summary.code})"
}

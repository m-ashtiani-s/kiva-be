package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.ProductInquiryDto
import com.kiva.domain.aggregate.settings.model.valueobject.SocialLink
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Contract schema `ProductInquiry` (roadmap section 15.2, decision 18): the text a customer sends about a product and
 * color, and each messenger's link with it already in. Built here, not in the client, so a new handle or a new text
 * needs no release of the shop.
 */
@Serializable
data class ProductInquiryHttpResponse(
    val productCode: String,
    val productName: String,
    val colorName: String,
    val prefilledText: String,
    val channels: List<MessengerLinkHttpResponse>,
) {
    companion object {
        fun from(inquiry: ProductInquiryDto): ProductInquiryHttpResponse {
            val text = ProductTexts.inquiryText(inquiry.productName, inquiry.colorName, inquiry.productCode)
            val hours = ContentTexts.responseHours(inquiry.supportHours)
            return ProductInquiryHttpResponse(
                productCode = inquiry.productCode,
                productName = inquiry.productName,
                colorName = inquiry.colorName,
                prefilledText = text,
                channels = inquiry.channels.map { MessengerLinkHttpResponse.from(it, text, hours) },
            )
        }

        /** [url] with `text=` and [text] in UTF-8, after `?` or, when [url] already has a query, after `&`; a space is `%20`. */
        fun withText(url: String, text: String): String =
            url + (if ('?' in url) "&" else "?") + "text=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
    }
}

/** Contract schema `MessengerLink`: every field always there; no handle or no hours is null. */
@Serializable
data class MessengerLinkHttpResponse(val channel: String, val name: String, val url: String, val handle: String?, val responseHours: String?) {
    companion object {
        fun from(link: SocialLink, text: String, responseHours: String?) =
            MessengerLinkHttpResponse(link.channel.name, link.name, ProductInquiryHttpResponse.withText(link.url, text), link.handle, responseHours)
    }
}

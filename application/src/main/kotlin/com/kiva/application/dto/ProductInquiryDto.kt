package com.kiva.application.dto

import com.kiva.domain.aggregate.settings.model.valueobject.SocialLink
import com.kiva.domain.aggregate.settings.model.valueobject.SupportHours

/**
 * What «سؤال داری؟» of a product page is made of (contract ProductInquiry, before its Persian text and links): the
 * product and the color asked about, the shop's messengers that take a prefilled text (in the settings' order), and its
 * support hours.
 */
data class ProductInquiryDto(
    val productCode: String,
    val productName: String,
    val colorName: String,
    val channels: List<SocialLink>,
    val supportHours: List<SupportHours>,
)

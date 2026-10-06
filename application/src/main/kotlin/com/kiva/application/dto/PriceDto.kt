package com.kiva.application.dto

import com.kiva.domain.aggregate.product.model.policy.PricePolicy
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag

/** A price tag as the shop shows it (the contract's PriceInfo, in toman), its discount worked out by PricePolicy. */
data class PriceDto(
    val price: Long,
    val compareAtPrice: Long?,
    val discountPercent: Int,
    val discountAmount: Long,
) {
    companion object {
        fun from(tag: PriceTag): PriceDto = PriceDto(
            price = tag.price.toman,
            compareAtPrice = tag.compareAtPrice?.toman,
            discountPercent = PricePolicy.discountPercent(tag),
            discountAmount = PricePolicy.discountAmount(tag).toman,
        )
    }
}

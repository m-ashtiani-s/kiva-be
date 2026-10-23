package com.kiva.application.dto

import com.kiva.domain.aggregate.campaign.entity.Campaign
import java.time.Instant

/**
 * A campaign as the shop and the panel show it (contract Campaign and AdminCampaign): the campaign itself, its products
 * as shop cards (CampaignShowcasePolicy's rule, whether it runs now or not), and the server's clock for the countdown.
 */
data class CampaignDto(val campaign: Campaign, val products: List<ProductCardDto>, val serverTime: Instant) {
    override fun toString(): String = "CampaignDto(id=${campaign.id.value}, products=${products.size})"
}

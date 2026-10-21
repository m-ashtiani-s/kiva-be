package com.kiva.application.dto

import com.kiva.application.port.PaymentGateways
import com.kiva.application.repository.SiteContentQm
import com.kiva.domain.aggregate.settings.entity.ShopSettings

/**
 * What `GET /config` is made of (roadmap phase 5, decisions 1 and 2): the site's own texts, the shop's settings, the
 * gateways as checkout shows them, the announcements showing now, and what the server works out. The HTTP layer puts
 * them into the contract's SiteConfig, fills the texts' settings and writes the numbers in Persian.
 *
 * @property supportOpenNow someone answers now, by Tehran's clock.
 * @property otpLength and [resendCooldownSeconds]: the login code's (OtpPolicy).
 */
data class SiteConfigDto(
    val content: SiteContentQm,
    val settings: ShopSettings,
    val gateways: List<PaymentGateways.CheckoutOption>,
    val announcements: List<AnnouncementDto>,
    val supportOpenNow: Boolean,
    val otpLength: Int,
    val resendCooldownSeconds: Long,
)

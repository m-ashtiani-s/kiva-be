package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.SiteConfigDto
import com.kiva.application.repository.SiteContentQm
import kotlinx.serialization.Serializable

/**
 * Contract schema `SiteConfig` (`GET /config`; roadmap phase 5, step 5.4): every part always there. Texts have the
 * settings they name filled in (ContentTexts.fill); a brand path of the site gets the site's address.
 */
@Serializable
data class SiteConfigHttpResponse(
    val brand: BrandHttpResponse,
    val currency: CurrencyHttpResponse,
    val announcements: List<AnnouncementHttpResponse>,
    val shipping: ShippingConfigHttpResponse,
    val reservation: ReservationConfigHttpResponse,
    val returns: ReturnsConfigHttpResponse,
    val preShipmentPhoto: PreShipmentPhotoConfigHttpResponse,
    val inventory: InventoryConfigHttpResponse,
    val auth: AuthConfigHttpResponse,
    val support: SupportConfigHttpResponse,
    val social: List<SocialLinkHttpResponse>,
    val paymentGateways: List<PaymentGatewayHttpResponse>,
    val productPerks: List<PerkHttpResponse>,
    val trustBadges: List<TrustBadgeHttpResponse>,
    val footerLinks: List<FooterLinkGroupHttpResponse>,
    val features: FeaturesHttpResponse,
) {
    companion object {
        /** @param siteUrl the shop's address (`https://kiva.ir`), for the brand's own files. */
        fun from(config: SiteConfigDto, siteUrl: String): SiteConfigHttpResponse {
            val content = config.content
            val settings = config.settings
            fun fill(text: String) = ContentTexts.fill(text, settings)
            fun absolute(path: String) = if (path.startsWith("/")) siteUrl.trimEnd('/') + path else path
            return SiteConfigHttpResponse(
                brand = content.brand.let {
                    BrandHttpResponse(it.name, it.slogan, it.about, absolute(it.logoUrl), absolute(it.logoWhiteUrl), absolute(it.faviconUrl))
                },
                currency = CurrencyHttpResponse(CURRENCY, content.currencyLabel),
                announcements = AnnouncementHttpResponse.listOf(config.announcements),
                shipping = ShippingConfigHttpResponse(
                    methods = settings.shippingMethods.filter { it.isActive }.map(ShippingMethodSettingHttpResponse::from),
                    freeShippingThreshold = settings.freeShippingThreshold.toman,
                    freeShippingMethods = settings.freeShippingMethods.sorted().map { it.name },
                    nonShippingWeekdays = settings.nonShippingWeekdays.sortedBy(::iranianWeekOrder).map { it.name },
                ),
                reservation = ReservationConfigHttpResponse(settings.reservationEnabled, settings.reservationHoldDays, fill(content.reservationDescription)),
                returns = ReturnsConfigHttpResponse(settings.returnWindowDays, content.returnsPolicyUrl),
                preShipmentPhoto = PreShipmentPhotoConfigHttpResponse(
                    enabled = true,
                    required = true,
                    channels = CheckoutTexts.messengers,
                    title = fill(content.preShipmentPhoto.title),
                    description = fill(content.preShipmentPhoto.description),
                ),
                inventory = InventoryConfigHttpResponse(settings.lowStockThreshold),
                auth = AuthConfigHttpResponse(config.otpLength, config.resendCooldownSeconds),
                support = SupportConfigHttpResponse(
                    phone = settings.supportPhone?.value,
                    phoneDisplay = settings.supportPhone?.value?.let(ContentTexts::phoneDisplay),
                    email = settings.supportEmail?.value,
                    emailResponseHint = fill(content.support.emailResponseHint),
                    responseTimeHint = fill(content.support.responseTimeHint),
                    hours = settings.supportHours.map(SupportHoursHttpResponse::from),
                    isOpenNow = config.supportOpenNow,
                ),
                social = settings.social.map(SocialLinkHttpResponse::from),
                paymentGateways = config.gateways.map(PaymentGatewayHttpResponse::from),
                productPerks = content.productPerks.map { PerkHttpResponse(it.icon, fill(it.title), fill(it.subtitle)) },
                trustBadges = content.trustBadges.map { TrustBadgeHttpResponse(it.type, it.label, it.imageUrl, it.linkUrl) },
                footerLinks = content.footerLinks.map { group -> FooterLinkGroupHttpResponse(fill(group.group), group.links.map { link(it, ::fill) }) },
                features = FeaturesHttpResponse.from(settings.features),
            )
        }

        private fun link(link: SiteContentQm.Link, fill: (String) -> String) = FooterLinkHttpResponse(fill(link.label), link.url)

        /** The contract's Currency: toman, the only one. */
        private const val CURRENCY = "IRT"
    }
}

@Serializable
data class BrandHttpResponse(val name: String, val slogan: String, val about: String, val logoUrl: String, val logoWhiteUrl: String, val faviconUrl: String)

@Serializable
data class CurrencyHttpResponse(val code: String, val label: String)

/** Only the active methods, in the settings' order (decision 4). */
@Serializable
data class ShippingConfigHttpResponse(
    val methods: List<ShippingMethodSettingHttpResponse>,
    val freeShippingThreshold: Long,
    val freeShippingMethods: List<String>,
    val nonShippingWeekdays: List<String>,
)

@Serializable
data class ReservationConfigHttpResponse(val enabled: Boolean, val holdDays: Int, val description: String)

@Serializable
data class ReturnsConfigHttpResponse(val windowDays: Int, val policyUrl: String)

/** [enabled] and [required] always: the photo before shipping is a step of every order (decision 14). */
@Serializable
data class PreShipmentPhotoConfigHttpResponse(
    val enabled: Boolean,
    val required: Boolean,
    val channels: List<MessengerOptionHttpResponse>,
    val title: String,
    val description: String,
)

@Serializable
data class InventoryConfigHttpResponse(val lowStockThreshold: Int)

@Serializable
data class AuthConfigHttpResponse(val otpLength: Int, val resendCooldownSeconds: Long)

/** [isOpenNow] may be up to 5 minutes old: the answer is cached (decision 3). */
@Serializable
data class SupportConfigHttpResponse(
    val phone: String?,
    val phoneDisplay: String?,
    val email: String?,
    val emailResponseHint: String,
    val responseTimeHint: String,
    val hours: List<SupportHoursHttpResponse>,
    val isOpenNow: Boolean,
)

@Serializable
data class TrustBadgeHttpResponse(val type: String, val label: String, val imageUrl: String, val linkUrl: String)

@Serializable
data class FooterLinkGroupHttpResponse(val group: String, val links: List<FooterLinkHttpResponse>)

@Serializable
data class FooterLinkHttpResponse(val label: String, val url: String)

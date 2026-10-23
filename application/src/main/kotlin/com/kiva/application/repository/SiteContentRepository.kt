package com.kiva.application.repository

/**
 * The texts and links of `GET /config` that are neither settings nor computed (roadmap phase 5, decision 1): one row,
 * changed with SQL until the panel has a form for it. Runs inside the use case's transaction.
 */
interface SiteContentRepository {
    fun load(): SiteContentQm
}

/**
 * The site's own texts and links, as stored. A text may name a setting as `{holdDays}`, `{returnWindowDays}` or
 * `{freeShippingThreshold}`, filled when shown (decision 23); a brand path starting with `/` is the site's own file.
 */
data class SiteContentQm(
    val brand: Brand,
    val currencyLabel: String,
    val reservationDescription: String,
    val returnsPolicyUrl: String,
    val preShipmentPhoto: PhotoTexts,
    val support: SupportTexts,
    val productPerks: List<Perk>,
    val trustBadges: List<TrustBadge>,
    val footerLinks: List<FooterGroup>,
    val home: Home,
    /** The search panel's suggested queries, typed by hand (roadmap phase 12, decision 13). */
    val popularQueries: List<String>,
) {
    /**
     * The home page's fixed sections (roadmap phase 11, decision 15).
     *
     * @property showcaseProductId the product whose colors turn in the hero, or null for the newest in the shop.
     * @property showcaseMessenger PhotoMessengerChannel's name (`TELEGRAM`).
     */
    data class Home(val hero: Hero, val features: List<Perk>, val howItWorks: List<Step>, val showcaseProductId: Long?, val showcaseMessenger: String)

    data class Hero(val eyebrow: String, val title: String, val text: String, val primaryCta: Link, val secondaryCta: Link)

    data class Step(val step: Int, val title: String, val text: String, val isSignature: Boolean)

    data class Brand(val name: String, val slogan: String, val about: String, val logoUrl: String, val logoWhiteUrl: String, val faviconUrl: String)

    data class PhotoTexts(val title: String, val description: String)

    data class SupportTexts(val emailResponseHint: String, val responseTimeHint: String)

    data class Perk(val icon: String, val title: String, val subtitle: String)

    /** @property type `ENAMAD` or `SAMANDEHI`. */
    data class TrustBadge(val type: String, val label: String, val imageUrl: String, val linkUrl: String)

    data class FooterGroup(val group: String, val links: List<Link>)

    data class Link(val label: String, val url: String)
}

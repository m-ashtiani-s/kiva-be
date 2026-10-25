package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.application.repository.SiteContentQm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** The site's own texts, one row. Created by V31__content_basics.sql (Exposed never creates tables); keep them in step. */
object SiteContentTbl : LongIdTable(name = "sitecontent_tbl", columnName = "pId") {
    /** Read strictly: a key missing or misspelled by an SQL edit fails loudly instead of showing nothing on the site. */
    val document = jsonb<SiteContentDocument>("document", Json)
    val updatedOn = timestampWithTimeZone("updatedOn")
}

/** `sitecontent_tbl.document` as V31 wrote it, with V36's `home` and V37's `search`; every key required. */
@Serializable
data class SiteContentDocument(
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
    val search: Search,
) {
    /** The search panel's suggested queries (V37; roadmap phase 12, decision 13). */
    @Serializable
    data class Search(val popularQueries: List<String>)

    /** The home page's fixed sections (V36; roadmap phase 11, decision 15). */
    @Serializable
    data class Home(val hero: Hero, val features: List<Perk>, val howItWorks: List<Step>, val showcaseProductId: Long?, val showcaseMessenger: String)

    @Serializable
    data class Hero(val eyebrow: String, val title: String, val text: String, val primaryCta: Link, val secondaryCta: Link)

    @Serializable
    data class Step(val step: Int, val title: String, val text: String, val isSignature: Boolean)

    @Serializable
    data class Brand(val name: String, val slogan: String, val about: String, val logoUrl: String, val logoWhiteUrl: String, val faviconUrl: String)

    @Serializable
    data class PhotoTexts(val title: String, val description: String)

    @Serializable
    data class SupportTexts(val emailResponseHint: String, val responseTimeHint: String)

    @Serializable
    data class Perk(val icon: String, val title: String, val subtitle: String)

    @Serializable
    data class TrustBadge(val type: String, val label: String, val imageUrl: String, val linkUrl: String)

    @Serializable
    data class FooterGroup(val group: String, val links: List<Link>)

    @Serializable
    data class Link(val label: String, val url: String)

    fun toQm() = SiteContentQm(
        brand = SiteContentQm.Brand(brand.name, brand.slogan, brand.about, brand.logoUrl, brand.logoWhiteUrl, brand.faviconUrl),
        currencyLabel = currencyLabel,
        reservationDescription = reservationDescription,
        returnsPolicyUrl = returnsPolicyUrl,
        preShipmentPhoto = SiteContentQm.PhotoTexts(preShipmentPhoto.title, preShipmentPhoto.description),
        support = SiteContentQm.SupportTexts(support.emailResponseHint, support.responseTimeHint),
        productPerks = productPerks.map { SiteContentQm.Perk(it.icon, it.title, it.subtitle) },
        trustBadges = trustBadges.map { SiteContentQm.TrustBadge(it.type, it.label, it.imageUrl, it.linkUrl) },
        footerLinks = footerLinks.map { group -> SiteContentQm.FooterGroup(group.group, group.links.map { SiteContentQm.Link(it.label, it.url) }) },
        home = SiteContentQm.Home(
            hero = SiteContentQm.Hero(
                home.hero.eyebrow, home.hero.title, home.hero.text,
                SiteContentQm.Link(home.hero.primaryCta.label, home.hero.primaryCta.url), SiteContentQm.Link(home.hero.secondaryCta.label, home.hero.secondaryCta.url),
            ),
            features = home.features.map { SiteContentQm.Perk(it.icon, it.title, it.subtitle) },
            howItWorks = home.howItWorks.map { SiteContentQm.Step(it.step, it.title, it.text, it.isSignature) },
            showcaseProductId = home.showcaseProductId,
            showcaseMessenger = home.showcaseMessenger,
        ),
        popularQueries = search.popularQueries,
    )
}

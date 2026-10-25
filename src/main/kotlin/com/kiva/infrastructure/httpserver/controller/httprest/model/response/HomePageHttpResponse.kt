package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.HomePageDto
import com.kiva.application.repository.SiteContentQm
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/**
 * Contract schema `HomePage`: every part always there; the content's texts with the settings they name filled in
 * (ContentTexts.fill). No card has a heart (`isWishlisted` null): the answer is cached for everyone.
 */
@Serializable
data class HomePageHttpResponse(
    val hero: HomeHeroHttpResponse,
    val features: List<PerkHttpResponse>,
    val categories: List<CategoryHttpResponse>,
    val newArrivals: List<ProductSummaryHttpResponse>,
    val promos: List<BannerHttpResponse>,
    val sale: CampaignHttpResponse?,
    val testimonials: TestimonialListHttpResponse,
    val howItWorks: List<HomeStepHttpResponse>,
) {
    companion object {
        /** @param siteUrl the shop's address, for the categories' own pages. */
        fun from(page: HomePageDto, urls: MediaUrls, siteUrl: String): HomePageHttpResponse {
            fun fill(text: String) = ContentTexts.fill(text, page.settings)
            val hero = page.home.hero
            return HomePageHttpResponse(
                hero = HomeHeroHttpResponse(
                    eyebrow = fill(hero.eyebrow),
                    title = fill(hero.title),
                    text = fill(hero.text),
                    primaryCta = HomeCtaHttpResponse.from(hero.primaryCta, ::fill),
                    secondaryCta = HomeCtaHttpResponse.from(hero.secondaryCta, ::fill),
                    showcaseProduct = page.showcase?.let { ProductSummaryHttpResponse.from(it, filterColors = emptyList(), urls) },
                    showcaseMessenger = page.home.showcaseMessenger,
                ),
                features = page.home.features.map { PerkHttpResponse(it.icon, fill(it.title), fill(it.subtitle)) },
                categories = page.categories.map { CategoryHttpResponse.from(it, urls, siteUrl) },
                newArrivals = page.newArrivals.map { ProductSummaryHttpResponse.from(it, filterColors = emptyList(), urls) },
                promos = page.promos.map { BannerHttpResponse.from(it, urls) },
                sale = page.sale?.let { CampaignHttpResponse.from(it, urls) },
                testimonials = TestimonialListHttpResponse.from(page.testimonials, urls),
                howItWorks = page.home.howItWorks.map { HomeStepHttpResponse(it.step, fill(it.title), fill(it.text), it.isSignature) },
            )
        }
    }
}

/** `HomePage.hero`. */
@Serializable
data class HomeHeroHttpResponse(
    val eyebrow: String,
    val title: String,
    val text: String,
    val primaryCta: HomeCtaHttpResponse,
    val secondaryCta: HomeCtaHttpResponse,
    val showcaseProduct: ProductSummaryHttpResponse?,
    val showcaseMessenger: String,
)

@Serializable
data class HomeCtaHttpResponse(val label: String, val url: String) {
    companion object {
        fun from(link: SiteContentQm.Link, fill: (String) -> String) = HomeCtaHttpResponse(fill(link.label), link.url)
    }
}

/** One step of «چطور کار می‌کنیم». */
@Serializable
data class HomeStepHttpResponse(val step: Int, val title: String, val text: String, val isSignature: Boolean)

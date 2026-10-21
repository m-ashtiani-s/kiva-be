package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.StaticPageDto
import kotlinx.serialization.Serializable

/** Contract schema `StaticPage`: the page as the site shows it, its search engine fields filled in for the site. */
@Serializable
data class StaticPageHttpResponse(
    val slug: String,
    val title: String,
    val content: String,
    val updatedAt: String,
    val seo: PageSeoHttpResponse,
) {
    companion object {
        /** @param siteUrl the shop's address (`https://kiva.ir`), for the canonical address. */
        fun from(page: StaticPageDto, siteUrl: String) = StaticPageHttpResponse(
            slug = page.slug,
            title = page.title,
            content = page.content,
            updatedAt = page.updatedOn.toString(),
            seo = PageSeoHttpResponse(
                title = page.seo.title ?: "${page.title} | کیوا",
                description = page.seo.description,
                canonicalUrl = "${siteUrl.trimEnd('/')}/pages/${page.slug}",
                ogImageUrl = null,
                noIndex = page.seo.noIndex,
            ),
        )
    }
}

/** Contract schema `SeoMeta` for a static page. A page has no picture of its own yet: [ogImageUrl] is null. */
@Serializable
data class PageSeoHttpResponse(
    val title: String,
    val description: String?,
    val canonicalUrl: String,
    val ogImageUrl: String?,
    val noIndex: Boolean,
)

/** Contract schema `AdminStaticPageSummary`: a row of the panel's list, without the content. */
@Serializable
data class AdminStaticPageSummaryHttpResponse(val slug: String, val title: String, val updatedAt: String) {
    companion object {
        fun from(page: StaticPageDto) = AdminStaticPageSummaryHttpResponse(page.slug, page.title, page.updatedOn.toString())
    }
}

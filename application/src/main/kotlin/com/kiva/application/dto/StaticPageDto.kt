package com.kiva.application.dto

import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.page.entity.StaticPage
import java.time.Instant

/**
 * A static page as the contract's StaticPage shows it: [content] is the sanitized HTML; [seo] the editor's own search
 * engine fields (the HTTP layer adds the canonical address and the defaults).
 */
data class StaticPageDto(val slug: String, val title: String, val content: String, val seo: SeoMeta, val updatedOn: Instant) {
    override fun toString(): String = "StaticPageDto(slug=$slug, content=${content.length} chars)"

    companion object {
        fun from(page: StaticPage) = StaticPageDto(page.slug.value, page.draft.title, page.draft.content, page.draft.seo, page.updatedOn)
    }
}

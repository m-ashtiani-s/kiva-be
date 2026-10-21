package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.application.usecase.content.usecase.command.SavePageUc
import kotlinx.serialization.Serializable

/**
 * Contract schema `StaticPageUpsertRequest`, the body of `PUT /admin/pages/{slug}`. Every field nullable with a default, so
 * one left out is reported with every other problem of the form; anything else of `SeoMeta` (`canonicalUrl`, …) is ignored.
 */
@Serializable
data class StaticPageUpsertHttpRequest(
    val slug: String? = null,
    val title: String? = null,
    val content: String? = null,
    val seo: Seo? = null,
) {
    @Serializable
    data class Seo(val title: String? = null, val description: String? = null, val noIndex: Boolean? = null)

    fun toFields() = SavePageUc.Fields(slug, title, content, seo?.title, seo?.description, seo?.noIndex)
}

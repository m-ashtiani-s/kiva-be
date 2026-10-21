package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.application.usecase.content.usecase.command.SaveAnnouncementsUc
import com.kiva.application.usecase.content.usecase.command.SavePageUc
import com.kiva.domain.aggregate.faq.model.FaqDraft
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

/**
 * Contract schema `FaqGroupInput`, one group of the `PUT /admin/faq/groups` body (an array of these). `id` is the group's
 * anchor; a `sortOrder` sent along is ignored (the array's order is the order).
 */
@Serializable
data class FaqGroupInputHttpRequest(
    val id: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val isSignature: Boolean? = null,
    val questions: List<FaqItemInputHttpRequest>? = null,
) {
    fun toInput() = FaqDraft.GroupInput(id, name, icon, isSignature, questions?.map { FaqDraft.QuestionInput(it.id, it.question, it.answer) })
}

/** Contract schema `FaqItemInput`: a question with its id (the same question) or without (a new one). */
@Serializable
data class FaqItemInputHttpRequest(val id: Long? = null, val question: String? = null, val answer: String? = null)

/**
 * Contract schema `AnnouncementInput`, one announcement of the `PUT /admin/announcements` body (an array of these). The
 * times are read as text, so a malformed one is 400 DATE_TIME_INVALID on its path; a `sortOrder` sent along is ignored.
 */
@Serializable
data class AnnouncementInputHttpRequest(
    val id: Long? = null,
    val icon: String? = null,
    val text: String? = null,
    val url: String? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
) {
    fun toFields() = SaveAnnouncementsUc.Fields(id, icon, text, url, startsAt, endsAt)
}

package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AnnouncementDto
import com.kiva.application.dto.FaqGroupDto
import com.kiva.application.dto.FaqPageDto
import com.kiva.application.dto.StaticPageDto
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.SupportHours
import com.kiva.infrastructure.common.http.toPersianNumber
import kotlinx.serialization.Serializable
import java.time.LocalTime

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

/** Contract schema `FaqGroup`; `sortOrder` is the place in the list. */
@Serializable
data class FaqGroupHttpResponse(
    val id: String,
    val name: String,
    val icon: String?,
    val isSignature: Boolean,
    val sortOrder: Int,
    val questions: List<FaqItemHttpResponse>,
) {
    companion object {
        fun from(group: FaqGroupDto, sortOrder: Int) = FaqGroupHttpResponse(
            id = group.anchor,
            name = group.name,
            icon = group.icon,
            isSignature = group.isSignature,
            sortOrder = sortOrder,
            questions = group.questions.mapIndexed { q, it -> FaqItemHttpResponse(it.id, it.question, it.answer, it.questionHighlighted, it.answerHighlighted, q) },
        )

        fun listOf(groups: List<FaqGroupDto>): List<FaqGroupHttpResponse> = groups.mapIndexed { g, group -> from(group, g) }
    }
}

/** Contract schema `FaqItem`. */
@Serializable
data class FaqItemHttpResponse(
    val id: Long,
    val question: String,
    val answer: String,
    val questionHighlighted: String?,
    val answerHighlighted: String?,
    val sortOrder: Int,
)

/** Contract schema `FaqResponse`: [emptyMessage] only when there is nothing to show. */
@Serializable
data class FaqResponseHttpResponse(val groups: List<FaqGroupHttpResponse>, val totalMatches: Int, val emptyMessage: String?) {
    companion object {
        fun from(page: FaqPageDto) =
            FaqResponseHttpResponse(FaqGroupHttpResponse.listOf(page.groups), page.totalMatches, ContentTexts.FAQ_EMPTY.takeIf { page.groups.isEmpty() })
    }
}

/** The Persian texts of the content endpoints. */
object ContentTexts {
    const val FAQ_EMPTY = "سوالی با این کلمه پیدا نکردیم؛ از پشتیبانی بپرس"

    /**
     * [text] with the settings it names filled in, in Persian digits (roadmap phase 5, decision 23): `{holdDays}`,
     * `{returnWindowDays}` and `{freeShippingThreshold}` (toman, with separators).
     */
    fun fill(text: String, settings: ShopSettings): String = text
        .replace("{holdDays}", settings.reservationHoldDays.toLong().toPersianNumber())
        .replace("{returnWindowDays}", settings.returnWindowDays.toLong().toPersianNumber())
        .replace("{freeShippingThreshold}", settings.freeShippingThreshold.toman.toPersianNumber())

    /**
     * When the messengers answer, as «سؤال داری؟» shows it under each (decision 18): the hours of the first line of the
     * support hours that has hours, «پاسخگویی ۹ تا ۲۱» (minutes only when there are: «۹:۳۰»). No such line: null.
     */
    fun responseHours(hours: List<SupportHours>): String? {
        val line = hours.firstOrNull { it.from != null } ?: return null
        // SupportHours has both or neither.
        return "پاسخگویی ${clock(line.from ?: return null)} تا ${clock(line.to ?: return null)}"
    }

    private fun clock(time: LocalTime): String =
        time.hour.toLong().toPersianNumber() + if (time.minute == 0) "" else ":" + time.minute.toString().padStart(2, '0').map { '۰' + (it - '0') }.joinToString("")

    /**
     * A support number as people read it, in Persian digits (decision 16): a landline as its area code and the rest
     * (`۰۲۱-۹۱۰۰۱۲۳۴`), a mobile in three parts (`۰۹۱۲-۳۴۵-۶۷۸۹`).
     */
    fun phoneDisplay(phone: String): String {
        val parts = if (phone.startsWith("09")) listOf(phone.take(4), phone.substring(4, 7), phone.substring(7)) else listOf(phone.take(3), phone.substring(3))
        return parts.joinToString("-") { part -> part.map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("") }
    }
}

/** Contract schema `Announcement`; `sortOrder` is the place in the list. */
@Serializable
data class AnnouncementHttpResponse(
    val id: Long,
    val icon: String?,
    val text: String,
    val url: String?,
    val sortOrder: Int,
    val startsAt: String?,
    val endsAt: String?,
) {
    companion object {
        fun listOf(items: List<AnnouncementDto>): List<AnnouncementHttpResponse> = items.mapIndexed { i, it ->
            AnnouncementHttpResponse(it.id, it.icon, it.text, it.url, i, it.startsAt?.toString(), it.endsAt?.toString())
        }
    }
}

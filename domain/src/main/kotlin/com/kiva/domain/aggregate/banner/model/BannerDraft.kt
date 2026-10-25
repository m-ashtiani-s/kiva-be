package com.kiva.domain.aggregate.banner.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.forField
import com.kiva.common.text.normalizePersianLetters
import com.kiva.common.valueobject.OpenWindow
import com.kiva.common.valueobject.SiteLink
import com.kiva.domain.aggregate.banner.model.valueobject.BannerCta
import com.kiva.domain.aggregate.banner.model.valueobject.BannerOverlay
import com.kiva.domain.aggregate.banner.model.valueobject.BannerPlacement
import com.kiva.domain.aggregate.banner.model.valueobject.BannerTag
import com.kiva.domain.aggregate.banner.model.valueobject.BannerTheme
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel
import java.time.Instant

/**
 * A banner as the panel's form sends it (`POST`/`PUT /admin/banners*`; roadmap section 16.2, decision 8), checked on its
 * own shape: every problem at once, each under its field. `PUT` replaces the whole banner: an optional field left out is
 * null (or its default). Whether the picture is a BANNER image is the use case's (MediaAttachments).
 */
@ConsistentCopyVisibility
data class BannerDraft private constructor(
    val placement: BannerPlacement,
    val theme: BannerTheme,
    val tag: BannerTag?,
    val title: String,
    val text: String?,
    val cta: BannerCta?,
    val imageMediaId: MediaId,
    val overlay: BannerOverlay?,
    val sortOrder: Int,
    val window: OpenWindow,
) {
    /** The form as it came; [startsAt] and [endsAt] already read (DATE_TIME_INVALID is the caller's). */
    data class Input(
        val placement: String?,
        val theme: String?,
        val tagLabel: String?,
        val tagIcon: String?,
        val title: String?,
        val text: String?,
        val ctaLabel: String?,
        val ctaUrl: String?,
        val imageMediaId: String?,
        val overlayHighlight: String?,
        val overlayTitle: String?,
        val overlaySubtitle: String?,
        val overlayChannel: String?,
        val sortOrder: Int?,
        val startsAt: Result<Instant?, ResultFailure>,
        val endsAt: Result<Instant?, ResultFailure>,
    )

    companion object {
        const val MAX_TITLE = 80
        const val MAX_TEXT = 200
        const val MAX_SHORT = 30

        fun makeNew(input: Input): Result<BannerDraft, InputProblems> {
            val problems = Problems()
            val placement = problems.take(enumOf<BannerPlacement>(input.placement, Problem.PlacementInvalid, required = true).forField("placement"))
            val theme = problems.take(enumOf<BannerTheme>(input.theme, Problem.ThemeInvalid, required = false).forField("theme"))
            val tagLabel = problems.take(optional(input.tagLabel, MAX_SHORT).forField("tagLabel"))
            val tagIcon = problems.take(iconOf(input.tagIcon).forField("tagIcon"))
            val title = problems.take(required(input.title, MAX_TITLE).forField("title"))
            val text = problems.take(optional(input.text, MAX_TEXT).forField("text"))
            val ctaLabel = problems.take(optional(input.ctaLabel, MAX_SHORT).forField("ctaLabel"))
            val ctaUrl = problems.take(urlOf(input.ctaUrl).forField("ctaUrl"))
            val image = problems.take(imageOf(input.imageMediaId).forField("imageMediaId"))
            val highlight = problems.take(optional(input.overlayHighlight, MAX_SHORT).forField("overlayHighlight"))
            val overlayTitle = problems.take(optional(input.overlayTitle, MAX_SHORT).forField("overlayTitle"))
            val overlaySubtitle = problems.take(optional(input.overlaySubtitle, MAX_SHORT).forField("overlaySubtitle"))
            val channel = problems.take(channelOf(input.overlayChannel).forField("overlayChannel"))
            val startsAt = problems.take(input.startsAt.forField("startsAt"))
            val endsAt = problems.take(input.endsAt.forField("endsAt"))
            val label = input.ctaLabel?.trim()?.ifEmpty { null }
            val url = input.ctaUrl?.trim()?.ifEmpty { null }
            if (label != null && url == null) problems.add(FieldFailure("ctaUrl", Problem.CtaIncomplete))
            if (label == null && url != null) problems.add(FieldFailure("ctaLabel", Problem.CtaIncomplete))
            val start = input.startsAt.getOrNull()
            val end = input.endsAt.getOrNull()
            if (start != null && end != null && !end.isAfter(start)) problems.add(FieldFailure("endsAt", Problem.EndsBeforeStart))
            if (problems.any()) return Result.failure(problems.all())

            val overlayParts = listOf(highlight.value, overlayTitle.value, overlaySubtitle.value, channel.value)
            return Result.success(
                BannerDraft(
                    placement = placement.value!!,
                    theme = theme.value ?: BannerTheme.CREAM,
                    tag = tagLabel.value?.let { BannerTag(it, tagIcon.value) },
                    title = title.value,
                    text = text.value,
                    cta = ctaLabel.value?.let { BannerCta(it, ctaUrl.value!!) },
                    imageMediaId = image.value,
                    overlay = if (overlayParts.all { it == null }) null else BannerOverlay(highlight.value, overlayTitle.value, overlaySubtitle.value, channel.value),
                    sortOrder = input.sortOrder ?: 0,
                    window = OpenWindow(startsAt.value, endsAt.value),
                ),
            )
        }

        private inline fun <reified E : Enum<E>> enumOf(raw: String?, invalid: ResultFailure, required: Boolean): Result<E?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return if (required) Result.failure(FieldRequired) else Result.success(null)
            return enumValues<E>().firstOrNull { it.name == text }?.let { Result.success(it) } ?: Result.failure(invalid)
        }

        private fun required(raw: String?, max: Int): Result<String, ResultFailure> {
            val text = raw?.trim()?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
            return if (text.length > max) Result.failure(TextTooLong(max)) else Result.success(text)
        }

        private fun optional(raw: String?, max: Int): Result<String?, ResultFailure> {
            val text = raw?.trim()?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.success(null)
            return if (text.length > max) Result.failure(TextTooLong(max)) else Result.success(text)
        }

        private fun iconOf(raw: String?): Result<IconName?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return IconName.makeNew(text)
        }

        private fun urlOf(raw: String?): Result<SiteLink?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return SiteLink.makeNew(text)
        }

        private fun imageOf(raw: String?): Result<MediaId, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.failure(Problem.ImageRequired)
            return MediaId.makeNew(text)
        }

        private fun channelOf(raw: String?): Result<SocialChannel?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return SocialChannel.entries.firstOrNull { it.name == text }?.let { Result.success(it) } ?: Result.failure(Problem.ChannelInvalid)
        }
    }

    // No field here: makeNew names each.
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object PlacementInvalid : Problem("BANNER_PLACEMENT_INVALID")
        data object ThemeInvalid : Problem("BANNER_THEME_INVALID")
        data object ChannelInvalid : Problem("SOCIAL_CHANNEL_INVALID")
        data object ImageRequired : Problem("IMAGE_REQUIRED")
        data object CtaIncomplete : Problem("CTA_INCOMPLETE")
        data object EndsBeforeStart : Problem("DATE_RANGE_INVALID")
    }
}

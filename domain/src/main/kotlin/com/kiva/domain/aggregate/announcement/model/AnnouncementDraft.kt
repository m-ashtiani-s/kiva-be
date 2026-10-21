package com.kiva.domain.aggregate.announcement.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.ListItemDuplicate
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.TooManyItems
import com.kiva.common.railway.forField
import com.kiva.common.text.normalizePersianLetters
import com.kiva.common.valueobject.SiteLink
import com.kiva.domain.aggregate.announcement.entity.AnnouncementBar
import com.kiva.domain.aggregate.announcement.model.valueobject.AnnouncementId
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import java.time.Instant

/**
 * The announcement bar as the panel's form sends it (`PUT /admin/announcements`; roadmap phase 5, decision 11), checked
 * on its own shape: every problem at once, each on its path (`1.url`). An announcement with an id is that one; one without
 * is new. Whether each id is one the shop has is [unknownIds]'s, asked with the stored bar.
 */
@ConsistentCopyVisibility
data class AnnouncementDraft private constructor(val items: List<Item>) {

    /** @property id null for a new announcement. */
    data class Item(val id: AnnouncementId?, val icon: IconName?, val text: String, val url: SiteLink?, val startsAt: Instant?, val endsAt: Instant?)

    /**
     * An announcement as the form sent it. [startsAt] and [endsAt] come already read by the caller (they are text in the
     * contract), as a moment, none, or DATE_TIME_INVALID.
     */
    data class Input(
        val id: Long?,
        val icon: String?,
        val text: String?,
        val url: String?,
        val startsAt: Result<Instant?, ResultFailure>,
        val endsAt: Result<Instant?, ResultFailure>,
    )

    /** Each id of this draft the shop doesn't have, as ANNOUNCEMENT_NOT_FOUND on its path. */
    fun unknownIds(known: Set<AnnouncementId>): List<ResultFailure> =
        items.mapIndexedNotNull { i, item -> item.id?.takeIf { it !in known }?.let { FieldFailure("$i.id", Failure.NotFound) } }

    /** The bar this draft makes: each new announcement gets an id from [newId], in the order they stand. */
    fun toBar(newId: () -> AnnouncementId): AnnouncementBar =
        AnnouncementBar(items.map { AnnouncementBar.Announcement(it.id ?: newId(), it.icon, it.text, it.url, it.startsAt, it.endsAt) })

    /** True when it would leave [bar] as it is: nothing new, and every announcement the same, in the same order. */
    fun isSameAs(bar: AnnouncementBar): Boolean = items.none { it.id == null } && toBar { error("no new announcement") } == bar

    companion object {
        const val MAX_ITEMS = 10
        const val MAX_TEXT = 200

        /** Where a problem of the whole array (too many) is put: the body is an array with no name of its own (decision 24). */
        const val FIELD = "announcements"

        fun makeNew(inputs: List<Input>): Result<AnnouncementDraft, InputProblems> {
            val problems = Problems()
            if (inputs.size > MAX_ITEMS) problems.add(FieldFailure(FIELD, TooManyItems(MAX_ITEMS)))
            val ids = mutableSetOf<Long>()
            val parts = inputs.mapIndexed { i, input ->
                val startsAt = problems.take(input.startsAt.forField("$i.startsAt"))
                val endsAt = problems.take(input.endsAt.forField("$i.endsAt"))
                val starts = input.startsAt.getOrNull()
                val ends = input.endsAt.getOrNull()
                if (starts != null && ends != null && !ends.isAfter(starts)) problems.add(FieldFailure("$i.endsAt", Failure.EndsBeforeStart))
                ItemParts(
                    id = problems.take(idOf(input.id, ids).forField("$i.id")),
                    icon = problems.take(iconOf(input.icon).forField("$i.icon")),
                    text = problems.take(textOf(input.text).forField("$i.text")),
                    url = problems.take(urlOf(input.url).forField("$i.url")),
                    startsAt = startsAt,
                    endsAt = endsAt,
                )
            }
            if (problems.any()) return Result.failure(problems.all())
            return Result.success(AnnouncementDraft(parts.map { it.item() }))
        }

        /** An announcement's checked parts; read only when none of the form's parts failed. */
        private class ItemParts(
            val id: Problems.Part<AnnouncementId?>,
            val icon: Problems.Part<IconName?>,
            val text: Problems.Part<String>,
            val url: Problems.Part<SiteLink?>,
            val startsAt: Problems.Part<Instant?>,
            val endsAt: Problems.Part<Instant?>,
        ) {
            fun item() = Item(id.value, icon.value, text.value, url.value, startsAt.value, endsAt.value)
        }

        private fun idOf(raw: Long?, seen: MutableSet<Long>): Result<AnnouncementId?, ResultFailure> {
            if (raw == null) return Result.success(null)
            if (raw <= 0) return Result.failure(Failure.NotFound)
            return if (seen.add(raw)) Result.success(AnnouncementId.constructFromPersisted(raw)) else Result.failure(ListItemDuplicate)
        }

        private fun iconOf(raw: String?): Result<IconName?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return IconName.makeNew(text)
        }

        private fun textOf(raw: String?): Result<String, ResultFailure> {
            val text = raw?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(Failure.TextRequired)
            return if (text.length > MAX_TEXT) Result.failure(TextTooLong(MAX_TEXT)) else Result.success(text)
        }

        private fun urlOf(raw: String?): Result<SiteLink?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return SiteLink.makeNew(text)
        }
    }

    // No field here: the draft puts each on its path.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object TextRequired : Failure("ANNOUNCEMENT_TEXT_REQUIRED")
        data object NotFound : Failure("ANNOUNCEMENT_NOT_FOUND")
        data object EndsBeforeStart : Failure("DATE_RANGE_INVALID")
    }
}

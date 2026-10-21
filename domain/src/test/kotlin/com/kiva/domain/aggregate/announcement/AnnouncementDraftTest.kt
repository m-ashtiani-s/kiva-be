package com.kiva.domain.aggregate.announcement

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.announcement.entity.AnnouncementBar
import com.kiva.domain.aggregate.announcement.model.AnnouncementDraft
import com.kiva.domain.aggregate.announcement.model.valueobject.AnnouncementId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The announcement bar's form and which announcements show when (roadmap phase 5, decisions 5 and 11). */
class AnnouncementDraftTest {

    private val now: Instant = Instant.parse("2026-10-21T08:00:00Z")

    private object BadMoment : ResultFailure("DATE_TIME_INVALID", FailureKind.INVALID)

    private fun input(
        id: Long? = null,
        text: String? = "ارسال رایگان",
        url: String? = null,
        icon: String? = "truck",
        startsAt: Instant? = null,
        endsAt: Instant? = null,
    ) = AnnouncementDraft.Input(id, icon, text, url, Result.success(startsAt), Result.success(endsAt))

    private fun problemsOf(result: Result<AnnouncementDraft, InputProblems>): List<String> = result.failureOrNull()?.causes.orEmpty().map { "${it.field} ${it.code}" }

    @Test
    fun `a good list is kept in its order - blank url and icon are none`() {
        val draft = AnnouncementDraft.makeNew(listOf(input(3, url = " /faq#reserve "), input(text = "  رزرو ۴ روزه ", url = " ", icon = ""))).getOrNull()!!

        assertEquals(listOf(3L, null), draft.items.map { it.id?.value })
        assertEquals(listOf("/faq#reserve", null), draft.items.map { it.url?.value })
        assertEquals(listOf("truck", null), draft.items.map { it.icon?.value })
        assertEquals("رزرو ۴ روزه", draft.items[1].text)
    }

    @Test
    fun `every problem at once, each on its path`() {
        val result = AnnouncementDraft.makeNew(
            listOf(
                input(text = " ", url = "javascript:alert(1)", icon = "Truck!"),
                input(id = 4, text = "م".repeat(201), startsAt = now, endsAt = now),
                input(id = 4),
                AnnouncementDraft.Input(null, null, "x", null, Result.failure(BadMoment), Result.success(null)),
            ),
        )

        assertEquals(
            listOf(
                "0.icon ICON_INVALID", "0.text ANNOUNCEMENT_TEXT_REQUIRED", "0.url URL_INVALID",
                "1.endsAt DATE_RANGE_INVALID", "1.text TEXT_TOO_LONG", "2.id LIST_ITEM_DUPLICATE", "3.startsAt DATE_TIME_INVALID",
            ),
            problemsOf(result).sorted(),
        )
    }

    @Test
    fun `11 announcements is TOO_MANY_ITEMS on announcements`() {
        assertEquals(listOf("announcements TOO_MANY_ITEMS"), problemsOf(AnnouncementDraft.makeNew(List(11) { input() })))
    }

    @Test
    fun `an id the shop doesn't have is ANNOUNCEMENT_NOT_FOUND on its path - new ones get ids in order`() {
        val draft = AnnouncementDraft.makeNew(listOf(input(3), input(), input(5))).getOrNull()!!

        assertEquals(listOf("2.id ANNOUNCEMENT_NOT_FOUND"), draft.unknownIds(setOf(AnnouncementId.constructFromPersisted(3))).map { "${it.field} ${it.code}" })
        assertEquals(listOf(3L, 9L, 5L), draft.toBar { AnnouncementId.constructFromPersisted(9) }.items.map { it.id.value })
    }

    @Test
    fun `the same list again is the same - one more isn't`() {
        val bar = AnnouncementDraft.makeNew(listOf(input(), input())).getOrNull()!!.let { draft ->
            var next = 0L
            draft.toBar { AnnouncementId.constructFromPersisted(++next) }
        }

        assertTrue(AnnouncementDraft.makeNew(listOf(input(1), input(2))).getOrNull()!!.isSameAs(bar))
        assertFalse(AnnouncementDraft.makeNew(listOf(input(1), input(2), input())).getOrNull()!!.isSameAs(bar))
        assertFalse(AnnouncementDraft.makeNew(listOf(input(2), input(1))).getOrNull()!!.isSameAs(bar))
    }

    @Test
    fun `what shows now - open ends, started, not yet, ended, ending exactly now`() {
        val hour = 3600L
        val bar = AnnouncementDraft.makeNew(
            listOf(
                input(text = "باز"),
                input(text = "شروع‌شده", startsAt = now.minusSeconds(hour)),
                input(text = "فردا", startsAt = now.plusSeconds(24 * hour)),
                input(text = "تمام‌شده", endsAt = now.minusSeconds(hour)),
                input(text = "همین الان تمام", startsAt = now.minusSeconds(hour), endsAt = now),
                input(text = "تا فردا", endsAt = now.plusSeconds(24 * hour)),
            ),
        ).getOrNull()!!.let { draft ->
            var next = 0L
            draft.toBar { AnnouncementId.constructFromPersisted(++next) }
        }

        assertEquals(listOf("باز", "شروع‌شده", "تا فردا"), bar.showingAt(now).map { it.text })
        assertEquals(emptyList(), AnnouncementBar.EMPTY.showingAt(now))
    }
}

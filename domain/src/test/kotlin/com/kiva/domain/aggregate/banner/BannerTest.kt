package com.kiva.domain.aggregate.banner

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.OpenWindow
import com.kiva.domain.aggregate.banner.entity.Banner
import com.kiva.domain.aggregate.banner.model.BannerDraft
import com.kiva.domain.aggregate.banner.model.valueobject.BannerId
import com.kiva.domain.aggregate.banner.model.valueobject.BannerTheme
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BannerTest {

    private val now = Instant.parse("2026-10-20T08:00:00Z")

    private fun input(
        tagLabel: String? = null,
        ctaLabel: String? = null,
        ctaUrl: String? = null,
        overlayTitle: String? = null,
        startsAt: Instant? = null,
        endsAt: Instant? = null,
    ) = BannerDraft.Input(
        "HOME_PROMO", null, tagLabel, "timer", " عنوان ", null, ctaLabel, ctaUrl, "med_" + "a".repeat(22), null, overlayTitle, null, null, null,
        Result.success(startsAt), Result.success(endsAt),
    )

    private fun draft(input: BannerDraft.Input) = (BannerDraft.makeNew(input) as Result.Success).value

    @Test
    fun `a bare form - CREAM, no tag without its label, no button, no overlay - the title trimmed`() {
        val made = draft(input())
        assertEquals(listOf(BannerTheme.CREAM, "عنوان", 0), listOf(made.theme, made.title, made.sortOrder))
        assertNull(made.tag, "an icon alone is no tag")
        assertNull(made.cta)
        assertNull(made.overlay)
        val full = draft(input(tagLabel = "تگ", overlayTitle = "روز"))
        assertEquals(listOf("timer", "روز"), listOf(full.tag?.icon?.value, full.overlay?.title))
    }

    @Test
    fun `a button needs both its parts - each missing half is CTA_INCOMPLETE on that half`() {
        val noUrl = BannerDraft.makeNew(input(ctaLabel = "بیشتر")) as Result.Failure
        assertEquals(listOf("ctaUrl CTA_INCOMPLETE"), noUrl.failure.causes.map { "${it.field} ${it.code}" })
        val noLabel = BannerDraft.makeNew(input(ctaUrl = "/faq")) as Result.Failure
        assertEquals(listOf("ctaLabel CTA_INCOMPLETE"), noLabel.failure.causes.map { "${it.field} ${it.code}" })
        assertEquals("/faq", draft(input(ctaLabel = "بیشتر", ctaUrl = "/faq")).cta?.url?.value)
    }

    @Test
    fun `visible inside its window only - either side open - the end itself outside`() {
        val banner = Banner.create(BannerId.constructFromPersisted(1), draft(input(startsAt = now, endsAt = now.plusSeconds(60))), now)
        assertFalse(banner.isVisibleAt(now.minusSeconds(1)))
        assertTrue(banner.isVisibleAt(now))
        assertFalse(banner.isVisibleAt(now.plusSeconds(60)))
        assertTrue(OpenWindow.ALWAYS.contains(now))
    }
}

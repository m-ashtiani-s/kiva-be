package com.kiva.domain.aggregate.campaign

import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.campaign.entity.Campaign
import com.kiva.domain.aggregate.campaign.model.CampaignDraft
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignId
import com.kiva.domain.aggregate.campaign.model.valueobject.CampaignWindow
import com.kiva.domain.aggregate.campaign.repository.CampaignRepository
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignTest {

    private val start = Instant.parse("2026-10-21T00:00:00Z")
    private val end = Instant.parse("2026-11-01T00:00:00Z")

    private fun draft(
        slug: String? = "paeez-1405",
        title: String? = " جشنواره پاييزه ",
        startsAt: Instant? = start,
        endsAt: Instant? = end,
        productIds: List<Long>? = null,
        isActive: Boolean? = null,
    ): Result<CampaignDraft, InputProblems> =
        CampaignDraft.makeNew(slug, title, null, null, Result.success(startsAt), Result.success(endsAt), productIds, isActive)

    private fun CampaignDraft.campaign(id: Long = 1) = Campaign.create(CampaignId.constructFromPersisted(id), this, start)

    /** Each problem as "path code", the leaves of the nested fields. */
    private fun InputProblems.paths(): Set<String> = leaves(null).toSet()

    private fun ResultFailure.leaves(path: String?): List<String> = when (this) {
        is FieldFailure -> original.leaves(if (path == null) name else "$path.$name")
        is InputProblems -> causes.flatMap { it.leaves(path) }
        else -> listOf("$path $code")
    }

    @Test
    fun `a good form - the title trimmed with Persian letters - active by default - every product when none is chosen`() {
        val made = draft().getOrNull()!!
        assertEquals(listOf("جشنواره پاییزه", "true", "0"), listOf(made.title, made.isActive.toString(), made.productIds.size.toString()))
    }

    @Test
    fun `a bad form - every problem at once - an end not after the start is DATE_RANGE_INVALID on endsAt`() {
        val bad = draft(slug = "a".repeat(61), title = " ", endsAt = start, productIds = listOf(3, 3, 0)) as Result.Failure
        assertEquals(
            setOf("slug TEXT_TOO_LONG", "title FIELD_REQUIRED", "endsAt DATE_RANGE_INVALID", "productIds.1 LIST_ITEM_DUPLICATE", "productIds.2 PRODUCT_ID_INVALID"),
            bad.failure.paths(),
        )
        val missing = draft(startsAt = null, endsAt = null) as Result.Failure
        assertEquals(setOf("startsAt FIELD_REQUIRED", "endsAt FIELD_REQUIRED"), missing.failure.paths())
        assertIs<Result.Failure<*>>(draft(productIds = (1L..201L).toList()))
    }

    @Test
    fun `live only while switched on and inside its window - the end itself is outside`() {
        val campaign = draft().getOrNull()!!.campaign()
        assertFalse(campaign.isLiveAt(start.minusSeconds(1)))
        assertTrue(campaign.isLiveAt(start))
        assertFalse(campaign.isLiveAt(end))
        assertFalse(draft(isActive = false).getOrNull()!!.campaign().isLiveAt(start))
    }

    @Test
    fun `windows overlap when they share a moment - back to back they don't`() {
        val window = CampaignWindow(start, end)
        assertTrue(window.overlaps(CampaignWindow(end.minusSeconds(1), end.plusSeconds(100))))
        assertFalse(window.overlaps(CampaignWindow(end, end.plusSeconds(100))))
    }

    @Test
    fun `replaced by the same form is nothing to save - by another it is the new campaign`() {
        val campaign = draft().getOrNull()!!.campaign()
        assertNull(campaign.replacedBy(draft().getOrNull()!!, end))
        val other = campaign.replacedBy(draft(isActive = false).getOrNull()!!, end)!!
        assertEquals(listOf(false, end, start), listOf(other.isActive, other.updatedOn, other.createdOn))
    }

    @Test
    fun `the domain service - a slug someone else has 409 - an overlapping active one 409 with its id - its own row and a switched-off form pass`() {
        val stored = draft().getOrNull()!!.campaign(id = 5)
        val repository = InMemoryCampaigns(stored)
        val service = CampaignDomainService(repository)

        assertEquals(CampaignDomainService.Failure.DuplicateSlug, (service.checkSave(draft().getOrNull()!!, self = null) as Result.Failure).failure)
        val overlap = (service.checkSave(draft(slug = "yalda").getOrNull()!!, self = null) as Result.Failure).failure
        assertEquals(CampaignDomainService.Failure.Overlap(stored.id), overlap)
        assertEquals(mapOf<String, Any>("campaignId" to 5L), overlap.meta)
        assertIs<Result.Success<Unit>>(service.checkSave(draft().getOrNull()!!, self = stored.id))
        assertIs<Result.Success<Unit>>(service.checkSave(draft(slug = "yalda", isActive = false).getOrNull()!!, self = null))
    }

    private class InMemoryCampaigns(vararg stored: Campaign) : CampaignRepository {
        private val rows = stored.toMutableList()
        override fun nextId() = CampaignId.constructFromPersisted(rows.size + 1L)
        override fun lockAll() = Unit
        override fun find(id: CampaignId) = rows.firstOrNull { it.id == id }
        override fun findAll() = rows.sortedByDescending { it.window.startsAt }
        override fun findBySlug(slug: Slug) = rows.firstOrNull { it.slug == slug }
        override fun findActiveOverlapping(window: CampaignWindow, except: CampaignId?) = rows.firstOrNull { it.isActive && it.id != except && it.window.overlaps(window) }
        override fun findLiveAt(now: Instant) = rows.firstOrNull { it.isLiveAt(now) }
        override fun add(campaign: Campaign) { rows += campaign }
        override fun save(campaign: Campaign) { rows.replaceAll { if (it.id == campaign.id) campaign else it } }
        override fun delete(id: CampaignId) = rows.removeIf { it.id == id }
    }
}

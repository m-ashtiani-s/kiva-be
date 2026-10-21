package com.kiva.domain.aggregate.announcement.entity

import com.kiva.common.valueobject.SiteLink
import com.kiva.domain.aggregate.announcement.model.valueobject.AnnouncementId
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import java.time.Instant

/**
 * The bar at the top of the site (roadmap phase 5, step 5.3): its announcements in order, shown in turn. Written whole
 * (`PUT /admin/announcements`, from an AnnouncementDraft), so the order is the array's (decision 11).
 */
data class AnnouncementBar(val items: List<Announcement>) {

    /**
     * One announcement. It shows from [startsAt] until (not at) [endsAt]; either open (null) means no limit that side.
     *
     * @property url where it links to, or nowhere.
     */
    data class Announcement(
        val id: AnnouncementId,
        val icon: IconName?,
        val text: String,
        val url: SiteLink?,
        val startsAt: Instant?,
        val endsAt: Instant?,
    ) {
        init {
            require(text.isNotBlank()) { "Announcement ${id.value} has no text" }
            require(startsAt == null || endsAt == null || endsAt.isAfter(startsAt)) { "Announcement ${id.value} ends before it starts" }
        }

        /** It shows at [now]. */
        fun isShowingAt(now: Instant): Boolean = (startsAt == null || !now.isBefore(startsAt)) && (endsAt == null || now.isBefore(endsAt))
    }

    // The draft refuses this with a Failure; only broken stored data gets here. A bug: stop loudly.
    init {
        require(items.map { it.id }.toSet().size == items.size) { "Two announcements share an id" }
    }

    val ids: Set<AnnouncementId> get() = items.mapTo(mutableSetOf()) { it.id }

    /** What the site shows at [now] (`GET /config`; decision 5): those whose time it is, in order. */
    fun showingAt(now: Instant): List<Announcement> = items.filter { it.isShowingAt(now) }

    companion object {
        val EMPTY = AnnouncementBar(emptyList())
    }
}

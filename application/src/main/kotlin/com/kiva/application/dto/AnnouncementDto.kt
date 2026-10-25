package com.kiva.application.dto

import com.kiva.domain.aggregate.announcement.entity.AnnouncementBar
import java.time.Instant

/** An announcement of the bar as the contract's Announcement shows it; its `sortOrder` is its place in the list. */
data class AnnouncementDto(val id: Long, val icon: String?, val text: String, val url: String?, val startsAt: Instant?, val endsAt: Instant?) {
    companion object {
        fun from(item: AnnouncementBar.Announcement) =
            AnnouncementDto(item.id.value, item.icon?.value, item.text, item.url?.value, item.startsAt, item.endsAt)
    }
}

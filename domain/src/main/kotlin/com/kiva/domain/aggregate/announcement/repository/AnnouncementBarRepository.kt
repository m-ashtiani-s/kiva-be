package com.kiva.domain.aggregate.announcement.repository

import com.kiva.domain.aggregate.announcement.entity.AnnouncementBar
import com.kiva.domain.aggregate.announcement.model.valueobject.AnnouncementId

/** The announcement bar, read and written whole. Runs inside the use case's transaction. */
interface AnnouncementBarRepository {

    /** Holds the bar until the transaction ends (an advisory lock), so two saves run one after the other. */
    fun lock()

    /** Every announcement, in order; [AnnouncementBar.EMPTY] before anyone wrote one. */
    fun load(): AnnouncementBar

    /** A new announcement's id, never given before. */
    fun nextId(): AnnouncementId

    /** Replaces the stored bar with [bar]. */
    fun save(bar: AnnouncementBar)
}

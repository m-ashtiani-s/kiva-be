package com.kiva.common.valueobject

import java.time.Instant

/**
 * When something shows: from [startsAt] until (not at) [endsAt], either one null for no limit on that side (an
 * announcement's, a banner's; roadmap phase 11, section 16.3). The form refuses an end not after the start with
 * DATE_RANGE_INVALID; here it is only the second wall.
 */
data class OpenWindow(val startsAt: Instant?, val endsAt: Instant?) {

    init {
        require(startsAt == null || endsAt == null || endsAt.isAfter(startsAt)) { "A window ends at $endsAt, not after it starts at $startsAt" }
    }

    fun contains(now: Instant): Boolean = (startsAt == null || !now.isBefore(startsAt)) && (endsAt == null || now.isBefore(endsAt))

    companion object {
        val ALWAYS = OpenWindow(null, null)
    }
}

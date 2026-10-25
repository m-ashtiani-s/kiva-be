package com.kiva.domain.aggregate.announcement.model.valueobject

/** An announcement's id (the contract's `Announcement.id`), given once and kept across saves; a positive number. */
@JvmInline
value class AnnouncementId private constructor(val value: Long) {

    init {
        require(value > 0) { "Invalid AnnouncementId $value" }
    }

    companion object {
        /** For an id read back from storage or given out by the repository. Still checked by `init`. */
        fun constructFromPersisted(value: Long): AnnouncementId = AnnouncementId(value)
    }
}

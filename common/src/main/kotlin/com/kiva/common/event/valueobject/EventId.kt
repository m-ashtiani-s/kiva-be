package com.kiva.common.event.valueobject

import java.util.UUID

/** The unique id of one stored event: a UUID in its usual lowercase form. */
@JvmInline
value class EventId private constructor(val value: String) {

    // Never user input: generated here or read back from storage, so a bad value is a bug.
    init {
        require(isCanonicalUuid(value)) { "Invalid EventId '$value'" }
    }

    companion object {
        fun generate(): EventId = EventId(UUID.randomUUID().toString())

        fun constructFromPersisted(value: String): EventId = EventId(value)

        // UUID.fromString also accepts odd shapes such as "1-2-3-4-5"; only the form it prints back is allowed.
        private fun isCanonicalUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}

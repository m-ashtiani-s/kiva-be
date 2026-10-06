package com.kiva.common.event.valueobject

/**
 * The version of an event's stored shape, from 1. It goes up only when the stored data of an event type changes,
 * so old events can still be read and converted.
 *
 * Never user input: written in code or read back from storage. So the constructor is public and a bad value is a bug.
 */
@JvmInline
value class EventVersion(val value: Int) {
    init {
        require(value >= 1) { "Invalid EventVersion $value" }
    }

    companion object {
        val FIRST = EventVersion(1)
    }
}

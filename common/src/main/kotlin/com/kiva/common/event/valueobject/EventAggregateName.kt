package com.kiva.common.event.valueobject

/**
 * Which kind of aggregate an event belongs to, in UPPER_SNAKE_CASE: `PRODUCT`, `INVENTORY`. Together with
 * [EventAggregateId] it names one stream. Stored with every event, so it is never renamed once in use.
 *
 * Never user input: written in code or read back from storage. So the constructor is public and a bad value is a bug.
 */
@JvmInline
value class EventAggregateName(val value: String) {
    init {
        require(FORMAT.matches(value)) { "Invalid EventAggregateName '$value'" }
    }

    private companion object {
        val FORMAT = Regex("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*")
    }
}

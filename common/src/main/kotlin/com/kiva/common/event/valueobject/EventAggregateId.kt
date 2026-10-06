package com.kiva.common.event.valueobject

/**
 * Which aggregate an event belongs to, as text so one event store can hold any kind of id (`12` for product 12).
 * Together with [EventAggregateName] it names one stream.
 *
 * Never user input: built in code from an aggregate's own id, or read back from storage. So the constructor is public
 * and a bad value is a bug.
 */
@JvmInline
value class EventAggregateId(val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim() && value.length <= MAX_LENGTH) { "Invalid EventAggregateId '$value'" }
    }

    companion object {
        const val MAX_LENGTH = 255

        /** For aggregates with a numeric id. */
        fun of(id: Long): EventAggregateId = EventAggregateId(id.toString())
    }
}

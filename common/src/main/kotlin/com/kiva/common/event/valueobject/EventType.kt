package com.kiva.common.event.valueobject

/**
 * The stored name of an event's kind, in PascalCase: `ProductCreated`. It is saved with every event and used to pick
 * the class when the event is read back, so a type that is already in use is never renamed.
 *
 * Never user input: written in code or read back from storage. So the constructor is public and a bad value is a bug.
 */
@JvmInline
value class EventType(val value: String) {
    init {
        require(FORMAT.matches(value)) { "Invalid EventType '$value'" }
    }

    private companion object {
        val FORMAT = Regex("[A-Z][A-Za-z0-9]*")
    }
}

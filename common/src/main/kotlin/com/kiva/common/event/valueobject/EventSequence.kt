package com.kiva.common.event.valueobject

/**
 * The position of an event in its aggregate's stream: 1 for the first event, then 2, 3, ... with no gaps.
 *
 * The event store is unique on (aggregate name, aggregate id, sequence). So when two requests change the same
 * aggregate at once, both try to write the same next number and the second one is refused: that is what keeps
 * concurrent changes from silently overwriting each other.
 */
@JvmInline
value class EventSequence private constructor(val value: Long) {

    // Never user input: counted by EventSourceEntity or read back from storage, so a bad value is a bug.
    init {
        require(value >= 1) { "Invalid EventSequence $value" }
    }

    fun next(): EventSequence = EventSequence(Math.addExact(value, 1))

    companion object {
        val FIRST = EventSequence(1)

        fun constructFromPersisted(value: Long): EventSequence = EventSequence(value)
    }
}

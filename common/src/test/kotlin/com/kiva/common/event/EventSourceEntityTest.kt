package com.kiva.common.event

import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.event.valueobject.EventType
import com.kiva.common.event.valueobject.EventVersion
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.UserId
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.properties.Delegates
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// ============ a tiny event-sourced aggregate, only for these tests ============

private sealed class LampEvent : DomainEvent {
    override val aggregateName = EventAggregateName("LAMP")
    override val version = EventVersion.FIRST
    override val occurredOn: Instant = Instant.parse("2026-10-06T08:00:00Z")
}

private data class LampInstalled(
    override val aggregateId: EventAggregateId,
    override val sequence: EventSequence,
    override val occurredByUserId: UserId,
    val room: String,
    override val id: EventId = EventId.generate(),
) : LampEvent() {
    override val type = EventType("LampInstalled")
}

private data class LampSwitchedOn(
    override val aggregateId: EventAggregateId,
    override val sequence: EventSequence,
    override val occurredByUserId: UserId,
    override val id: EventId = EventId.generate(),
) : LampEvent() {
    override val type = EventType("LampSwitchedOn")
}

private data class LampSwitchedOff(
    override val aggregateId: EventAggregateId,
    override val sequence: EventSequence,
    override val occurredByUserId: UserId,
    override val id: EventId = EventId.generate(),
) : LampEvent() {
    override val type = EventType("LampSwitchedOff")
}

/** A lamp in a room that can be switched on and off: the whole pattern of an event-sourced aggregate in small. */
private class Lamp private constructor() : EventSourceEntity<LampEvent>() {

    // Set by apply(), so not in the constructor. `lateinit` would do for String, but Kotlin doesn't allow it on
    // value classes (all our value objects): Delegates.notNull() does the same job and also throws if read too early.
    var id: EventAggregateId by Delegates.notNull()
        private set
    lateinit var room: String
        private set
    var isOn: Boolean = false
        private set

    /** Loading: replay the stored stream. */
    constructor(events: List<LampEvent>) : this() {
        applyEventStream(events)
    }

    /** Something new: the first event. */
    private constructor(id: EventAggregateId, room: String, by: UserId) : this() {
        raiseEvent(LampInstalled(id, nextSequence(), by, room))
    }

    companion object {
        fun install(id: String, room: String, by: UserId): Lamp = Lamp(EventAggregateId(id), room, by)
    }

    fun switchOn(by: UserId): Result<Lamp, SwitchFailure> {
        if (isOn) return Result.failure(SwitchFailure.AlreadyOn)   // rules first...
        raiseEvent(LampSwitchedOn(id, nextSequence(), by))          // ...then the fact
        return Result.success(this)
    }

    fun switchOff(by: UserId): Result<Lamp, SwitchFailure> {
        if (!isOn) return Result.failure(SwitchFailure.AlreadyOff)
        raiseEvent(LampSwitchedOff(id, nextSequence(), by))
        return Result.success(this)
    }

    /** Only for the tests of what the base class refuses: raises an event exactly as given. */
    fun raiseAsIs(event: LampEvent) = raiseEvent(event)

    override fun apply(event: LampEvent) {
        when (event) {
            is LampInstalled -> {
                id = event.aggregateId
                room = event.room
                isOn = false
            }
            is LampSwitchedOn -> isOn = true
            is LampSwitchedOff -> isOn = false
        }
    }

    sealed class SwitchFailure(code: String) : ResultFailure(code, FailureKind.BUSINESS_RULE) {
        data object AlreadyOn : SwitchFailure("LAMP_ALREADY_ON")
        data object AlreadyOff : SwitchFailure("LAMP_ALREADY_OFF")
    }
}

// ============ tests ============

class EventSourceEntityTest {

    private val sara = UserId.constructFromPersisted(7)
    private val lamp1 = EventAggregateId("lamp-1")

    private fun seq(value: Long) = EventSequence.constructFromPersisted(value)
    private fun Lamp.sequences() = getRecordedEvents().map { it.sequence.value }

    @Nested
    inner class NewEvents {

        @Test
        fun `the first event of a new aggregate is number 1, and it is applied and recorded`() {
            val lamp = Lamp.install("lamp-1", "hall", sara)

            assertEquals("hall", lamp.room)
            val installed = lamp.getRecordedEvents().single()
            assertEquals(1L, installed.sequence.value)
            assertEquals(EventType("LampInstalled"), installed.type)
            assertEquals(sara, installed.occurredByUserId)
        }

        @Test
        fun `several events raised on one object are numbered 1, 2, 3 instead of all getting the same number`() {
            val lamp = Lamp.install("lamp-1", "hall", sara)
            lamp.switchOn(sara)
            lamp.switchOff(sara)

            assertEquals(listOf(1L, 2L, 3L), lamp.sequences())
            assertEquals(false, lamp.isOn)
        }

        @Test
        fun `a rule that refuses raises nothing`() {
            val lamp = Lamp.install("lamp-1", "hall", sara)

            assertEquals(Result.failure(Lamp.SwitchFailure.AlreadyOff), lamp.switchOff(sara))
            assertEquals(listOf(1L), lamp.sequences())
        }

        @Test
        fun `an event with the wrong number is refused, and nothing changes`() {
            val lamp = Lamp.install("lamp-1", "hall", sara)

            assertFailsWith<IllegalArgumentException> { lamp.raiseAsIs(LampSwitchedOn(lamp1, seq(1), sara)) } // reused
            assertFailsWith<IllegalArgumentException> { lamp.raiseAsIs(LampSwitchedOn(lamp1, seq(3), sara)) } // skipped one
            assertEquals(false, lamp.isOn)
            assertEquals(listOf(1L), lamp.sequences())
        }

        @Test
        fun `an event of another aggregate is refused`() {
            val lamp = Lamp.install("lamp-1", "hall", sara)

            assertFailsWith<IllegalArgumentException> {
                lamp.raiseAsIs(LampSwitchedOn(EventAggregateId("lamp-2"), seq(2), sara))
            }
            assertEquals(false, lamp.isOn)
        }

        @Test
        fun `a new aggregate not saved yet has the number of events it raised as its version`() {
            val lamp = Lamp.install("lamp-1", "hall", sara)
            assertEquals(1L, lamp.version)

            lamp.switchOn(sara)

            assertEquals(2L, lamp.version)
        }

        @Test
        fun `the recorded events handed out do not change afterwards`() {
            val lamp = Lamp.install("lamp-1", "hall", sara)
            val handedOut = lamp.getRecordedEvents()

            lamp.switchOn(sara)

            assertEquals(1, handedOut.size)
        }
    }

    @Nested
    inner class Loading {

        @Test
        fun `replaying the recorded events rebuilds the same state, and replayed events are not recorded again`() {
            val original = Lamp.install("lamp-1", "hall", sara)
            original.switchOn(sara)

            val loaded = Lamp(original.getRecordedEvents())

            assertEquals(original.id, loaded.id)
            assertEquals(original.room, loaded.room)
            assertEquals(original.isOn, loaded.isOn)
            assertEquals(emptyList(), loaded.getRecordedEvents())
        }

        @Test
        fun `new events after loading continue the stream`() {
            val loaded = Lamp(listOf(LampInstalled(lamp1, seq(1), sara, "hall"), LampSwitchedOn(lamp1, seq(2), sara)))

            loaded.switchOff(UserId.SYSTEM)

            val switchedOff = loaded.getRecordedEvents().single()
            assertEquals(3L, switchedOff.sequence.value)
            assertEquals(UserId.SYSTEM, switchedOff.occurredByUserId)
        }

        @Test
        fun `an empty stream is refused`() {
            assertFailsWith<IllegalArgumentException> { Lamp(emptyList()) }
        }

        @Test
        fun `a stream with a gap, a repeat, or not starting at 1 is broken data and is refused`() {
            val installed = LampInstalled(lamp1, seq(1), sara, "hall")

            assertFailsWith<IllegalArgumentException> { Lamp(listOf(installed, LampSwitchedOn(lamp1, seq(3), sara))) }
            assertFailsWith<IllegalArgumentException> { Lamp(listOf(installed, LampSwitchedOn(lamp1, seq(1), sara))) }
            assertFailsWith<IllegalArgumentException> { Lamp(listOf(LampSwitchedOn(lamp1, seq(2), sara))) }
        }

        @Test
        fun `the version is the number of the last event, stored or raised since`() {
            val loaded = Lamp(listOf(LampInstalled(lamp1, seq(1), sara, "hall"), LampSwitchedOn(lamp1, seq(2), sara)))
            assertEquals(2L, loaded.version)

            loaded.switchOff(sara)

            assertEquals(3L, loaded.version)
        }

        @Test
        fun `a refused change leaves the version where it was`() {
            val loaded = Lamp(listOf(LampInstalled(lamp1, seq(1), sara, "hall")))

            loaded.switchOff(sara)

            assertEquals(1L, loaded.version)
        }

        @Test
        fun `a stream mixing two aggregates is refused`() {
            assertFailsWith<IllegalArgumentException> {
                Lamp(listOf(LampInstalled(lamp1, seq(1), sara, "hall"), LampSwitchedOn(EventAggregateId("lamp-2"), seq(2), sara)))
            }
        }
    }
}

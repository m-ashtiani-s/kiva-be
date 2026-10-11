package com.kiva.domain.aggregate.session.model.valueobject

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Whatever the client sends about its device is kept, cleaned, never refused: it is only for display. */
class SessionDeviceTest {

    @Test
    fun `texts are trimmed and keep what they say`() {
        val device = SessionDevice.of("  Chrome on Android ", " Mozilla/5.0 ", "203.0.113.9")

        assertEquals("Chrome on Android", device.name)
        assertEquals("Mozilla/5.0", device.userAgent)
        assertEquals("203.0.113.9", device.ip)
    }

    @Test
    fun `control characters are dropped, so nothing can break a line of a log or a page`() {
        assertEquals("Evil phone", SessionDevice.of("Evil\u0000 phone\n", null, null).name)
    }

    @Test
    fun `texts are cut to what the table holds`() {
        val device = SessionDevice.of("x".repeat(500), "y".repeat(500), "z".repeat(100))

        assertEquals(100, device.name?.length)
        assertEquals(255, device.userAgent?.length)
        assertEquals(45, device.ip?.length)
    }

    @Test
    fun `an empty or missing text is no text`() {
        val device = SessionDevice.of("   ", "", null)

        assertNull(device.name)
        assertNull(device.userAgent)
        assertNull(device.ip)
    }
}

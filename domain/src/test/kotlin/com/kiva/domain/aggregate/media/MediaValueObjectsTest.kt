package com.kiva.domain.aggregate.media

import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The media's value objects: ids, purposes, alt text and stored paths. */
class MediaValueObjectsTest {

    @Test
    fun `a media id is med_ and 22 letters or digits, trimmed`() {
        assertEquals("med_5Hq2xT9aLmQ0vB3nR7sK1w", MediaId.makeNew(" med_5Hq2xT9aLmQ0vB3nR7sK1w ").getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "med_", "med_5Hq2xT9aLmQ0vB3nR7sK1", "med_5Hq2xT9aLmQ0vB3nR7sK1wX", "MED_5Hq2xT9aLmQ0vB3nR7sK1w", "med_5Hq2xT9aLmQ0vB3nR7sK1-"])
    fun `anything else is MEDIA_ID_INVALID`(raw: String) {
        assertEquals("MEDIA_ID_INVALID", MediaId.makeNew(raw).failureOrNull()?.code)
    }

    @Test
    fun `a broken stored media id stops loudly`() {
        assertFailsWith<IllegalArgumentException> { MediaId.constructFromPersisted("med_x") }
    }

    @Test
    fun `staff upload with the six panel purposes - a customer's purpose or an unknown one is MEDIA_PURPOSE_INVALID on purpose`() {
        assertEquals(
            listOf(MediaPurpose.PRODUCT, MediaPurpose.PRE_SHIPMENT, MediaPurpose.BLOG, MediaPurpose.BANNER, MediaPurpose.CATEGORY, MediaPurpose.TESTIMONIAL),
            MediaPurpose.entries.filter { MediaPurpose.makeNewForStaff(it.name).getOrNull() != null },
        )
        listOf("RETURN_EVIDENCE", "REVIEW_PHOTO", "product", "AVATAR", null).forEach { raw ->
            val failure = MediaPurpose.makeNewForStaff(raw).failureOrNull()
            assertEquals("MEDIA_PURPOSE_INVALID" to "purpose", failure?.code to failure?.field, raw)
        }
    }

    @Test
    fun `an alt is trimmed, blank is none, over 200 is MEDIA_ALT_TOO_LONG on alt`() {
        assertEquals("کیف یاسی", MediaAlt.makeNew("  کیف یاسی ").getOrNull()?.value)
        assertNull(MediaAlt.makeNew("   ").getOrNull())
        assertNull(MediaAlt.makeNew(null).getOrNull())
        assertEquals("ب".repeat(200), MediaAlt.makeNew("ب".repeat(200)).getOrNull()?.value)
        val failure = MediaAlt.makeNew("ب".repeat(201)).failureOrNull()
        assertEquals(listOf("MEDIA_ALT_TOO_LONG", "alt", 200), listOf(failure?.code, failure?.field, failure?.meta?.get("maxLength")))
    }

    @Test
    fun `a stored path stays inside the media root - no leading slash, no dot segments, no backslash`() {
        assertTrue(MediaVariant.isRelativePath("2036/03/med_5Hq2xT9aLmQ0vB3nR7sK1w/w800.webp"))
        listOf("/etc/passwd", "2036/../../etc/passwd", "2036\\03\\x.webp", "", "a//b", ".tmp/x", "2036/03/x y.webp").forEach {
            assertFalse(MediaVariant.isRelativePath(it), it)
        }
        assertFailsWith<IllegalArgumentException> { MediaVariant.of(800, "../w800.webp") }
    }
}

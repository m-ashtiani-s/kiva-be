package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProductMediaTest {

    private val front = "https://cdn.kiva.ir/p/1/lilac/front.webp"

    @Test
    fun `a photo with a full address is kept, url trimmed and blank alt meaning none`() {
        val media = ProductMedia.makeNew(MediaType.IMAGE, " $front ", MediaView.FRONT, "  ").getOrNull()!!

        assertEquals(front, media.url)
        assertEquals(MediaView.FRONT, media.view)
        assertNull(media.alt)
    }

    @Test
    fun `plain http is allowed too, for local storage while developing`() {
        assertEquals(MediaType.VIDEO, ProductMedia.makeNew(MediaType.VIDEO, "http://localhost:9000/kiva/1.mp4", null, null).getOrNull()?.type)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "cdn.kiva.ir/p/1.webp", "ftp://cdn.kiva.ir/p/1.webp", "https://", "not a url"])
    fun `anything but a full http(s) address is MEDIA_URL_INVALID on the url field`(url: String) {
        val failure = ProductMedia.makeNew(MediaType.IMAGE, url, null, null).failureOrNull()!!

        assertEquals("MEDIA_INVALID", failure.code)
        assertEquals(listOf("MEDIA_URL_INVALID" to "url"), failure.causes.map { it.code to it.field })
    }

    @Test
    fun `a bad url and a too long alt are reported together`() {
        val failure = ProductMedia.makeNew(MediaType.IMAGE, "nope", null, "ع".repeat(201)).failureOrNull()!!

        assertEquals(listOf("MEDIA_URL_INVALID", "MEDIA_ALT_TOO_LONG"), failure.causes.map { it.code })
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { ProductMedia.constructFromPersisted(MediaType.IMAGE, "nope", null, null) }
        assertFailsWith<IllegalArgumentException> { ProductMedia.constructFromPersisted(MediaType.IMAGE, front, null, " ") }
    }
}

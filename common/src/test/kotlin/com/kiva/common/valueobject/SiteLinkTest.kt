package com.kiva.common.valueobject

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A link a panel form gives: a whole https address or a path of this site, nothing a browser would read as elsewhere. */
class SiteLinkTest {

    @ParameterizedTest
    @ValueSource(strings = ["/faq#reserve", "/products?onSale=true", "/", "https://kiva.ir/blog", "  /track  "])
    fun `a path of the site and a whole https address are links - trimmed`(raw: String) {
        assertEquals(raw.trim(), SiteLink.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["javascript:alert(1)", "//evil.example", "/\\evil.example", "http://kiva.ir", "faq", "https://", "/fa q", "/fa\tq", "mailto:a@b.ir"])
    fun `anything else is URL_INVALID`(raw: String) {
        assertNull(SiteLink.makeNew(raw).getOrNull(), raw)
        assertEquals("URL_INVALID", SiteLink.makeNew(raw).failureOrNull()?.code)
    }

    @ParameterizedTest
    @ValueSource(ints = [300, 301])
    fun `at most 300 letters`(length: Int) {
        val link = "/" + "a".repeat(length - 1)
        assertEquals(length <= 300, SiteLink.makeNew(link).getOrNull() != null)
    }
}

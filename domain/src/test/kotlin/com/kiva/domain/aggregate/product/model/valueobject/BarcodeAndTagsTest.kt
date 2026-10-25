package com.kiva.domain.aggregate.product.model.valueobject

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BarcodeAndTagsTest {

    @Test
    fun `a barcode is 4 to 32 Latin letters, digits and dashes, blank is none`() {
        assertEquals("6260000123456", Barcode.makeNew(" 6260000123456 ").getOrNull()?.value)
        assertNull(Barcode.makeNew("  ").getOrNull())
        assertNull(Barcode.makeNew(null).getOrNull())
    }

    @ParameterizedTest
    @ValueSource(strings = ["123", "۱۲۳۴۵", "12 34", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"])
    fun `anything else is BARCODE_INVALID`(raw: String) {
        assertEquals("BARCODE_INVALID", Barcode.makeNew(raw).failureOrNull()?.code)
    }

    @Test
    fun `tags are trimmed and kept once each, in order`() {
        assertEquals(listOf("چرم", "اداری"), ProductTags.makeNew(listOf(" چرم ", "اداری", "چرم")).getOrNull()?.items)
        assertEquals(ProductTags.NONE, ProductTags.makeNew(null).getOrNull())
    }

    @Test
    fun `21 tags is TAGS_TOO_MANY on tags, an empty or long tag is TAG_INVALID on its place`() {
        assertEquals("TAGS_TOO_MANY", ProductTags.makeNew(List(21) { "t$it" }).failureOrNull()?.code)
        assertEquals("tags", ProductTags.makeNew(List(21) { "t$it" }).failureOrNull()?.field)

        val bad = ProductTags.makeNew(listOf("خوب", " ", "ب".repeat(31))).failureOrNull()!!
        assertEquals(listOf("TAG_INVALID" to "tags.1", "TAG_INVALID" to "tags.2"), (bad.causes.ifEmpty { listOf(bad) }).map { it.code to it.field })
    }

    @Test
    fun `broken stored values are bugs`() {
        assertFailsWith<IllegalArgumentException> { Barcode.constructFromPersisted("1") }
        assertFailsWith<IllegalArgumentException> { ProductTags.constructFromPersisted(listOf(" ")) }
    }
}

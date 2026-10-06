package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HighlightsTest {

    @Test
    fun `lines are trimmed, blank lines dropped, and the order kept`() {
        val result = Highlights.makeNew(listOf(" چرم مصنوعی مات ", "", "   ", "یراق ضدزنگ"))

        assertEquals(listOf("چرم مصنوعی مات", "یراق ضدزنگ"), result.getOrNull()?.items)
    }

    @Test
    fun `no bullets at all is fine`() {
        assertEquals(Result.success(Highlights.NONE), Highlights.makeNew(emptyList()))
        assertEquals(Result.success(Highlights.NONE), Highlights.makeNew(listOf(" ", "")))
    }

    @Test
    fun `at most 8 bullets, and the failure tells the client the limit`() {
        assertEquals(8, Highlights.makeNew(List(8) { "بولت $it" }).getOrNull()?.items?.size)
        assertEquals(Result.failure(Highlights.Failure.TooMany), Highlights.makeNew(List(9) { "بولت $it" }))
        assertEquals(mapOf<String, Any>("maxItems" to 8), Highlights.Failure.TooMany.meta)
    }

    @Test
    fun `a bullet is at most 160 characters`() {
        assertEquals(Result.failure(Highlights.Failure.ItemTooLong), Highlights.makeNew(listOf("ب".repeat(161))))
        assertEquals("highlights", Highlights.Failure.ItemTooLong.field)
    }

    @Test
    fun `a blank stored bullet is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { Highlights.constructFromPersisted(listOf("یراق ضدزنگ", " ")) }
    }
}

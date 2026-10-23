package com.kiva.domain.aggregate.product.model.policy

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchQueryPolicyTest {

    @Test
    fun `normalized - Arabic letters, Persian digits, a half-space as a space, runs of spaces one - nothing but blanks is empty`() {
        assertEquals("کراس مشکی", SearchQueryPolicy.normalize("  كراس   مشکي "))
        assertEquals("کراس بادی 24", SearchQueryPolicy.normalize("کراس‌بادی ۲۴"))
        assertEquals("", SearchQueryPolicy.normalize("‌ "))
    }

    @Test
    fun `at most six words, small letters`() {
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), SearchQueryPolicy.wordsOf("A b c d e f g h"))
    }

    @Test
    fun `the matched colour is the longest whole-word name in the query - a part of a word is none`() {
        val palette = mapOf("green" to "سبز", "olive" to "سبز زیتونی", "black" to "مشکی")
        assertEquals("black", SearchQueryPolicy.matchedColorOf("کراس مشکی", palette))
        assertEquals("olive", SearchQueryPolicy.matchedColorOf("کیف سبز زیتونی", palette))
        assertNull(SearchQueryPolicy.matchedColorOf("کیف مشکیرنگ", palette))
    }
}

package com.kiva.infrastructure.pdf

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Persian text made drawable left to right (RtlText): letters joined, the line in the order it is seen. */
class RtlTextTest {

    @Test
    fun `the letters of a word take their joined forms, لا its ligature, and the word is turned to be drawn from the left`() {
        // «سلام»: س initial, لا final (joined to س), م alone (an alef joins nothing after it); from the left م لا س.
        assertEquals(listOf(0xFEE1, 0xFEFC, 0xFEB3), RtlText.visual("سلام").map { it.code })
    }

    @Test
    fun `Persian letters of their own are joined too - گیر is گ initial, ی medial, ر final`() {
        assertEquals(listOf(0xFEAE, 0xFBFF, 0xFB94), RtlText.visual("گیر").map { it.code })
    }

    @Test
    fun `پ چ ژ ک take their forms - after ا and ژ, which join nothing after them, a letter stands alone - and so does a hamza`() {
        assertEquals(listOf(0xFEEE, 0xFB59, 0xFB7C), RtlText.visual("چپو").map { it.code })
        assertEquals(listOf(0xFB8E, 0xFB8A, 0xFE8D), RtlText.visual("اژک").map { it.code })
        assertEquals(listOf(0xFE80, 0xFE8E, 0xFEB3), RtlText.visual("ساء").map { it.code })
    }

    @Test
    fun `a Latin code inside a Persian line keeps its own order`() {
        val visual = RtlText.visual("سفارش KV-215566")

        assertTrue("KV-215566" in visual, visual)
        assertTrue(visual.startsWith("KV-215566"), "the code comes first from the left: $visual")
    }

    @Test
    fun `Persian digits and the thousands separator keep their order`() {
        assertTrue("۲٬۷۹۰٬۰۰۰" in RtlText.visual("۲٬۷۹۰٬۰۰۰ تومان"))
    }

    @Test
    fun `the zero-width non-joiner of «می‌شه» breaks the join and leaves nothing to draw`() {
        val visual = RtlText.visual("می‌شه")

        assertTrue(visual.none { it.code == 0x200C }, visual)
        // م and ی joined, ش and ه joined, ی and ش not; from the left ه ش ی م.
        assertEquals(listOf(0xFEEA, 0xFEB7, 0xFBFD, 0xFEE3), visual.map { it.code })
    }
}

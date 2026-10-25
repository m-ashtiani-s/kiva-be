package com.kiva.infrastructure.pdf

import com.ibm.icu.text.Bidi

/**
 * Persian text for a PDF. PDFBox draws the characters it is given left to right, each glyph alone: it neither joins
 * Persian letters nor puts a right-to-left line in order. So a line is first shaped (PersianShaping: every letter
 * becomes its joined form, from Unicode's presentation forms, which Vazirmatn has), then ordered as it is seen (ICU's
 * Bidi, the paragraph right to left, so «KV-215566» and the digits keep their own order inside it).
 */
internal object RtlText {

    /** The marks that only steer joining or direction (ZWNJ, ZWJ, LRM, RLM, ALM): shaped and ordered, nothing is left to draw. */
    private val INVISIBLE: Set<Char> = setOf(0x200C, 0x200D, 0x200E, 0x200F, 0x061C).map(Int::toChar).toSet()

    /** [logical] (as typed) as it is drawn left to right. */
    fun visual(logical: String): String {
        val shaped = PersianShaping.shape(logical)
        val ordered = Bidi(shaped, Bidi.DIRECTION_RIGHT_TO_LEFT).writeReordered(Bidi.DO_MIRRORING.toInt())
        return ordered.filterNot { it in INVISIBLE }
    }
}

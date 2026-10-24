package com.kiva.infrastructure.pdf

/**
 * Joins Persian letters for a PDF: every letter becomes its isolated, final, initial or medial form from Unicode's
 * presentation forms, and لا (with any alef) its ligature. In logical order: [RtlText] orders the line afterwards.
 *
 * Its own table, not ICU's ArabicShaping: that one leaves Persian's own letters (پ چ ژ ک گ ی) unjoined (step 14.3 found
 * it), and they are in every receipt. The table has the letters a Persian text uses (with Arabic's ك ي ى ة and the
 * hamza forms); anything else stays as it is and joins nothing.
 */
internal object PersianShaping {

    /**
     * A letter's forms: [isolated] and [final] always; [initial] and [medial] only for one that joins the letter after
     * it. [joinsPrevious] is false only for the hamza, which joins nothing.
     */
    private class Forms(val isolated: Int, val final: Int, val initial: Int? = null, val medial: Int? = null, val joinsPrevious: Boolean = true) {
        val joinsNext: Boolean get() = initial != null
    }

    private fun dual(isolated: Int) = Forms(isolated, isolated + 1, isolated + 2, isolated + 3)

    private fun right(isolated: Int) = Forms(isolated, isolated + 1)

    // By code point: ء آ أ ؤ إ ئ ا ب ة ت ث ج ح خ د ذ ر ز س ش ص ض ط ظ ع غ ف ق ك ل م ن ه و ى ي, then پ چ ژ ک گ ی.
    private val FORMS: Map<Char, Forms> = mapOf(
        Char(0x0621) to Forms(0xFE80, 0xFE80, joinsPrevious = false),
        Char(0x0622) to right(0xFE81), Char(0x0623) to right(0xFE83), Char(0x0624) to right(0xFE85), Char(0x0625) to right(0xFE87),
        Char(0x0626) to dual(0xFE89), Char(0x0627) to right(0xFE8D), Char(0x0628) to dual(0xFE8F), Char(0x0629) to right(0xFE93),
        Char(0x062A) to dual(0xFE95), Char(0x062B) to dual(0xFE99), Char(0x062C) to dual(0xFE9D), Char(0x062D) to dual(0xFEA1),
        Char(0x062E) to dual(0xFEA5), Char(0x062F) to right(0xFEA9), Char(0x0630) to right(0xFEAB), Char(0x0631) to right(0xFEAD),
        Char(0x0632) to right(0xFEAF), Char(0x0633) to dual(0xFEB1), Char(0x0634) to dual(0xFEB5), Char(0x0635) to dual(0xFEB9),
        Char(0x0636) to dual(0xFEBD), Char(0x0637) to dual(0xFEC1), Char(0x0638) to dual(0xFEC5), Char(0x0639) to dual(0xFEC9),
        Char(0x063A) to dual(0xFECD), Char(0x0641) to dual(0xFED1), Char(0x0642) to dual(0xFED5), Char(0x0643) to dual(0xFED9),
        Char(0x0644) to dual(0xFEDD), Char(0x0645) to dual(0xFEE1), Char(0x0646) to dual(0xFEE5), Char(0x0647) to dual(0xFEE9),
        Char(0x0648) to right(0xFEED), Char(0x0649) to right(0xFEEF), Char(0x064A) to dual(0xFEF1),
        Char(0x067E) to dual(0xFB56), Char(0x0686) to dual(0xFB7A), Char(0x0698) to right(0xFB8A), Char(0x06A9) to dual(0xFB8E),
        Char(0x06AF) to dual(0xFB92), Char(0x06CC) to dual(0xFBFC),
    )

    /** لا with each alef: its isolated form (its final one is the next code point). */
    private val LAM_ALEF: Map<Char, Int> = mapOf(Char(0x0622) to 0xFEF5, Char(0x0623) to 0xFEF7, Char(0x0625) to 0xFEF9, Char(0x0627) to 0xFEFB)

    private val LAM = Char(0x0644)
    private val TATWEEL = Char(0x0640)
    private val ZWJ = Char(0x200D)

    /** Marks over or under a letter (harakat): they neither join nor break a join. */
    private fun isTransparent(char: Char): Boolean = char.code in 0x064B..0x065F || char.code == 0x0670

    /** [char], before a letter, joins on to it: a letter that joins the next one, tatweel or ZWJ. */
    private fun joinsBefore(char: Char?): Boolean = char != null && (FORMS[char]?.joinsNext == true || char == TATWEEL || char == ZWJ)

    /** [char], after a letter, takes the join from it: any letter of the table but the hamza, tatweel or ZWJ. */
    private fun joinsAfter(char: Char?): Boolean = char != null && (FORMS[char]?.joinsPrevious == true || char == TATWEEL || char == ZWJ)

    fun shape(logical: String): String {
        val out = StringBuilder(logical.length)
        var i = 0
        while (i < logical.length) {
            val char = logical[i]
            val forms = FORMS[char]
            if (forms == null) {
                out.append(char)
                i++
                continue
            }
            val previous = neighbour(logical, i, -1)
            val next = neighbour(logical, i, +1)
            val joinedBefore = forms.joinsPrevious && joinsBefore(previous)
            // لا: the lam and its alef are one glyph, joined to what is before the lam when that joins on.
            val nextIndex = indexOfNeighbour(logical, i, +1)
            if (char == LAM && nextIndex != null && logical[nextIndex] in LAM_ALEF) {
                val ligature = LAM_ALEF.getValue(logical[nextIndex])
                out.appendCodePoint(if (joinedBefore) ligature + 1 else ligature)
                i = nextIndex + 1
                continue
            }
            val joinedAfter = forms.joinsNext && joinsAfter(next)
            val form = when {
                joinedBefore && joinedAfter -> forms.medial
                joinedBefore -> forms.final
                joinedAfter -> forms.initial
                else -> forms.isolated
            }
            out.appendCodePoint(checkNotNull(form))
            i++
        }
        return out.toString()
    }

    /** The first character from [i] in [direction] that is not a mark over a letter, or null at the text's end. */
    private fun neighbour(text: String, i: Int, direction: Int): Char? = indexOfNeighbour(text, i, direction)?.let(text::get)

    private fun indexOfNeighbour(text: String, i: Int, direction: Int): Int? {
        var j = i + direction
        while (j in text.indices && isTransparent(text[j])) j += direction
        return j.takeIf { it in text.indices }
    }
}

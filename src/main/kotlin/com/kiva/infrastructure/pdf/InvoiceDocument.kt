package com.kiva.infrastructure.pdf

/**
 * A purchase receipt as it is laid out (roadmap section 19.3): every text ready to draw, in reading order (right to
 * left is the renderer's). No logic is left for the renderer: InvoiceTexts decides what is said.
 *
 * @property banner across the top when the order was cancelled («لغو شده»), otherwise null.
 * @property facts label and value: the number, the date of payment, the shipping, the tracking code.
 * @property rows one per line of the order, a text per column of [columns].
 * @property totals label and value, top to bottom; [payable] under them, bold.
 */
data class InvoiceDocument(
    val title: String,
    val note: String,
    val banner: String?,
    val seller: List<String>,
    val facts: List<Pair<String, String>>,
    val buyer: List<String>,
    val columns: List<String>,
    val rows: List<List<String>>,
    val totals: List<Pair<String, String>>,
    val payable: Pair<String, String>,
    val footer: String,
) {
    init {
        require(rows.all { it.size == columns.size }) { "Every row has a text per column" }
    }

    // The buyer's name and address are personal data.
    override fun toString(): String = "InvoiceDocument(rows=${rows.size})"
}

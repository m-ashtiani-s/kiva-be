package com.kiva.infrastructure.pdf

import com.kiva.application.dto.InvoiceDto
import com.kiva.application.port.InvoiceRenderer
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.InvoiceTexts
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The purchase receipt with Apache PDFBox (roadmap phase 14, decision 15): one A4 page, right to left, in Vazirmatn
 * (OFL, `resources/fonts/`), the fonts embedded as subsets. InvoiceTexts says what is written; this only lays it out:
 * the shop and the receipt's facts at the top, the buyer, a table of the lines, the totals, and a band «لغو شده» across
 * the top of a cancelled order's.
 *
 * Every line goes through RtlText (shaping and order) and is placed by its right edge. A character the font has no
 * glyph for is left out rather than failing the receipt.
 */
class PdfBoxInvoiceRenderer(
    private val regularFont: ByteArray = resource(REGULAR),
    private val boldFont: ByteArray = resource(BOLD),
) : InvoiceRenderer {

    override fun render(invoice: InvoiceDto): ByteArray = draw(InvoiceTexts.documentOf(invoice))

    /** The page of [document]. */
    fun draw(document: InvoiceDocument): ByteArray = PDDocument().use { pdf ->
        val page = PDPage(PDRectangle.A4)
        pdf.addPage(page)
        val fonts = Fonts(PDType0Font.load(pdf, ByteArrayInputStream(regularFont), true), PDType0Font.load(pdf, ByteArrayInputStream(boldFont), true))
        pdf.documentInformation.title = document.title
        PDPageContentStream(pdf, page).use { stream -> Page(stream, fonts, page.mediaBox).lay(document) }
        ByteArrayOutputStream().also(pdf::save).toByteArray()
    }

    private class Fonts(val regular: PDType0Font, val bold: PDType0Font)

    /** One page being written, from the top down; [y] is the baseline of the next line. */
    private class Page(private val stream: PDPageContentStream, private val fonts: Fonts, box: PDRectangle) {
        private val right = box.width - MARGIN
        private val left = MARGIN
        private val top = box.height - MARGIN
        private var y = top

        fun lay(document: InvoiceDocument) {
            document.banner?.let(::banner)
            header(document)
            rule()
            buyer(document)
            table(document)
            totals(document)
            footer(document)
        }

        private fun banner(text: String) {
            stream.setNonStrokingColor(BANNER)
            stream.addRect(left, top - 26f, right - left, 30f)
            stream.fill()
            stream.setNonStrokingColor(Color.WHITE)
            centered(text, fonts.bold, 16f, top - 18f)
            stream.setNonStrokingColor(Color.BLACK)
            y = top - 52f
        }

        /** The title and the note on the right, the shop under them; the receipt's facts on the left. */
        private fun header(document: InvoiceDocument) {
            val start = y
            write(document.title, fonts.bold, 20f, right, y)
            y -= 18f
            write(document.note, fonts.regular, 9f, right, y, GRAY)
            y -= 22f
            write(InvoiceTexts.SELLER, fonts.bold, 10f, right, y)
            document.seller.forEach { line ->
                y -= 15f
                write(line, fonts.regular, 10f, right, y)
            }
            var factsY = start
            val factsRight = left + FACTS_WIDTH
            document.facts.forEach { (label, value) ->
                write(label, fonts.bold, 10f, factsRight, factsY)
                write(value, fonts.regular, 10f, factsRight - LABEL_WIDTH, factsY)
                factsY -= 16f
            }
            y = minOf(y, factsY) - 12f
        }

        private fun buyer(document: InvoiceDocument) {
            y -= 18f
            write(InvoiceTexts.BUYER, fonts.bold, 11f, right, y)
            document.buyer.forEach { line ->
                y -= 15f
                write(line, fonts.regular, 10f, right, y)
            }
            y -= 22f
        }

        /** A header row on grey, then one row per line, the columns from the right in [COLUMN_WIDTHS]. */
        private fun table(document: InvoiceDocument) {
            stream.setNonStrokingColor(HEADER)
            stream.addRect(left, y - 6f, right - left, ROW_HEIGHT)
            stream.fill()
            stream.setNonStrokingColor(Color.BLACK)
            row(document.columns, fonts.bold)
            document.rows.forEach { cells ->
                y -= ROW_HEIGHT
                row(cells, fonts.regular)
                stream.setStrokingColor(RULE)
                stream.moveTo(left, y - 6f)
                stream.lineTo(right, y - 6f)
                stream.stroke()
            }
            y -= ROW_HEIGHT + 10f
        }

        private fun row(cells: List<String>, font: PDType0Font) {
            var cellRight = right - 4f
            cells.zip(COLUMN_WIDTHS).forEach { (text, width) ->
                write(fit(text, font, 9f, width - 6f), font, 9f, cellRight, y)
                cellRight -= width
            }
        }

        /** Label on the right, amount on the left, in a box of [TOTALS_WIDTH] at the left of the page. */
        private fun totals(document: InvoiceDocument) {
            val boxRight = left + TOTALS_WIDTH
            document.totals.forEach { (label, value) ->
                write(label, fonts.regular, 10f, boxRight, y)
                write(value, fonts.regular, 10f, left + AMOUNT_WIDTH, y)
                y -= 16f
            }
            stream.setStrokingColor(RULE)
            stream.moveTo(left, y + 10f)
            stream.lineTo(boxRight, y + 10f)
            stream.stroke()
            y -= 4f
            write(document.payable.first, fonts.bold, 12f, boxRight, y)
            write(document.payable.second, fonts.bold, 12f, left + AMOUNT_WIDTH, y)
            y -= 30f
        }

        private fun footer(document: InvoiceDocument) {
            centered(document.footer, fonts.regular, 10f, MARGIN, GRAY)
        }

        private fun rule() {
            stream.setStrokingColor(RULE)
            stream.moveTo(left, y)
            stream.lineTo(right, y)
            stream.stroke()
        }

        /** [text] with its right edge at [xRight]. */
        private fun write(text: String, font: PDType0Font, size: Float, xRight: Float, baseline: Float, color: Color = Color.BLACK) {
            val visual = drawable(RtlText.visual(text), font)
            if (visual.isEmpty()) return
            stream.setNonStrokingColor(color)
            stream.beginText()
            stream.setFont(font, size)
            stream.newLineAtOffset(xRight - widthOf(visual, font, size), baseline)
            stream.showText(visual)
            stream.endText()
            stream.setNonStrokingColor(Color.BLACK)
        }

        private fun centered(text: String, font: PDType0Font, size: Float, baseline: Float, color: Color = Color.BLACK) {
            val visual = drawable(RtlText.visual(text), font)
            write(text, font, size, (left + right) / 2 + widthOf(visual, font, size) / 2, baseline, color)
        }

        /** [text] cut to [width] with «…» at its end (its left, once drawn), if it is wider. */
        private fun fit(text: String, font: PDType0Font, size: Float, width: Float): String {
            if (widthOf(drawable(RtlText.visual(text), font), font, size) <= width) return text
            var cut = text
            while (cut.isNotEmpty() && widthOf(drawable(RtlText.visual("$cut…"), font), font, size) > width) cut = cut.dropLast(1)
            return "$cut…"
        }

        private fun widthOf(visual: String, font: PDType0Font, size: Float): Float = font.getStringWidth(visual) / 1000f * size

        /** What of [visual] the font can draw. */
        private fun drawable(visual: String, font: PDType0Font): String =
            visual.filter { char -> runCatching { font.encode(char.toString()) }.isSuccess }
    }

    companion object {
        const val REGULAR = "/fonts/Vazirmatn-Regular.ttf"
        const val BOLD = "/fonts/Vazirmatn-Bold.ttf"

        private const val MARGIN = 40f
        private const val ROW_HEIGHT = 22f
        private const val FACTS_WIDTH = 250f
        private const val LABEL_WIDTH = 70f
        private const val TOTALS_WIDTH = 260f
        private const val AMOUNT_WIDTH = 110f

        /** ردیف، کالا، رنگ، تعداد، قیمت واحد، قیمت قبل از تخفیف، جمع: 515 points, the page less its margins. */
        private val COLUMN_WIDTHS = listOf(32f, 130f, 60f, 38f, 80f, 95f, 80f)

        private val GRAY = Color(0x6B, 0x6B, 0x6B)
        private val RULE = Color(0xD9, 0xD4, 0xCC)
        private val HEADER = Color(0xF2, 0xEE, 0xE8)
        private val BANNER = Color(0xB4, 0x3C, 0x3C)

        private fun resource(path: String): ByteArray =
            checkNotNull(PdfBoxInvoiceRenderer::class.java.getResourceAsStream(path)) { "The font $path is not in the resources" }.use { it.readBytes() }
    }
}

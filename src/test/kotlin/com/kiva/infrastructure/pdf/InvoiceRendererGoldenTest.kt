package com.kiva.infrastructure.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The receipt's page, drawn, against a picture of it checked once by eye (roadmap step 14.3): text taken back out of a
 * PDF of shaped Persian can't be trusted, so the only way to see «the letters join and run right to left» is to look.
 * The picture is `src/test/resources/golden/invoice.png`; it changes only on purpose, with a word in the commit about
 * why. When it is missing or differs, the page drawn now is written to `build/golden/` to look at.
 */
class InvoiceRendererGoldenTest {

    private val renderer = PdfBoxInvoiceRenderer()

    /** A fixed receipt: two lines, a discount code, free shipping. */
    private val document = InvoiceDocument(
        title = "رسید خرید",
        note = "این رسید فاکتور رسمی مالیاتی نیست",
        banner = null,
        seller = listOf("کیوا", "۰۲۱۹۱۰۰۲۳۴۵", "support@kiva.ir", "kiva.ir"),
        facts = listOf("شماره" to "KV-215566", "تاریخ پرداخت" to "پنجشنبه ۲۳ مهر ۱۴۰۵، ساعت ۱۱:۳۰", "روش ارسال" to "پست پیشتاز", "کد رهگیری" to "TPX4821760"),
        buyer = listOf("سارا محمدی", "۰۹۱۲۳۴۵۶۷۸۹", "تهران، تهران، خیابان ولیعصر، کوچه نسترن، پلاک ۱۲، واحد ۴", "کد پستی ۱۹۶۵۸۴۳۱۱۷"),
        columns = listOf("ردیف", "کالا", "رنگ", "تعداد", "قیمت واحد", "قیمت قبل از تخفیف", "جمع"),
        rows = listOf(
            listOf("۱", "کیف دستی آیدا", "کاراملی", "۱", "۲٬۷۹۰٬۰۰۰ تومان", "۳٬۲۹۰٬۰۰۰ تومان", "۲٬۷۹۰٬۰۰۰ تومان"),
            listOf("۲", "کیف پول چرمی KV-1015", "قهوه‌ای", "۲", "۶۹۰٬۰۰۰ تومان", "—", "۱٬۳۸۰٬۰۰۰ تومان"),
        ),
        totals = listOf(
            "قیمت کالاها" to "۴٬۶۷۰٬۰۰۰ تومان",
            "تخفیف محصولات" to "۵۰۰٬۰۰۰ تومان",
            "جمع" to "۴٬۱۷۰٬۰۰۰ تومان",
            "کد تخفیف KIVA10" to "۴۱۷٬۰۰۰ تومان",
            "هزینه‌ی ارسال" to "رایگان",
        ),
        payable = "پرداخت‌شده" to "۳٬۷۵۳٬۰۰۰ تومان",
        footer = "ممنون که از کیوا خرید کردی",
    )

    private fun page(pdf: ByteArray): BufferedImage = Loader.loadPDF(pdf).use { PDFRenderer(it).renderImageWithDPI(0, DPI, ImageType.RGB) }

    private fun saved(image: BufferedImage, name: String): Path {
        val path = Path.of("build", "golden", name)
        Files.createDirectories(path.parent)
        ImageIO.write(image, "png", path.toFile())
        return path.toAbsolutePath()
    }

    @Test
    fun `the receipt's page looks as it was checked - at most half a percent of its pixels differ`() {
        val pdf = renderer.draw(document)
        // For opening in a real PDF viewer by hand.
        Files.createDirectories(Path.of("build", "golden"))
        Files.write(Path.of("build", "golden", "invoice.pdf"), pdf)
        val drawn = page(pdf)
        val golden = javaClass.getResourceAsStream(GOLDEN)?.use { ImageIO.read(it) }
            ?: fail("No $GOLDEN yet; the page drawn now is ${saved(drawn, "invoice.png")}: look at it, then copy it to src/test/resources$GOLDEN")

        assertEquals(golden.width to golden.height, drawn.width to drawn.height)
        val differing = (0 until drawn.height).sumOf { y -> (0 until drawn.width).count { x -> differs(golden.getRGB(x, y), drawn.getRGB(x, y)) } }
        val share = differing.toDouble() / (drawn.width * drawn.height)
        if (share > MAX_DIFFERING) fail("${"%.3f".format(share * 100)}% of the pixels differ; the page drawn now is ${saved(drawn, "invoice-actual.png")}")
    }

    @Test
    fun `a cancelled order's receipt has its red band across the top - the same receipt without it has none`() {
        val cancelled = page(renderer.draw(document.copy(banner = "لغو شده")))
        val plain = page(renderer.draw(document))

        // Halfway across, a few points under the top margin: inside the band.
        val x = cancelled.width / 2 - 120
        val y = (MARGIN_POINTS + 4) * DPI.toInt() / 72
        assertTrue(isBandRed(cancelled.getRGB(x, y)), "no band at ($x, $y): ${Integer.toHexString(cancelled.getRGB(x, y))}")
        assertTrue(!isBandRed(plain.getRGB(x, y)))
    }

    @Test
    fun `it is a PDF of one A4 page with the fonts embedded`() {
        Loader.loadPDF(renderer.draw(document)).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            assertEquals(595, pdf.getPage(0).mediaBox.width.toInt())
            val fonts = pdf.getPage(0).resources.fontNames.map { pdf.getPage(0).resources.getFont(it) }
            assertTrue(fonts.isNotEmpty() && fonts.all { it.isEmbedded }, "${fonts.map { it.name }}")
        }
    }

    @Test
    fun `a text with a character the fonts can't draw is drawn without it - the receipt doesn't fail`() {
        val odd = document.copy(buyer = listOf("سارا 😀 محمدی"))

        val pdf = renderer.draw(odd)

        assertTrue(pdf.isNotEmpty() && ByteArrayInputStream(pdf).readNBytes(5).decodeToString() == "%PDF-")
    }

    private fun differs(a: Int, b: Int): Boolean =
        listOf(16, 8, 0).any { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) > CHANNEL_TOLERANCE }

    private fun isBandRed(rgb: Int): Boolean = (rgb shr 16 and 0xFF) > 150 && (rgb shr 8 and 0xFF) < 90 && (rgb and 0xFF) < 90

    private companion object {
        const val GOLDEN = "/golden/invoice.png"
        const val DPI = 100f
        const val MAX_DIFFERING = 0.005
        const val CHANNEL_TOLERANCE = 40
        const val MARGIN_POINTS = 40
    }
}

package com.kiva.domain.aggregate.blog.model.valueobject

import com.kiva.domain.aggregate.blog.BlogFixtures.block
import com.kiva.domain.aggregate.blog.BlogFixtures.heading
import com.kiva.domain.aggregate.blog.BlogFixtures.paragraph
import com.kiva.domain.aggregate.blog.BlogFixtures.sanitizer
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A post's blocks as the form sends them (roadmap phase 15, decision 7): each kind with its own fields only. */
class BlogBlocksTest {

    private val photo = "med_" + "1".padStart(22, '0')

    private fun made(vararg raw: BlogBlocks.Raw?): List<BlogBlock> = BlogBlocks.makeNew(raw.toList(), sanitizer).getOrNull() ?: error("refused: ${problems(*raw)}")

    /** `field code` of every problem. */
    private fun problems(vararg raw: BlogBlocks.Raw?): List<String> =
        BlogBlocks.makeNew(raw.toList(), sanitizer).failureOrNull()?.causes?.map { "${it.field} ${it.code}" } ?: error("not refused")

    @Nested
    inner class EachKind {
        @Test
        fun `a heading takes its text and an anchor in the order of the headings - s0, s1`() {
            val blocks = made(heading, paragraph, block("HEADING", text = "قدهای بلند"))

            assertEquals(listOf(BlogBlock.Heading("s0", "چرا فرم بدن مهمه؟"), BlogBlock.Heading("s1", "قدهای بلند")), blocks.filterIsInstance<BlogBlock.Heading>())
        }

        @Test
        fun `a paragraph is plain text or sanitized HTML`() {
            val blocks = made(block("PARAGRAPH", text = "متن ساده"), block("PARAGRAPH", html = "<p>سلام <script>alert(1)</script><b>دنیا</b></p>"))

            assertEquals(BlogBlock.Paragraph(TextOrHtml.Text("متن ساده")), blocks[0])
            assertEquals(BlogBlock.Paragraph(TextOrHtml.Html("<p>سلام <b>دنیا</b></p>")), blocks[1])
        }

        @Test
        fun `a quote, a tip with or without its title, a product, a photo with its caption and a list`() {
            val blocks = made(
                block("QUOTE", text = "کیف باید با تناسب بدنت هم‌اندازه باشه"),
                block("TIP", text = "از ما عکس با مدل بخواه"),
                block("TIP", title = "یادت نره", html = "<p>هر ۳ ماه</p>"),
                block("PRODUCT", productId = 7),
                block("IMAGE", imageMediaId = photo, text = "کیف هوبو"),
                block("LIST", items = listOf("شاپر", "هوبو")),
            )

            assertEquals(
                listOf(
                    BlogBlock.Quote("کیف باید با تناسب بدنت هم‌اندازه باشه"),
                    BlogBlock.Tip(null, TextOrHtml.Text("از ما عکس با مدل بخواه")),
                    BlogBlock.Tip("یادت نره", TextOrHtml.Html("<p>هر ۳ ماه</p>")),
                    BlogBlock.ProductCard(ProductId.constructFromPersisted(7)),
                    BlogBlock.Image(MediaId.constructFromPersisted(photo), "کیف هوبو"),
                    BlogBlock.ListBlock(listOf("شاپر", "هوبو")),
                ),
                blocks,
            )
        }

        @Test
        fun `another kind's fields are not kept - a heading with html, items and a product is only its text`() {
            val blocks = made(block("HEADING", text = "تیتر", html = "<p>نه</p>", items = listOf("نه"), productId = 9, imageMediaId = photo))

            assertEquals(listOf(BlogBlock.Heading("s0", "تیتر")), blocks)
        }
    }

    @Nested
    inner class Refused {
        @Test
        fun `no blocks or more than 100 is refused on blocks`() {
            assertEquals(listOf("blocks FIELD_REQUIRED"), problems())
            assertEquals(listOf("blocks TOO_MANY_ITEMS"), problems(*Array(101) { paragraph }))
        }

        @Test
        fun `every incomplete block at once, each under blocks dot its place dot its field`() {
            assertEquals(
                listOf(
                    "blocks.0.type FIELD_REQUIRED",
                    "blocks.1.type BLOG_BLOCK_TYPE_INVALID",
                    "blocks.2.text FIELD_REQUIRED",
                    "blocks.3.html BLOG_BLOCK_TEXT_OR_HTML",
                    "blocks.4.productId FIELD_REQUIRED",
                    "blocks.5.imageMediaId FIELD_REQUIRED",
                    "blocks.6.items FIELD_REQUIRED",
                    "blocks.7.items.1 FIELD_REQUIRED",
                    "blocks.8.text TEXT_TOO_LONG",
                    "blocks.9.html TEXT_TOO_LONG",
                    "blocks.10.title TEXT_TOO_LONG",
                    "blocks.11.items TOO_MANY_ITEMS",
                    "blocks.12.imageMediaId MEDIA_ID_INVALID",
                    "blocks.13 FIELD_REQUIRED",
                ),
                problems(
                    block(null, text = "بی نوع"),
                    block("VIDEO", text = "نوع ناشناخته"),
                    block("HEADING"),
                    block("PARAGRAPH", text = "هر دو", html = "<p>هر دو</p>"),
                    block("PRODUCT"),
                    block("IMAGE", text = "زیرنویس بی عکس"),
                    block("LIST", items = emptyList()),
                    block("LIST", items = listOf("یکی", "  ")),
                    block("QUOTE", text = "ن".repeat(501)),
                    block("PARAGRAPH", html = "<p>" + "ن".repeat(5000) + "</p>"),
                    block("TIP", title = "ن".repeat(61), text = "نکته"),
                    block("LIST", items = List(21) { "مورد" }),
                    block("IMAGE", imageMediaId = "not-a-media"),
                    null,
                ),
            )
        }

        @Test
        fun `a paragraph of only a script has nothing left - it is empty`() {
            assertEquals(listOf("blocks.0.text FIELD_REQUIRED"), problems(block("PARAGRAPH", html = "<script>alert(1)</script>")))
        }
    }
}

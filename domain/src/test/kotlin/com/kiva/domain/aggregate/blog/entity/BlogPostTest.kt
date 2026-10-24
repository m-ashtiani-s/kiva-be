package com.kiva.domain.aggregate.blog.entity

import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.blog.BlogFixtures.block
import com.kiva.domain.aggregate.blog.BlogFixtures.draft
import com.kiva.domain.aggregate.blog.BlogFixtures.editor
import com.kiva.domain.aggregate.blog.BlogFixtures.fields
import com.kiva.domain.aggregate.blog.BlogFixtures.heading
import com.kiva.domain.aggregate.blog.BlogFixtures.now
import com.kiva.domain.aggregate.blog.BlogFixtures.paragraph
import com.kiva.domain.aggregate.blog.BlogFixtures.post
import com.kiva.domain.aggregate.blog.BlogFixtures.sanitizer
import com.kiva.domain.aggregate.blog.BlogFixtures.teamKiva
import com.kiva.domain.aggregate.blog.model.BlogPostDraft
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostStatus
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A post of the blog (roadmap phase 15, section 20.3): when the shop sees it, and what it works out by itself. */
class BlogPostTest {

    private val hour = Duration.ofHours(1)

    @Nested
    inner class Seen {
        @Test
        fun `a draft is never seen`() {
            assertFalse(post(draft(fields(status = "DRAFT"))).isVisibleAt(now.plus(hour)))
        }

        @Test
        fun `published with a date that came - seen from that moment, not before`() {
            val post = post(draft(fields(status = "PUBLISHED"), publishedAt = now.minus(hour)))

            assertTrue(post.isVisibleAt(now))
            assertFalse(post.isVisibleAt(now.minus(hour).minusSeconds(1)))
        }

        @Test
        fun `published with a date to come is a schedule - not seen until then`() {
            val post = post(draft(fields(status = "PUBLISHED"), publishedAt = now.plus(hour)))

            assertFalse(post.isVisibleAt(now))
            assertTrue(post.isScheduledAt(now))
            assertTrue(post.isVisibleAt(now.plus(hour)))
        }

        @Test
        fun `archived is kept but not seen, whatever its date`() {
            assertFalse(post(draft(fields(status = "ARCHIVED"), publishedAt = now.minus(hour))).isVisibleAt(now))
        }
    }

    @Nested
    inner class Publishing {
        @Test
        fun `published without a date is published now`() {
            assertEquals(now, post(draft(fields(status = "PUBLISHED"))).publishedAt)
        }

        @Test
        fun `published again without a date keeps the date it was first published on`() {
            val first = post(draft(fields(status = "PUBLISHED")))

            val edited = first.rewrite(draft(fields(status = "PUBLISHED", title = "عنوان تازه")), teamKiva, editor, now.plus(hour))

            assertEquals(now, edited.publishedAt)
            assertEquals(listOf(now, now.plus(hour)), listOf(edited.createdOn, edited.updatedOn))
        }

        @Test
        fun `a draft published later without a date is published when it is`() {
            val draftPost = post(draft(fields(status = "DRAFT")))

            assertEquals(now.plus(hour), draftPost.rewrite(draft(fields(status = "PUBLISHED")), teamKiva, editor, now.plus(hour)).publishedAt)
            assertNull(draftPost.publishedAt)
        }

        @Test
        fun `no status is a draft`() {
            assertEquals(BlogPostStatus.DRAFT, post().status)
        }
    }

    @Nested
    inner class WorkedOut {
        @Test
        fun `the table of contents is its headings with their anchors`() {
            val post = post(draft(fields(blocks = listOf(heading, paragraph, block("HEADING", text = "جمع‌بندی")))))

            assertEquals(listOf("s0" to "چرا فرم بدن مهمه؟", "s1" to "جمع‌بندی"), post.toc().map { it.anchor to it.text })
        }

        @Test
        fun `no reading time - the words of the lead and every block at 200 a minute, rounded up, at least 1`() {
            // 401 words, half in a heading and a list, half in HTML: 3 minutes.
            val html = "<p>" + List(361) { "کلمه" }.joinToString(" <b>") + "</p>"
            val blocks = listOf(block("HEADING", text = List(20) { "تیتر" }.joinToString(" ")), block("PARAGRAPH", html = html), block("LIST", items = List(20) { "مورد" }))
            assertEquals(3, post(draft(fields(lead = null, blocks = blocks))).effectiveReadingMinutes())
            assertEquals(1, post().effectiveReadingMinutes())
            assertEquals(12, post(draft(fields(readingMinutes = 12))).effectiveReadingMinutes())
        }

        @Test
        fun `no excerpt - the lead, or else the first paragraph without its HTML, cut at 160`() {
            assertEquals("قد، فرم شانه و سبک زندگیت تعیین می‌کنه چه کیفی بهت میاد.", post().effectiveExcerpt())
            val fromParagraph = post(draft(fields(lead = null, blocks = listOf(heading, block("PARAGRAPH", html = "<p>کیف <b>فقط</b> یه وسیله نیست</p>")))))
            assertEquals("کیف فقط یه وسیله نیست", fromParagraph.effectiveExcerpt())
            val long = post(draft(fields(lead = "ب".repeat(500))))
            assertEquals("ب".repeat(160) + "…", long.effectiveExcerpt())
            assertEquals("خلاصه‌ی خودش", post(draft(fields(excerpt = "خلاصه‌ی خودش"))).effectiveExcerpt())
        }
    }

    @Nested
    inner class TheForm {
        private fun problems(fields: BlogPostDraft.Fields): List<String> =
            BlogPostDraft.makeNew(fields, Result.success(null), sanitizer).failureOrNull()?.causes?.map { "${it.field} ${it.code}" } ?: error("not refused")

        @Test
        fun `a slug of digits only, no title, a category that can't be one, too long a reading time - all at once`() {
            assertEquals(
                listOf("title FIELD_REQUIRED", "slug SLUG_INVALID", "categoryId BLOG_CATEGORY_NOT_FOUND", "readingMinutes NUMBER_OUT_OF_RANGE", "status BLOG_POST_STATUS_INVALID"),
                problems(fields(title = " ", slug = "123", categoryId = 0, readingMinutes = 61, status = "LIVE")),
            )
        }

        @Test
        fun `tags are normalized and each kept once - more than 10 is refused, an empty one on its place`() {
            assertEquals(listOf("کیف", "فرم بدن"), draft(fields(tags = listOf("كيف", "کیف", " فرم بدن "))).tags.map { it.value })
            assertEquals(listOf("tags TOO_MANY_ITEMS"), problems(fields(tags = List(11) { "برچسب$it" })))
            assertEquals(listOf("tags.1 BLOG_TAG_EMPTY"), problems(fields(tags = listOf("کیف", "  "))))
        }

        @Test
        fun `a block's problem comes with the form's`() {
            assertEquals(listOf("slug FIELD_REQUIRED", "blocks.0.text FIELD_REQUIRED"), problems(fields(slug = null, blocks = listOf(block("QUOTE")))))
        }
    }
}

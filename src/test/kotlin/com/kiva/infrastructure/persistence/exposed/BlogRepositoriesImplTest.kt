package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.Result
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.blog.entity.BlogPost
import com.kiva.domain.aggregate.blog.model.BlogPostDraft
import com.kiva.domain.aggregate.blog.model.valueobject.BlogBlocks
import com.kiva.domain.aggregate.blog.model.valueobject.BlogCategoryId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogPostId
import com.kiva.domain.aggregate.blog.model.valueobject.BlogTag
import com.kiva.domain.aggregate.blog.repository.BlogPostRepository.PublicFilter
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.BlogPostTbl
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The blog's tables (V40) and repositories on kiva_test, each step in its own transaction as in the app. */
class BlogRepositoriesImplTest {

    private val db = TestDatabase.database
    private val posts = BlogPostRepositoryImpl()
    private val categories = BlogCategoryRepositoryImpl()
    private val authors = BlogAuthorRepositoryImpl()
    private val feedback = BlogFeedbackRepositoryImpl()
    private val now = Instant.parse("2026-10-24T08:00:00Z")
    private val editor = UserId.constructFromPersisted(30)

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) { BlogPostTbl.deleteAll() }
    }

    private fun category(slug: String): BlogCategoryId = transaction(db) { categories.findBySlug(Slug.constructFromPersisted(slug))!!.id }

    private fun stored(
        slug: String,
        category: String = "style",
        status: String = "PUBLISHED",
        publishedAt: Instant? = now.minus(Duration.ofHours(1)),
        tags: List<String> = emptyList(),
        featured: Boolean = false,
        text: String = "متن",
    ): BlogPost = transaction(db) {
        val fields = BlogPostDraft.Fields(
            "عنوان $slug", slug, null, null, category(category).value, null, null, null, null,
            listOf(BlogBlocks.Raw("HEADING", "تیتر", null, null, null, null, null), BlogBlocks.Raw("PARAGRAPH", null, "<p>$text</p>", null, null, null, null)),
            tags, featured, status, null, null, null,
        )
        val draft = BlogPostDraft.makeNew(fields, Result.success(publishedAt)) { it }.getOrNull()!!
        val post = BlogPost.create(posts.nextId(), draft, authors.findDefault()!!.id, editor, now)
        if (featured) posts.unfeatureAllExcept(post.id)
        posts.add(post, "عنوان $slug ${tags.joinToString(" ")} $text")
        post
    }

    private fun page(filter: PublicFilter = PublicFilter(null, emptyList(), null), size: Int = 10) =
        transaction(db) { posts.pagePublic(filter, now, Pageable.makeNew(1, size, null).getOrNull()!!) }

    @Nested
    inner class Seeded {
        @Test
        fun `the migration made the prototype's five categories in order, and one default author «تیم کیوا»`() {
            val all = transaction(db) { categories.findAll() }

            assertEquals(listOf("buying-guide", "style", "care", "materials", "behind-the-scenes"), all.map { it.slug.value })
            assertEquals("تیم کیوا", transaction(db) { authors.findDefault() }?.name)
        }
    }

    @Nested
    inner class Posts {
        @Test
        fun `a post is read back as it was stored, its blocks and tags included, by id and by slug`() {
            val post = stored("bag-by-body-shape", tags = listOf("فرم بدن"), text = "کیف <b>فقط</b>")

            val back = transaction(db) { posts.find(post.id) }!!
            assertEquals(listOf(post.blocks, post.tags, post.publishedAt, post.authorId), listOf(back.blocks, back.tags, back.publishedAt, back.authorId))
            assertEquals(post.id, transaction(db) { posts.findBySlug(Slug.constructFromPersisted("bag-by-body-shape")) }?.id)
        }

        @Test
        fun `a slug another post has is refused by the unique key - UniqueTakenException - and the check sees it`() {
            val first = stored("same-slug")

            assertFailsWith<UniqueTakenException> { stored("same-slug") }
            assertTrue(transaction(db) { posts.isSlugTaken(Slug.constructFromPersisted("same-slug"), except = null) })
            assertTrue(!transaction(db) { posts.isSlugTaken(Slug.constructFromPersisted("same-slug"), except = first.id) })
        }

        @Test
        fun `featuring a second post unfeatures the first - and the index refuses two featured`() {
            val first = stored("first", featured = true)
            val second = stored("second", featured = true)

            assertEquals(listOf(false, true), listOf(first, second).map { transaction(db) { posts.find(it.id) }!!.isFeatured })
            val twice = runCatching { transaction(db) { exec("UPDATE blogpost_tbl SET \"isFeatured\" = TRUE") } }.exceptionOrNull()?.message.orEmpty()
            assertTrue("blogpost_one_featured_idx" in twice, twice)
        }

        @Test
        fun `the shop sees published posts whose moment came, the latest first - not drafts, archived or scheduled ones`() {
            val old = stored("old", publishedAt = now.minus(Duration.ofDays(2)))
            val recent = stored("recent")
            stored("draft", status = "DRAFT")
            stored("archived", status = "ARCHIVED")
            stored("scheduled", publishedAt = now.plus(Duration.ofHours(1)))

            assertEquals(listOf(recent.id, old.id), page().items.map { it.id })
            assertEquals(2L, page().totalItems)
        }

        @Test
        fun `filters - a category, a tag, every word of q, and a post left out`() {
            val style = stored("style-post", tags = listOf("کیف دوشی"), text = "چرم طبیعی")
            val care = stored("care-post", category = "care", text = "تمیزکاری چرم")

            assertEquals(listOf(care.id), page(PublicFilter(category("care"), emptyList(), null)).items.map { it.id })
            assertEquals(listOf(style.id), page(PublicFilter(null, emptyList(), BlogTag.constructFromPersisted("کیف دوشی"))).items.map { it.id })
            assertEquals(listOf(care.id), page(PublicFilter(null, listOf("چرم", "تمیزکاری"), null)).items.map { it.id })
            assertEquals(listOf(care.id), page(PublicFilter(null, emptyList(), null, exclude = style.id)).items.map { it.id })
        }

        @Test
        fun `the featured post only while the shop sees it and it fits the filter`() {
            val featured = stored("featured", featured = true)

            assertEquals(featured.id, transaction(db) { posts.featured(PublicFilter(null, emptyList(), null), now) }?.id)
            assertNull(transaction(db) { posts.featured(PublicFilter(category("care"), emptyList(), null), now) })
            assertNull(transaction(db) { posts.featured(PublicFilter(null, emptyList(), null), now.minus(Duration.ofDays(1))) })
        }

        @Test
        fun `related - its category first, then the others, each the latest first, never itself`() {
            val post = stored("post")
            val otherCategoryNewer = stored("other", category = "care", publishedAt = now.minus(Duration.ofMinutes(1)))
            val sameCategoryOlder = stored("same", publishedAt = now.minus(Duration.ofDays(3)))

            assertEquals(listOf(sameCategoryOlder.id, otherCategoryNewer.id), transaction(db) { posts.related(post, now, 3) }.map { it.id })
            assertEquals(listOf(sameCategoryOlder.id), transaction(db) { posts.related(post, now, 1) }.map { it.id })
        }

        @Test
        fun `posts the shop sees, counted by category`() {
            stored("a")
            stored("b")
            stored("c", category = "care", status = "DRAFT")

            assertEquals(mapOf(category("style") to 2), transaction(db) { posts.countVisibleByCategory(now) })
        }
    }

    @Nested
    inner class Feedback {
        @Test
        fun `one vote per voter - a second replaces the first - counted by answer, comments newest first`() {
            val post = stored("voted")
            val id = post.id
            transaction(db) {
                feedback.upsert(id, "u:7", helpful = true, comment = null, now)
                feedback.upsert(id, "u:7", helpful = false, comment = "کوتاه بود", now.plusSeconds(1))
                feedback.upsert(id, "ip:" + "a".repeat(64), helpful = false, comment = "عکس نداشت", now.plusSeconds(2))
                feedback.upsert(id, "ip:" + "b".repeat(64), helpful = true, comment = null, now.plusSeconds(3))
            }

            assertEquals(1 to 2, transaction(db) { feedback.statsOf(id) }.let { it.helpful to it.notHelpful })
            assertEquals(listOf("عکس نداشت", "کوتاه بود"), transaction(db) { feedback.lastComments(id, 50) }.map { it.text })
            assertEquals(1, transaction(db) { feedback.deleteVotesOfUser(UserId.constructFromPersisted(7)) })
            assertEquals(1 to 1, transaction(db) { feedback.statsOf(id) }.let { it.helpful to it.notHelpful })
        }

        @Test
        fun `a voter key that isn't u-id or ip-hash, a raw IP above all, and a comment with yes are refused by the table`() {
            val post = stored("guarded")
            listOf("'ip:192.168.1.10', FALSE, NULL", "'7', TRUE, NULL", "'u:8', TRUE, 'عالی'").forEach { values ->
                val refused = runCatching { transaction(db) { exec("INSERT INTO blogfeedback_tbl VALUES (${post.id.value}, $values, now())") } }
                assertTrue(refused.isFailure, values)
            }
        }

        @Test
        fun `deleting a post takes its votes with it`() {
            val post = stored("gone")
            transaction(db) { feedback.upsert(post.id, "u:9", true, null, now) }

            transaction(db) { posts.delete(post.id) }

            assertEquals(0 to 0, transaction(db) { feedback.statsOf(post.id) }.let { it.helpful to it.notHelpful })
            assertNull(transaction(db) { posts.find(BlogPostId.constructFromPersisted(post.id.value)) })
        }
    }
}

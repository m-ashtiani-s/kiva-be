package com.kiva.domain.aggregate.blog.model

import com.kiva.common.railway.FieldFailure
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** One answer to «مفید بود؟» (roadmap phase 15, decision 14): a comment only with «no». */
class BlogVoteTest {

    private fun problems(helpful: Boolean?, comment: String?): List<String> =
        BlogVote.makeNew(helpful, comment).failureOrNull()?.causes.orEmpty().map { (it as FieldFailure).let { f -> "${f.field} ${f.code}" } }

    @Test
    fun `yes drops a comment - no keeps it trimmed - an empty one is none`() {
        val yes = BlogVote.makeNew(true, "عالی بود").getOrNull()!!
        val no = BlogVote.makeNew(false, "  مثال نداشت  ").getOrNull()!!

        assertEquals(listOf(true, null), listOf(yes.helpful, yes.comment))
        assertEquals(listOf(false, "مثال نداشت"), listOf(no.helpful, no.comment))
        assertEquals(null, BlogVote.makeNew(false, "   ").getOrNull()!!.comment)
    }

    @Test
    fun `helpful is required - a no comment over 300 or with a control character is refused - one with yes never is`() {
        assertEquals(listOf("helpful FIELD_REQUIRED"), problems(null, "چیزی"))
        assertEquals(listOf("comment TEXT_TOO_LONG"), problems(false, "ا".repeat(301)))
        assertEquals(listOf("comment TEXT_INVALID"), problems(false, "یک" + Char(0x07) + "دو"))
        assertEquals(emptyList(), problems(true, "ا".repeat(301)))
        assertEquals("ا".repeat(300), BlogVote.makeNew(false, "ا".repeat(300)).getOrNull()!!.comment)
    }
}

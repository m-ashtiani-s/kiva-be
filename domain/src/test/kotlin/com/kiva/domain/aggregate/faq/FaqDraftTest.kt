package com.kiva.domain.aggregate.faq

import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.faq.entity.FaqBook
import com.kiva.domain.aggregate.faq.model.FaqDraft
import com.kiva.domain.aggregate.faq.model.FaqDraft.GroupInput
import com.kiva.domain.aggregate.faq.model.FaqDraft.QuestionInput
import com.kiva.domain.aggregate.faq.model.valueobject.FaqItemId
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The FAQ form's rules (roadmap phase 5, decisions 9 and 18). */
class FaqDraftTest {

    private val sanitizer = HtmlSanitizer { it.replace(Regex("<script>.*?</script>"), "") }

    private fun question(id: Long? = null, text: String? = "رزرو چطوره؟", answer: String? = "<p>این‌طوری</p>") = QuestionInput(id, text, answer)

    private fun group(anchor: String? = "reserve", name: String? = "رزرو", vararg questions: QuestionInput) =
        GroupInput(anchor, name, null, null, questions.toList())

    private fun problemsOf(result: Result<FaqDraft, InputProblems>): List<String> = result.failureOrNull()?.causes.orEmpty().map { "${it.field} ${it.code}" }

    @Test
    fun `a good tree is kept in the arrays' order, answers cleaned, new questions without an id`() {
        val draft = FaqDraft.makeNew(
            listOf(group("reserve", "رزرو", question(7), question(answer = "<p>جواب</p><script>x</script>")), group("return", "بازگشت")),
            sanitizer,
        ).getOrNull()!!

        assertEquals(listOf("reserve", "return"), draft.groups.map { it.anchor.value })
        assertEquals(listOf(7L, null), draft.groups[0].questions.map { it.id?.value })
        assertEquals("<p>جواب</p>", draft.groups[0].questions[1].answer)
    }

    @Test
    fun `every problem at once, each on its path`() {
        val result = FaqDraft.makeNew(
            listOf(
                group("Reserve", " ", question(text = " ", answer = "<script>x</script>")),
                group("return", "بازگشت", question(id = 3), question(id = 3, text = "س".repeat(301))),
                group("return", "تکرار"),
            ),
            sanitizer,
        )

        assertEquals(
            listOf(
                "0.id FAQ_ANCHOR_INVALID", "0.name FAQ_GROUP_NAME_REQUIRED", "0.questions.0.question QUESTION_REQUIRED", "0.questions.0.answer ANSWER_REQUIRED",
                "1.questions.1.id LIST_ITEM_DUPLICATE", "1.questions.1.question TEXT_TOO_LONG", "2.id FAQ_ANCHOR_DUPLICATE",
            ),
            problemsOf(result),
        )
    }

    @Test
    fun `more than 20 groups is FAQ_TOO_MANY on groups, more than 50 questions on the group's questions`() {
        val many = (1..21).map { group("g$it", "گروه") }
        assertEquals(listOf("groups FAQ_TOO_MANY"), problemsOf(FaqDraft.makeNew(many, sanitizer)))

        val crowded = listOf(group("reserve", "رزرو", *Array(51) { question() }))
        assertEquals(listOf("0.questions FAQ_TOO_MANY"), problemsOf(FaqDraft.makeNew(crowded, sanitizer)))
    }

    @Test
    fun `an id the shop doesn't have is FAQ_ITEM_NOT_FOUND on its path`() {
        val draft = FaqDraft.makeNew(listOf(group("reserve", "رزرو", question(7), question(9))), sanitizer).getOrNull()!!

        assertEquals(listOf("0.questions.1.id FAQ_ITEM_NOT_FOUND"), draft.unknownIds(setOf(FaqItemId.constructFromPersisted(7))).map { "${it.field} ${it.code}" })
    }

    @Test
    fun `the book gives each new question an id in order - the same tree again is the same, one more question isn't`() {
        var next = 100L
        val book = FaqDraft.makeNew(listOf(group("reserve", "رزرو", question(7), question())), sanitizer).getOrNull()!!
            .toBook { FaqItemId.constructFromPersisted(++next) }

        assertEquals(listOf(7L, 101L), book.groups[0].questions.map { it.id.value })
        val again = FaqDraft.makeNew(listOf(group("reserve", "رزرو", question(7), question(101))), sanitizer).getOrNull()!!
        assertTrue(again.isSameAs(book))
        val more = FaqDraft.makeNew(listOf(group("reserve", "رزرو", question(7), question(101), question())), sanitizer).getOrNull()!!
        assertFalse(more.isSameAs(book))
        assertFalse(again.isSameAs(FaqBook.EMPTY))
    }
}

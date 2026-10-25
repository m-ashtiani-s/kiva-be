package com.kiva.domain.aggregate.faq.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.ListItemDuplicate
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.forField
import com.kiva.common.text.normalizePersianLetters
import com.kiva.domain.aggregate.faq.entity.FaqBook
import com.kiva.domain.aggregate.faq.model.valueobject.FaqAnchor
import com.kiva.domain.aggregate.faq.model.valueobject.FaqItemId
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import com.kiva.domain.aggregate.settings.model.valueobject.IconName

/**
 * The FAQ as the panel's form sends it (`PUT /admin/faq/groups`; roadmap phase 5, decision 9), checked on its own shape:
 * every problem at once, each on its path (`2.questions.0.answer`). A question with an id is that question, wherever it
 * now stands; one without is new. Whether each id is one the shop has is [unknownIds]'s, asked with the stored book.
 */
@ConsistentCopyVisibility
data class FaqDraft private constructor(val groups: List<Group>) {

    /** @property id null for a new question. @property answer the sanitized HTML. */
    data class Question(val id: FaqItemId?, val question: String, val answer: String) {
        override fun toString(): String = "Question(id=${id?.value})"
    }

    data class Group(val anchor: FaqAnchor, val name: String, val icon: IconName?, val isSignature: Boolean, val questions: List<Question>)

    /** A group as the form sent it; null fields are reported, not guessed. */
    data class GroupInput(val anchor: String?, val name: String?, val icon: String?, val isSignature: Boolean?, val questions: List<QuestionInput>?)

    data class QuestionInput(val id: Long?, val question: String?, val answer: String?) {
        override fun toString(): String = "QuestionInput(id=$id)"
    }

    /** Each id of this draft the shop doesn't have, as FAQ_ITEM_NOT_FOUND on its path. */
    fun unknownIds(known: Set<FaqItemId>): List<ResultFailure> = groups.flatMapIndexed { g, group ->
        group.questions.mapIndexedNotNull { q, question ->
            question.id?.takeIf { it !in known }?.let { FieldFailure("$g.questions.$q.id", Failure.ItemNotFound) }
        }
    }

    /** The book this draft makes: each new question gets an id from [newId], in the order they stand. */
    fun toBook(newId: () -> FaqItemId): FaqBook = FaqBook(
        groups.map { group ->
            FaqBook.Group(
                group.anchor, group.name, group.icon, group.isSignature,
                group.questions.map { FaqBook.Question(it.id ?: newId(), it.question, it.answer) },
            )
        },
    )

    /** True when it would leave [book] as it is: no new question, and every group and question the same, in the same order. */
    fun isSameAs(book: FaqBook): Boolean = groups.none { group -> group.questions.any { it.id == null } } && toBook { error("no new question") } == book

    companion object {
        const val MAX_GROUPS = 20
        const val MAX_QUESTIONS = 50
        const val MAX_NAME = 60
        const val MAX_QUESTION = 300
        const val MAX_ANSWER = 5_000

        /** Where a problem of the whole array (too many groups) is put: the body is an array with no name of its own (decision 24). */
        const val FIELD = "groups"

        fun makeNew(input: List<GroupInput>, sanitizer: HtmlSanitizer): Result<FaqDraft, InputProblems> {
            val problems = Problems()
            if (input.size > MAX_GROUPS) problems.add(FieldFailure(FIELD, Failure.TooMany(MAX_GROUPS)))
            val anchors = mutableSetOf<String>()
            val ids = mutableSetOf<Long>()
            val groups = input.mapIndexed { g, group ->
                val questionsIn = group.questions.orEmpty()
                if (questionsIn.size > MAX_QUESTIONS) problems.add(FieldFailure("$g.questions", Failure.TooMany(MAX_QUESTIONS)))
                GroupParts(
                    anchor = problems.take(anchorOf(group.anchor, anchors).forField("$g.id")),
                    name = problems.take(textOf(group.name, MAX_NAME, Failure.GroupNameRequired).forField("$g.name")),
                    icon = problems.take(iconOf(group.icon).forField("$g.icon")),
                    isSignature = group.isSignature ?: false,
                    questions = questionsIn.mapIndexed { q, question ->
                        val path = "$g.questions.$q"
                        QuestionParts(
                            id = problems.take(idOf(question.id, ids).forField("$path.id")),
                            question = problems.take(textOf(question.question, MAX_QUESTION, Failure.QuestionRequired).forField("$path.question")),
                            answer = problems.take(answerOf(question.answer, sanitizer).forField("$path.answer")),
                        )
                    },
                )
            }
            if (problems.any()) return Result.failure(problems.all())
            return Result.success(FaqDraft(groups.map { it.group() }))
        }

        /** A group's checked parts; read only when none of the form's parts failed. */
        private class GroupParts(
            val anchor: Problems.Part<FaqAnchor>,
            val name: Problems.Part<String>,
            val icon: Problems.Part<IconName?>,
            val isSignature: Boolean,
            val questions: List<QuestionParts>,
        ) {
            fun group() = Group(anchor.value, name.value, icon.value, isSignature, questions.map { it.question() })
        }

        private class QuestionParts(val id: Problems.Part<FaqItemId?>, val question: Problems.Part<String>, val answer: Problems.Part<String>) {
            fun question() = Question(id.value, question.value, answer.value)
        }

        private fun anchorOf(raw: String?, seen: MutableSet<String>): Result<FaqAnchor, ResultFailure> {
            val anchor = FaqAnchor.makeNew(raw.orEmpty()).getOrNull() ?: return Result.failure(FaqAnchor.Failure.Invalid)
            return if (seen.add(anchor.value)) Result.success(anchor) else Result.failure(Failure.AnchorDuplicate)
        }

        private fun textOf(raw: String?, max: Int, required: Failure): Result<String, ResultFailure> {
            val text = raw?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(required)
            return if (text.length > max) Result.failure(TextTooLong(max)) else Result.success(text)
        }

        private fun iconOf(raw: String?): Result<IconName?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return IconName.makeNew(text)
        }

        private fun idOf(raw: Long?, seen: MutableSet<Long>): Result<FaqItemId?, ResultFailure> {
            if (raw == null) return Result.success(null)
            if (raw <= 0) return Result.failure(Failure.ItemNotFound)
            return if (seen.add(raw)) Result.success(FaqItemId.constructFromPersisted(raw)) else Result.failure(ListItemDuplicate)
        }

        private fun answerOf(raw: String?, sanitizer: HtmlSanitizer): Result<String, ResultFailure> {
            val clean = raw?.let(sanitizer::clean)?.trim()?.ifEmpty { null } ?: return Result.failure(Failure.AnswerRequired)
            return if (clean.length > MAX_ANSWER) Result.failure(TextTooLong(MAX_ANSWER)) else Result.success(clean)
        }
    }

    // No field here: the draft puts each on its path.
    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, meta = meta) {
        data object AnchorDuplicate : Failure("FAQ_ANCHOR_DUPLICATE")
        data object GroupNameRequired : Failure("FAQ_GROUP_NAME_REQUIRED")
        data object QuestionRequired : Failure("QUESTION_REQUIRED")
        data object AnswerRequired : Failure("ANSWER_REQUIRED")
        data object ItemNotFound : Failure("FAQ_ITEM_NOT_FOUND")
        data class TooMany(val maxItems: Int) : Failure("FAQ_TOO_MANY", mapOf("maxItems" to maxItems))
    }
}

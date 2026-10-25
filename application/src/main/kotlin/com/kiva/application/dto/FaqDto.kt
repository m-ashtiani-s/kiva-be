package com.kiva.application.dto

import com.kiva.domain.aggregate.faq.entity.FaqBook

/**
 * A FAQ group as the contract's FaqGroup shows it, its questions in order.
 *
 * @property anchor the contract's `id` (`reserve`).
 */
data class FaqGroupDto(val anchor: String, val name: String, val icon: String?, val isSignature: Boolean, val questions: List<FaqItemDto>) {
    companion object {
        fun from(group: FaqBook.Group, questions: List<FaqItemDto> = group.questions.map(FaqItemDto::from)) =
            FaqGroupDto(group.anchor.value, group.name, group.icon?.value, group.isSignature, questions)
    }
}

/**
 * A FAQ question. [questionHighlighted] and [answerHighlighted] are filled only by a search: the question escaped and the
 * answer's HTML, each with `<mark>` around the matches.
 */
data class FaqItemDto(
    val id: Long,
    val question: String,
    val answer: String,
    val questionHighlighted: String? = null,
    val answerHighlighted: String? = null,
) {
    override fun toString(): String = "FaqItemDto(id=$id)"

    companion object {
        fun from(question: FaqBook.Question) = FaqItemDto(question.id.value, question.question, question.answer)
    }
}

/**
 * What `GET /faq` shows: the groups (with a search, only those with a match, and in each only the matching questions),
 * and how many questions that is.
 */
data class FaqPageDto(val groups: List<FaqGroupDto>, val totalMatches: Int)

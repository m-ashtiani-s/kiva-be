package com.kiva.domain.aggregate.faq.entity

import com.kiva.domain.aggregate.faq.model.valueobject.FaqAnchor
import com.kiva.domain.aggregate.faq.model.valueobject.FaqItemId
import com.kiva.domain.aggregate.settings.model.valueobject.IconName

/**
 * The whole FAQ (roadmap phase 5, step 5.2): its groups in order, each with its questions in order. Written whole
 * (`PUT /admin/faq/groups`, from a FaqDraft), so the order is the arrays' and nothing else (decision 9).
 */
data class FaqBook(val groups: List<Group>) {

    /** @property answer the sanitized HTML. */
    data class Question(val id: FaqItemId, val question: String, val answer: String) {
        override fun toString(): String = "Question(id=${id.value})"
    }

    data class Group(val anchor: FaqAnchor, val name: String, val icon: IconName?, val isSignature: Boolean, val questions: List<Question>)

    // The draft refuses these with a Failure; only broken stored data gets here. A bug: stop loudly.
    init {
        require(groups.map { it.anchor }.toSet().size == groups.size) { "Two FAQ groups share an anchor" }
        val ids = groups.flatMap { group -> group.questions.map { it.id } }
        require(ids.toSet().size == ids.size) { "Two FAQ questions share an id" }
    }

    /** Every question's id. */
    val itemIds: Set<FaqItemId> get() = groups.flatMapTo(mutableSetOf()) { group -> group.questions.map { it.id } }

    val questionsCount: Int get() = groups.sumOf { it.questions.size }

    override fun toString(): String = "FaqBook(${groups.size} groups, $questionsCount questions)"

    companion object {
        val EMPTY = FaqBook(emptyList())
    }
}

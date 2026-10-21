package com.kiva.application.usecase.content.usecase.query

import com.kiva.application.dto.FaqGroupDto
import com.kiva.application.dto.FaqItemDto
import com.kiva.application.dto.FaqPageDto
import com.kiva.application.port.HtmlText
import com.kiva.application.usecase.content.FaqSearch
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.faq.repository.FaqRepository

/**
 * The site's FAQ (`GET /faq`, public; roadmap phase 5, decisions 10 and 17). Without `q`, every group and question in
 * order. With `q`, only the questions whose text or answer's text contains it, in the groups that have one, each with
 * the matches marked: the question escaped (it is plain text), the answer's HTML marked inside its text only.
 */
class GetFaqUc(
    private val transactionManager: TransactionManager,
    private val faqRepository: FaqRepository,
    private val htmlText: HtmlText,
) : QueryUseCase<GetFaqUc.Qry, FaqPageDto, GetFaqUc.Failure> {

    private val logger = getLogger()

    /** @property q the query string's `q` as it came. */
    data class Qry(val q: String?)

    override suspend fun execute(query: Qry): Result<FaqPageDto, Failure> {
        logger.info("GetFaqUc.execute q=${query.q?.length} chars")
        val search = FaqSearch.of(query.q).unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it))) }

        return transactionManager.inTransaction {
            val book = faqRepository.load()
            if (search == null) return@inTransaction Result.success(FaqPageDto(book.groups.map { FaqGroupDto.from(it) }, book.questionsCount))

            val groups = book.groups.mapNotNull { group ->
                val found = group.questions
                    .filter { search.matches(it.question) || search.matches(htmlText.plainText(it.answer)) }
                    .map { FaqItemDto.from(it).copy(questionHighlighted = search.markText(it.question), answerHighlighted = htmlText.mark(it.answer, search::rangesIn)) }
                found.takeIf { it.isNotEmpty() }?.let { FaqGroupDto.from(group, it) }
            }
            Result.success(FaqPageDto(groups, groups.sumOf { it.questions.size }))
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure>) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}

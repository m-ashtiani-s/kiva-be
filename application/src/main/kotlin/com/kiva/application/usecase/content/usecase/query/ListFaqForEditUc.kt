package com.kiva.application.usecase.content.usecase.query

import com.kiva.application.dto.FaqGroupDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.faq.repository.FaqRepository

/**
 * The whole FAQ for the panel's form (`GET /admin/faq/groups`; ADMIN, CONTENT_EDITOR), the shape `PUT` takes; also what
 * the `PUT` answers with. Never cached, and never highlighted.
 */
class ListFaqForEditUc(
    private val transactionManager: TransactionManager,
    private val faqRepository: FaqRepository,
) : QueryUseCase<ListFaqForEditUc.Qry, List<FaqGroupDto>, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<FaqGroupDto>, Nothing> {
        logger.info("ListFaqForEditUc.execute")
        return transactionManager.inTransaction { Result.success(faqRepository.load().groups.map { FaqGroupDto.from(it) }) }
    }
}
